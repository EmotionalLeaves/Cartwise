package com.emotionalleaves.cartwise.feed

import android.content.Context
import com.emotionalleaves.cartwise.data.BlockEntity
import com.emotionalleaves.cartwise.data.CartwiseDb
import com.emotionalleaves.cartwise.data.FeedCardEntity
import com.emotionalleaves.cartwise.data.ImpressionEntity
import com.emotionalleaves.cartwise.data.ItemEntity
import com.emotionalleaves.cartwise.data.Settings
import com.emotionalleaves.cartwise.data.WatchEntity
import com.emotionalleaves.cartwise.engine.Fmt
import com.emotionalleaves.cartwise.engine.Listing
import com.emotionalleaves.cartwise.engine.Load
import com.emotionalleaves.cartwise.engine.PageBudget
import com.emotionalleaves.cartwise.engine.Parse
import com.emotionalleaves.cartwise.engine.Product
import com.emotionalleaves.cartwise.engine.Reason
import com.emotionalleaves.cartwise.engine.ShopRepo
import com.emotionalleaves.cartwise.rank.Words
import com.emotionalleaves.cartwise.taste.SignalLog
import com.emotionalleaves.cartwise.taste.SignalType
import com.emotionalleaves.cartwise.taste.TasteModel
import com.emotionalleaves.cartwise.taste.Vec
import com.emotionalleaves.cartwise.taste.toItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean

enum class Shelf(val title: String) {
    RESTOCK("Time to restock"),
    PRICE_DROP("Price dropped"),
    GOES_WITH("Goes with what you own"),
    NEW_IN("New in your interests"),
    POPULAR("Popular"),
    WILDCARD("Wildcard"),
}

/**
 * Builds the For You feed: gathers candidates from places Amazon already shows,
 * scores them against your taste model and keeps only what clears the bar.
 * feed = 0.45 Fit + 0.20 Quality + 0.15 Timing + 0.10 Value + 0.10 Novelty
 */
class FeedBuilder(
    private val app: Context,
    private val db: CartwiseDb,
    private val shop: ShopRepo,
    private val taste: TasteModel,
    private val signals: SignalLog,
    private val settings: Settings,
) {
    data class Progress(
        val running: Boolean = false,
        val used: Int = 0,
        val max: Int = 0,
        val message: String = "",
        val finishedAt: Long = 0,
        val error: String? = null,
        /** Plain-language result of the last refresh, shown under the top bar. */
        val note: String? = null,
    )

    private class Cand(
        val l: Listing,
        val shelf: Shelf,
        val reason: String,
        val timing: Double,
        val category: String = "",
        val label: String = shelf.title,
    )

    private class Scored(
        val c: Cand,
        val score: Double,
        val fit: Double,
        val reason: String,
        val clusterId: String?,
        val toks: Set<String>,
    )

    val progress = MutableStateFlow(Progress())
    private val busy = AtomicBoolean(false)
    private val dao get() = db.dao()

    companion object {
        private const val DAY = 86_400_000L
    }

    suspend fun refresh(maxPages: Int, quick: Boolean = false): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        progress.value = Progress(running = true, max = maxPages, message = "Getting started", note = null)
        return try {
            val found = build(maxPages, quick)
            val note = when {
                found < 0 -> "Nothing to refresh yet. Take the quiz or import your orders so Cartwise knows what you like."
                found == 0 -> "Checked ${progress.value.used} pages; nothing new cleared the bar. Your current picks stay."
                else -> null
            }
            progress.value = progress.value.copy(running = false, message = "Updated", finishedAt = System.currentTimeMillis(), note = note)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            progress.value = progress.value.copy(running = false, error = e.message ?: e.toString())
            false
        } finally {
            busy.set(false)
            if (progress.value.running) progress.value = progress.value.copy(running = false)
        }
    }

    /** Returns how many cards were chosen, or -1 when there's nothing to go on yet. */
    private suspend fun build(max: Int, quick: Boolean = false): Int {
        taste.rebuildIfStale()
        val profile = taste.profile()
        // A tap wants an answer now: short gaps (the pages are cheap fetches). The nightly run stays slow and polite.
        val budget = PageBudget(max, paced = !quick)
        val cands = ArrayList<Cand>()
        val now = System.currentTimeMillis()
        fun step(msg: String) {
            progress.value = progress.value.copy(used = budget.used, max = max, message = msg)
        }
        var published = 0
        // Show what's ready so far, so shelves appear while the rest loads.
        suspend fun publish() {
            if (cands.size == published) return
            published = cands.size
            val cards = select(profile, cands, now)
            if (cards.isNotEmpty()) dao.replaceFeed(cards)
        }
        var blocked = false
        fun stopOn(r: Load.Fail) {
            if (r.reason == Reason.CAPTCHA) blocked = true
        }

        // 1. Time to restock: from your order history, no page loads.
        cands += restock(now)

        // 2. Watchlist price checks.
        val watches = dao.watchByStaleness().take(if (quick) 2 else minOf(8, max / 4 + 1))
        for (w in watches) {
            if (blocked || !budget.take()) break
            step("Checking the price of " + Fmt.short(w.title, 30))
            when (val r = shop.product(w.asin, maxAgeMs = 0)) {
                is Load.Ok -> cands += checkWatch(w, r.v, now)
                is Load.Fail -> stopOn(r)
            }
        }

        publish()
        // 3. Your Amazon Lists: price drops on things you already want.
        if (!blocked && shop.isSignedIn() && budget.take()) {
            step("Checking your Lists")
            when (val r = shop.lists(null, maxAgeMs = 0)) {
                is Load.Ok -> {
                    val listed = dao.refsWithType(SignalType.LISTED).toSet()
                    for (l in r.v.items) {
                        val prev = dao.item(l.asin)?.price
                        if (prev != null && l.price != null && l.price < prev * 0.97) {
                            cands += Cand(l, Shelf.PRICE_DROP, "On your List: down from ${Fmt.money(prev)} to ${Fmt.money(l.price)}", 1.0)
                        }
                        signals.remember(l.toItem())
                        if (l.asin !in listed) signals.log(l.asin, SignalType.LISTED, l.title)
                    }
                }
                is Load.Fail -> stopOn(r)
            }
        }

        val clusters = profile.clusters.filter { !it.e.muted }

        publish()
        // 4. New in your interests. Each refresh looks somewhere new: a different page, sort and
        //    angle (the interest's query, then its top categories and brands), so you don't see the same page again.
        val round = settings.current().refreshCount
        settings.setRefreshCount(round + 1)
        for ((ci, c) in clusters.take(6).withIndex()) {
            if (blocked || !budget.take()) break
            val angles = listOf(c.e.query) + c.e.topCategories.split("|").filter { it.isNotBlank() } + c.e.topBrands.split("|").filter { it.isNotBlank() }.map { "$it ${c.e.query}" }
            val q = angles[(round + ci) % angles.size]
            val pageNo = (round / angles.size) % 3 + 1
            val sortName = listOf("searchNew", "search", "searchPopular")[round % 3]
            step("Looking for new things in " + c.name)
            val u = shop.url(sortName, "q" to ShopRepo.enc(q), "page" to pageNo.toString()).let { if (it.contains("{page}")) it.replace("{page}", pageNo.toString()) else if (!it.contains("page=")) "$it&page=$pageNo" else it }
            when (val r = shop.searchUrl(u)) {
                is Load.Ok -> r.v.items.filter { !it.sponsored }.take(16).forEach {
                    cands += Cand(it, Shelf.NEW_IN, "New in " + c.name, 0.3)
                }
                is Load.Fail -> stopOn(r)
            }
        }

        publish()
        // 5. Goes with what you own: related items on product pages of things you kept.
        for ((k, _) in profile.kept.take(5)) {
            if (blocked || !budget.take()) break
            step("Finding things that go with " + Fmt.short(k.title, 30))
            when (val r = shop.product(k.ref)) {
                is Load.Ok -> {
                    signals.remember(r.v.toItem())
                    r.v.related.take(10).forEach {
                        cands += Cand(it, Shelf.GOES_WITH, "Goes with your " + Fmt.short(k.title, 34), 0.4, r.v.category)
                    }
                }
                is Load.Fail -> stopOn(r)
            }
        }

        publish()
        // 6. Popular where you shop: best sellers in your top categories.
        val allRoots = clusters.flatMap { it.roots }.distinct()
        val roots = if (allRoots.size <= 3) allRoots else (0 until 3).map { allRoots[(round + it) % allRoots.size] }
        for (root in roots) {
            if (blocked || !budget.take()) break
            step("Checking best sellers in $root")
            val slug = Categories.bestsellerSlug(root)
            val r = if (slug != null) shop.tiles(shop.url("bestsellers", "slug" to slug))
            else when (val s = shop.searchUrl(shop.url("searchPopular", "q" to ShopRepo.enc(root)))) {
                is Load.Ok -> Load.Ok(s.v.items)
                is Load.Fail -> s
            }
            when (r) {
                is Load.Ok -> r.v.take(20).forEach {
                    cands += Cand(it, Shelf.POPULAR, "Popular in $root", 0.2, "", "Popular in $root")
                }
                is Load.Fail -> stopOn(r)
            }
        }

        publish()
        // 7. Wildcard: one category over from your interests.
        val salt = Calendar.getInstance().get(Calendar.DAY_OF_YEAR) + round * 7
        for ((i, c) in clusters.take(3).withIndex()) {
            if (blocked || !budget.take()) break
            val q = Categories.adjacentQuery(c.roots.firstOrNull(), c.e.query, salt + i)
            step("Trying something new: $q")
            when (val r = shop.searchUrl(shop.url("search", "q" to ShopRepo.enc(q), "page" to "1"))) {
                is Load.Ok -> r.v.items.filter { !it.sponsored }.take(12).forEach {
                    cands += Cand(it, Shelf.WILDCARD, "Wildcard — next to " + c.name, 0.2)
                }
                is Load.Fail -> stopOn(r)
            }
        }

        step("Scoring ${cands.size} candidates")
        val cards = select(profile, cands, now)
        if (cards.isNotEmpty()) dao.replaceFeed(cards)
        settings.setLastFeed(now)
        progress.value = progress.value.copy(used = budget.used, message = if (blocked) "Amazon asked for a captcha; stopped early" else "Updated")
        forgetProfile()
        if (profile.clusters.isEmpty() && profile.kept.isEmpty() && cands.isEmpty()) return -1
        return cards.size
    }

    /** Runs a refresh right now in the app (not queued), for the refresh button and pull-to-refresh. */
    fun refreshNow(pages: Int = 10) {
        if (busy.get()) return
        progress.value = Progress(running = true, max = pages, message = "Getting started")
        com.emotionalleaves.cartwise.Graph.scope.launch { refresh(pages, quick = true) }
    }

    private suspend fun restock(now: Long): List<Cand> {
        val out = ArrayList<Cand>()
        val byAsin = dao.orders().filter { !it.returned }.groupBy { it.asin }
        for ((asin, list) in byAsin) {
            val dates = list.map { it.date }.filter { it > 0 }.distinct().sorted()
            if (dates.isEmpty()) continue
            val last = dates.last()
            val days = ((now - last) / DAY).toInt()
            val o = list.maxByOrNull { it.date } ?: continue
            val item = dao.item(asin)
            val l = Listing(
                asin = asin, title = o.title, url = shop.url("product", "asin" to asin),
                price = item?.price ?: o.price, image = item?.image?.ifBlank { o.image } ?: o.image,
                rating = item?.rating, reviews = item?.reviews ?: 0, brand = item?.brand ?: "",
            )
            if (dates.size >= 2) {
                val interval = ((dates.last() - dates.first()) / (dates.size - 1) / DAY).toInt()
                if (interval >= 5 && days >= interval * 0.85) {
                    out += Cand(l, Shelf.RESTOCK, "You rebuy this about every $interval days", 1.0, item?.category ?: "")
                }
            } else if (Words.consumable(o.title) && days in 35..400) {
                out += Cand(l, Shelf.RESTOCK, "Last bought $days days ago", 0.7, item?.category ?: "")
            }
        }
        return out
    }

    private suspend fun checkWatch(w: WatchEntity, p: Product, now: Long): List<Cand> {
        signals.remember(p.toItem())
        val newPrice = p.price
        val dropped = newPrice != null && w.price != null && newPrice < w.price * 0.97
        val hit = newPrice != null && w.target != null && newPrice <= w.target && now - w.notifiedAt > DAY
        dao.upsertWatch(
            w.copy(
                title = p.title.ifBlank { w.title },
                image = p.images.firstOrNull() ?: w.image,
                price = newPrice ?: w.price,
                prevPrice = if (newPrice != null && newPrice != w.price) w.price else w.prevPrice,
                checkedAt = now,
                notifiedAt = if (hit) now else w.notifiedAt,
            ),
        )
        if (hit && newPrice != null) Notifier.priceHit(app, w.asin, p.title.ifBlank { w.title }, newPrice)
        return if (dropped) listOf(Cand(p.toListing(), Shelf.PRICE_DROP, "Down from ${Fmt.money(w.price)} to ${Fmt.money(newPrice)}", 1.0, p.category)) else emptyList()
    }

    private suspend fun select(p: TasteModel.Profile, cands: List<Cand>, now: Long): List<FeedCardEntity> {
        val seen = dao.seenAsins().toSet()
        val seenTwice = alreadySeen()
        val scored = ArrayList<Scored>()
        val doneAsins = HashSet<String>()
        // Restock and price drops first so they win duplicates.
        val ordered = cands.sortedBy { it.shelf.ordinal }
        for (c in ordered) {
            val l = c.l
            if (l.asin.isBlank() || l.title.isBlank() || !doneAsins.add(l.asin)) continue
            scoreOne(p, c, seen, seenTwice)?.let { scored += it }
        }

        // Variety rules: no near-duplicates, max 3 cards per brand, shelves of up to 10, 1 in 8 wildcard.
        val chosen = ArrayList<Scored>()
        val brandCount = HashMap<String, Int>()
        val images = HashSet<String>()
        fun ok(s: Scored): Boolean {
            val b = s.c.l.brand.ifBlank { s.c.l.title.substringBefore(' ') }.lowercase()
            if ((brandCount[b] ?: 0) >= 3) return false
            val img = Parse.imageId(s.c.l.image)
            if (img.isNotEmpty() && img in images) return false
            if (s.toks.size >= 3 && chosen.any { Words.jaccard(it.toks, s.toks) >= 0.7 }) return false
            brandCount[b] = (brandCount[b] ?: 0) + 1
            if (img.isNotEmpty()) images += img
            chosen += s
            return true
        }
        val byShelf = scored.groupBy { it.c.shelf }
        for (shelf in Shelf.entries) {
            if (shelf == Shelf.WILDCARD) continue
            val list = byShelf[shelf].orEmpty().sortedByDescending { it.score }
            if (shelf == Shelf.POPULAR) {
                for ((_, group) in list.groupBy { it.c.label }) {
                    var n = 0
                    for (s in group) { if (n >= 8) break; if (ok(s)) n++ }
                }
            } else {
                var n = 0
                for (s in list) { if (n >= 10) break; if (ok(s)) n++ }
            }
        }
        val wildAllowed = maxOf(1, chosen.size / 7)
        var w = 0
        for (s in byShelf[Shelf.WILDCARD].orEmpty().sortedByDescending { it.score }) {
            if (w >= wildAllowed) break
            if (ok(s)) w++
        }

        return chosen.mapIndexed { i, s ->
            val l = s.c.l
            FeedCardEntity(
                asin = l.asin,
                shelf = s.c.shelf.name,
                shelfLabel = s.c.label,
                position = s.c.shelf.ordinal * 1000 + i,
                title = l.title,
                image = l.image,
                price = l.price,
                rating = l.rating,
                reviews = l.reviews,
                brand = l.brand,
                reason = s.reason,
                score = s.score,
                fit = s.fit,
                wildcard = s.c.shelf == Shelf.WILDCARD,
                createdAt = now,
            )
        }
    }

    /** Scores one candidate with the feed formula; null when it doesn't clear the bar. */
    private suspend fun scoreOne(p: TasteModel.Profile, c: Cand, seen: Set<String>, seenTwice: Set<String> = emptySet()): Scored? {
        val l = c.l
        if (l.asin in p.blockedItems) return null
        val brand = l.brand.ifBlank { l.title.substringBefore(' ') }
        if (brand.lowercase() in p.blockedBrands) return null
        if (l.asin in p.owned && c.shelf != Shelf.RESTOCK) return null
        // Discovery never repeats something you've already been shown and didn't open (for two weeks).
        if (c.shelf != Shelf.RESTOCK && c.shelf != Shelf.PRICE_DROP && l.asin in seenTwice) return null

        signals.remember(l.toItem(c.category))
        val item = dao.item(l.asin) ?: ItemEntity(ref = l.asin, title = l.title)
        val v = taste.vectorFor(item)
        val fit = taste.fit(p, v, item.brand.ifBlank { brand }, item.category)
        if (fit.score < 0) return null
        val f = fit.score
        when (c.shelf) {
            Shelf.RESTOCK, Shelf.PRICE_DROP -> Unit
            Shelf.WILDCARD -> if (f < 0.15 || f > 0.55) return null
            else -> if (f < TasteModel.FIT_CUTOFF) return null
        }

        val bayes = Parse.bayes(l.rating ?: item.rating, if (l.reviews > 0) l.reviews else item.reviews, 4.3)
        val qn = if ((l.rating ?: item.rating) == null) 0.4 else ((bayes - 3.5) / 1.5).coerceIn(0.0, 1.0)
        val quality = 0.7 * qn + 0.3 * maxOf(l.seller, item.seller)
        val seasonal = p.seasonal.any { Vec.cos(v, it) > 0.6 }
        val timing = if (seasonal) maxOf(c.timing, 0.6) else c.timing
        val novelty = if (l.asin in seen) 0.4 else 1.0
        val price = l.price ?: item.price
        val median = fit.cluster?.e?.medianPrice
        val value = if (price == null || median == null || median <= 0) 0.4 else (1.0 - price / (2 * median)).coerceIn(0.0, 1.0)
        val score = 0.45 * f + 0.20 * quality + 0.15 * timing + 0.10 * value + 0.10 * novelty

        var reason = c.reason
        if (c.shelf == Shelf.NEW_IN || c.shelf == Shelf.POPULAR) {
            val near = taste.nearestKept(p, v)
            if (near != null && near.second > 0.6) reason = "Because you kept: " + Fmt.short(near.first.title, 36)
        }
        if (seasonal && c.shelf != Shelf.RESTOCK && c.shelf != Shelf.PRICE_DROP) reason += " · you shop this time of year"
        return Scored(c, score, f, reason, fit.cluster?.e?.id, Words.cloneTokens(l.title, brand))
    }

    // ---- "More for you": the endless part of the feed, one page per scroll ----

    data class More(val cards: List<FeedCardEntity>, val wildcard: Boolean, val error: String? = null, val noInterests: Boolean = false)

    @Volatile private var cachedProfile: Pair<Long, TasteModel.Profile>? = null

    private suspend fun freshProfile(): TasteModel.Profile {
        val now = System.currentTimeMillis()
        cachedProfile?.let { if (now - it.first < 10 * 60_000) return it.second }
        return taste.profile().also { cachedProfile = now to it }
    }

    fun forgetProfile() {
        cachedProfile = null
    }

    /**
     * Step n of the endless stream: rotates through your interests and, for each, through
     * relevance, newest and most-reviewed pages; every 4th step is a wildcard one category over.
     * Loads exactly one page.
     */
    suspend fun more(step: Int, exclude: Set<String>): More {
        val p = freshProfile()
        val seenTwice = alreadySeen()
        val clusters = p.clusters.filter { !it.e.muted }
        if (clusters.isEmpty()) return More(emptyList(), false, noInterests = true)
        val c = clusters[step % clusters.size]
        val round = step / clusters.size
        val wildcard = step % 4 == 3
        val enc = ShopRepo.enc(c.e.query)
        val (url, shelf, reason) = if (wildcard) {
            val q = Categories.adjacentQuery(c.roots.firstOrNull(), c.e.query, step)
            Triple(shop.url("search", "q" to ShopRepo.enc(q), "page" to (round / 4 + 1).toString()), Shelf.WILDCARD, "Wildcard — next to " + c.name)
        } else {
            val page = round / 3 + 1
            when (round % 3) {
                0 -> Triple(shop.url("search", "q" to enc, "page" to page.toString()), Shelf.NEW_IN, "Matches your interest: " + c.name)
                1 -> Triple(shop.url("searchNew", "q" to enc) + "&page=$page", Shelf.NEW_IN, "New in " + c.name)
                else -> Triple(shop.url("searchPopular", "q" to enc) + "&page=$page", Shelf.NEW_IN, "Well reviewed in " + c.name)
            }
        }
        val items = when (val r = shop.searchUrl(url)) {
            is Load.Ok -> r.v.items
            is Load.Fail -> return More(emptyList(), wildcard, error = r.message)
        }
        val seen = dao.seenAsins().toSet()
        val now = System.currentTimeMillis()
        val scored = items.asSequence()
            .filter { !it.sponsored && it.asin.isNotBlank() && it.title.isNotBlank() && it.asin !in exclude }
            .distinctBy { it.asin }
            .toList()
            .mapNotNull { scoreOne(p, Cand(it, shelf, reason, 0.2), seen, seenTwice) }
            .sortedByDescending { it.score }
        val cards = scored.mapIndexed { i, s ->
            val l = s.c.l
            FeedCardEntity(
                asin = l.asin, shelf = "MORE", shelfLabel = "More for you", position = 100_000 + step * 100 + i,
                title = l.title, image = l.image, price = l.price, rating = l.rating, reviews = l.reviews, brand = l.brand,
                reason = s.reason, score = s.score, fit = s.fit, wildcard = wildcard, createdAt = now,
            )
        }
        return More(cards, wildcard)
    }

    /** A listing that cleared your taste bar, with its feed score and the reason to show. */
    data class Picked(val listing: Listing, val score: Double, val fit: Double, val reason: String)

    /** Interests to browse other sections by (e.g. the Outlet's "For you"), strongest first. */
    suspend fun interests(): List<TasteModel.ClusterVec> = freshProfile().clusters.filter { !it.e.muted }

    /** Scores listings from any page against your taste model, keeping only what clears the Fit bar. */
    suspend fun pick(items: List<Listing>, reason: String): List<Picked> {
        val p = freshProfile()
        val seen = dao.seenAsins().toSet()
        val seenTwice = alreadySeen()
        return items.asSequence()
            .filter { !it.sponsored && it.asin.isNotBlank() && it.title.isNotBlank() }
            .distinctBy { it.asin }
            .toList()
            .mapNotNull { l -> scoreOne(p, Cand(l, Shelf.NEW_IN, reason, 0.2), seen, seenTwice)?.let { Picked(l, it.score, it.fit, it.reason) } }
            .sortedByDescending { it.score }
    }

    // ---- Feedback from the feed cards ----

    /** Counts a sighting once per 20-minute session (the lastDay column now holds the last-seen time). */
    suspend fun impression(asin: String) {
        val now = System.currentTimeMillis()
        val cur = dao.impression(asin)
        if (cur != null && now - cur.lastDay < 20 * 60_000L) return
        val n = (cur?.count ?: 0) + 1
        dao.upsertImpression(ImpressionEntity(asin, n, now, cur?.interacted ?: false, cur?.firstSeen ?: now))
        if (n == 3 && cur?.interacted != true) signals.log(asin, SignalType.SKIP)
    }

    /** Things you've already been shown in the last two weeks and didn't open: kept out of discovery. */
    private suspend fun alreadySeen(): Set<String> = dao.seenSince(System.currentTimeMillis() - 14 * DAY).toSet()

    private suspend fun interacted(asin: String) {
        val cur = dao.impression(asin)
        dao.upsertImpression(
            ImpressionEntity(asin, cur?.count ?: 1, cur?.lastDay ?: System.currentTimeMillis(), true, cur?.firstSeen ?: System.currentTimeMillis()),
        )
    }

    suspend fun opened(asin: String) = interacted(asin)

    suspend fun thumb(card: FeedCardEntity, up: Boolean) {
        interacted(card.asin)
        signals.log(card.asin, if (up) SignalType.THUMB_UP else SignalType.THUMB_DOWN, card.title)
        if (!up) dao.removeFeedCard(card.asin)
    }

    /** kind: "item", "brand" or "topic" (things like this). */
    suspend fun neverShow(card: FeedCardEntity, kind: String) {
        interacted(card.asin)
        when (kind) {
            "brand" -> {
                val b = card.brand.ifBlank { card.title.substringBefore(' ') }
                dao.insertBlock(BlockEntity(kind = "brand", value = b.lowercase(), label = b))
                dao.removeFeedBrand(b)
            }
            "topic" -> {
                val item = dao.item(card.asin) ?: ItemEntity(ref = card.asin, title = card.title)
                val v = taste.vectorFor(item)
                dao.insertBlock(BlockEntity(kind = "topic", value = card.asin, label = "Things like: " + Fmt.short(card.title, 40), vector = Vec.toBytes(v)))
            }
            else -> dao.insertBlock(BlockEntity(kind = "item", value = card.asin, label = card.title))
        }
        dao.removeFeedCard(card.asin)
    }
}
