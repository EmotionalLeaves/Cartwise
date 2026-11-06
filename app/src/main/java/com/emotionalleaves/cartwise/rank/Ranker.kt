package com.emotionalleaves.cartwise.rank

import com.emotionalleaves.cartwise.engine.Listing
import com.emotionalleaves.cartwise.engine.Parse
import java.util.Locale

/** Weight presets: quality, value, delivery, seller, match, discount. */
enum class Preset(val label: String, val w: DoubleArray, val cheapest: Boolean = false) {
    BEST("Best overall", doubleArrayOf(0.28, 0.18, 0.15, 0.10, 0.24, 0.05)),
    DISCOUNT("Biggest discount", doubleArrayOf(0.20, 0.10, 0.05, 0.05, 0.15, 0.45)),
    VALUE("Best value", doubleArrayOf(0.20, 0.40, 0.10, 0.05, 0.20, 0.05)),
    FASTEST("Fastest", doubleArrayOf(0.20, 0.10, 0.45, 0.05, 0.20, 0.00)),
    TRUSTED("Most trusted", doubleArrayOf(0.45, 0.05, 0.05, 0.30, 0.15, 0.00)),
    CHEAPEST("Cheapest", doubleArrayOf(0.10, 0.60, 0.05, 0.05, 0.20, 0.00), cheapest = true);

    companion object {
        fun from(name: String?): Preset = entries.firstOrNull { it.name == name } ?: BEST
        val SIGNALS = listOf("Quality", "Value", "Delivery", "Seller", "Match", "Discount")
        const val N = 6
    }
}

data class Ranked(
    val item: Listing,
    val score: Double,
    val parts: DoubleArray,
    val weights: DoubleArray,
    val bayes: Double,
    val penalty: Double,
    val notes: List<String>,
    val similar: List<Listing> = emptyList(),
)

/**
 * score = wQ·Q + wV·V + wD·D + wS·S + wM·M − penalties, computed on the phone per page.
 * Sponsored results, blocked brands and clone listings are handled before scoring.
 */
object Ranker {
    data class Ctx(
        val query: String,
        val preset: Preset,
        val custom: DoubleArray? = null,
        val hideSponsored: Boolean = true,
        val hidden: Set<String> = emptySet(),
        val blockedBrands: Set<String> = emptySet(),
        val keptBrands: Set<String> = emptySet(),
        val returnedBrands: Set<String> = emptySet(),
        val personalize: Boolean = false,
        /** Only items with a real discount off the list price. */
        val saleOnly: Boolean = false,
        val filters: Filters = Filters(),
    )

    fun brandKey(l: Listing): String = l.brand.ifBlank { l.title.substringBefore(' ') }.lowercase(Locale.US).trim()

    private fun pct(i: Int, n: Int): Double = if (n <= 1) 1.0 else 1.0 - i.toDouble() / (n - 1)

    fun rank(items: List<Listing>, ctx: Ctx): List<Ranked> {
        val w0 = ctx.custom ?: ctx.preset.w
        val ws = w0.sum().takeIf { it > 0 } ?: 1.0
        val w = DoubleArray(Preset.N) { if (it < w0.size) w0[it] / ws else 0.0 }

        val pool = items.distinctBy { it.asin }.filter {
            it.title.isNotBlank() && it.asin !in ctx.hidden && brandKey(it) !in ctx.blockedBrands &&
                !(ctx.hideSponsored && it.sponsored) && (!ctx.saleOnly || it.discountPct >= 5) && ctx.filters.keep(it)
        }
        if (pool.isEmpty()) return emptyList()

        val rated = pool.mapNotNull { it.rating }
        val prior = if (rated.isEmpty()) 4.2 else rated.average()
        val bayes = pool.associate { it.asin to Parse.bayes(it.rating, it.reviews, prior) }
        val bmin = bayes.values.minOrNull() ?: 0.0
        val bmax = bayes.values.maxOrNull() ?: 0.0

        val dominantUnit = pool.mapNotNull { it.unitPrice?.unit }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val unitRank = HashMap<String, Int>()
        val unitGroup = if (!ctx.preset.cheapest && dominantUnit != null) {
            pool.filter { it.unitPrice?.unit == dominantUnit }.sortedBy { it.unitPrice!!.value }
        } else emptyList()
        unitGroup.forEachIndexed { i, l -> unitRank[l.asin] = i }
        val priceGroup = pool.filter { it.price != null }.sortedBy { it.price!! }
        val priceRank = HashMap<String, Int>()
        priceGroup.forEachIndexed { i, l -> priceRank[l.asin] = i }

        val qTokens = Words.contentTokens(ctx.query).map { Words.stem(it) }.toSet()

        val scored = pool.map { l ->
            val b = bayes[l.asin] ?: prior
            val q = if (bmax - bmin < 1e-6) 0.5 else (b - bmin) / (bmax - bmin)
            val ur = unitRank[l.asin]
            val pr = priceRank[l.asin]
            val v = when {
                ur != null -> pct(ur, unitGroup.size)
                pr != null -> pct(pr, priceGroup.size)
                else -> 0.3
            }
            val d = Parse.deliveryScore(l.deliveryDays)
            val s = l.seller
            val titleToks = Words.tokens(l.title).map { Words.stem(it) }.toSet()
            val m = if (qTokens.isEmpty()) 1.0 else qTokens.count { it in titleToks }.toDouble() / qTokens.size
            val notes = ArrayList<String>()
            var pen = 0.0
            if (l.price == null) { pen += 0.10; notes += "No price shown on the page (−0.10)" }
            if (ctx.personalize) {
                val bk = brandKey(l)
                if (bk in ctx.returnedBrands) { pen += 0.08; notes += "You returned something from this brand (−0.08)" }
                else if (bk in ctx.keptBrands) { pen -= 0.05; notes += "You've bought and kept this brand (+0.05)" }
            }
            if (l.sponsored) notes += "Sponsored placement (tagged, not boosted)"
            if (ur != null) notes += "Value uses unit price per ${l.unitPrice?.unit}"
            // 50% off or more counts as a full discount score.
            val disc = (l.discountPct / 50.0).coerceIn(0.0, 1.0)
            if (l.discountPct > 0) notes += "${l.discountPct}% off the list price"
            val parts = doubleArrayOf(q, v, d, s, m, disc)
            var score = 0.0
            for (i in 0 until Preset.N) score += w[i] * parts[i]
            Ranked(l, score - pen, parts, w, b, pen, notes)
        }.sortedByDescending { it.score }

        // Clone grouping: keep the best-scoring listing, fold the rest under "+N similar".
        val leaders = ArrayList<Ranked>()
        val sims = ArrayList<ArrayList<Listing>>()
        val leaderToks = ArrayList<Set<String>>()
        for (r in scored) {
            val toks = Words.cloneTokens(r.item.title, r.item.brand)
            val img = Parse.imageId(r.item.image)
            var idx = -1
            for (i in leaders.indices) {
                val L = leaders[i].item
                val sameImg = img.isNotEmpty() && img == Parse.imageId(L.image)
                val closeTitle = toks.size >= 3 && Words.jaccard(toks, leaderToks[i]) >= 0.8 && priceClose(L.price, r.item.price)
                if (sameImg || closeTitle) { idx = i; break }
            }
            if (idx >= 0) sims[idx].add(r.item)
            else { leaders.add(r); sims.add(ArrayList()); leaderToks.add(toks) }
        }
        return leaders.mapIndexed { i, r -> r.copy(similar = sims[i]) }
    }

    private fun priceClose(a: Double?, b: Double?): Boolean {
        if (a == null || b == null) return a == null && b == null
        val lo = minOf(a, b); val hi = maxOf(a, b)
        return hi <= 0 || lo / hi >= 0.7
    }
}
