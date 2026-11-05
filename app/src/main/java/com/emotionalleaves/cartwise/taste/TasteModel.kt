package com.emotionalleaves.cartwise.taste

import com.emotionalleaves.cartwise.data.CartwiseDb
import com.emotionalleaves.cartwise.data.ClusterEntity
import com.emotionalleaves.cartwise.data.ItemEntity
import com.emotionalleaves.cartwise.data.OrderEntity
import com.emotionalleaves.cartwise.data.Settings
import com.emotionalleaves.cartwise.data.SignalEntity
import com.emotionalleaves.cartwise.data.displayName
import com.emotionalleaves.cartwise.engine.Extract
import com.emotionalleaves.cartwise.rank.Words
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Your taste, on the phone: up to 8 interest clusters rebuilt nightly from your signals.
 * Nothing here ever leaves the device.
 */
class TasteModel(
    private val db: CartwiseDb,
    private val embedder: Embedder,
    private val settings: Settings,
    private val signals: SignalLog,
) {
    companion object {
        private const val DAY = 86_400_000.0
        private const val HOUR = 3_600_000L
        const val FIT_CUTOFF = 0.35
    }

    class ClusterVec(
        val e: ClusterEntity,
        val v: FloatArray,
        val brands: Set<String>,
        val cats: Set<String>,
        val roots: List<String>,
    ) {
        val name: String get() = e.displayName()
    }

    class Profile(
        val clusters: List<ClusterVec>,
        val negatives: List<FloatArray>,
        val topics: List<FloatArray>,
        val blockedBrands: Set<String>,
        val blockedItems: Set<String>,
        val owned: Set<String>,
        val keptBrands: Set<String>,
        val returnedBrands: Set<String>,
        val kept: List<Pair<ItemEntity, FloatArray>>,
        val seasonal: List<FloatArray>,
    )

    data class Fit(val score: Double, val cluster: ClusterVec?)

    private class Pt(val item: ItemEntity, val v: FloatArray, val w: Double)

    private val dao get() = db.dao()
    private val mutex = Mutex()
    private val expectedDim: Int by lazy { embedder.embed("dimension probe").size }

    fun textFor(i: ItemEntity): String {
        val title = if (i.ref.startsWith("seed:")) i.title.replace(":", " ") else i.title
        return if (i.category.isBlank()) title else title + ". " + i.category.replace(" › ", ", ")
    }

    suspend fun vectorFor(item: ItemEntity): FloatArray = withContext(Dispatchers.Default) {
        val dim = expectedDim
        val stored = item.vector?.let { Vec.fromBytes(it) }
        if (stored != null && stored.size == dim) return@withContext stored
        val v = embedder.embed(textFor(item))
        dao.setVector(item.ref, Vec.toBytes(v))
        v
    }

    private fun decayed(s: SignalEntity, now: Long): Double {
        val age = max(0L, now - s.ts) / DAY
        return s.weight * 0.5.pow(age / SignalType.halfLifeDays(s.type))
    }

    private suspend fun aggregate(now: Long): Map<String, Double> {
        val agg = HashMap<String, Double>()
        for (s in dao.allSignals()) {
            if (s.weight == 0.0) continue
            agg[s.ref] = (agg[s.ref] ?: 0.0) + decayed(s, now)
        }
        return agg
    }

    private suspend fun itemsFor(refs: Collection<String>): List<ItemEntity> =
        refs.toList().chunked(400).flatMap { dao.items(it) }

    suspend fun rebuildIfStale() {
        val p = settings.current()
        if (System.currentTimeMillis() - p.lastCluster > 20 * HOUR || dao.clusters().isEmpty()) rebuild()
    }

    suspend fun rebuild() {
        mutex.withLock { withContext(Dispatchers.Default) { rebuildInner() } }
    }

    private suspend fun rebuildInner() {
        val now = System.currentTimeMillis()
        val agg = aggregate(now)
        val blocks = dao.blocks()
        val topics = blocks.filter { it.kind == "topic" && it.vector != null }.map { Vec.fromBytes(it.vector!!) }
        val bBrands = blocks.filter { it.kind == "brand" }.map { it.value.lowercase() }.toSet()
        val bItems = blocks.filter { it.kind == "item" }.map { it.value }.toSet()

        val posKeys = agg.filter { it.value > 0.05 && it.key !in bItems }.keys
        val pts = ArrayList<Pt>()
        for (item in itemsFor(posKeys)) {
            if (item.title.isBlank()) continue
            if (item.brand.isNotBlank() && item.brand.lowercase() in bBrands) continue
            val v = vectorFor(item)
            if (topics.any { t -> Vec.cos(t, v) > 0.75 }) continue
            pts += Pt(item, v, agg[item.ref] ?: 0.0)
        }
        val old = dao.clusters()
        if (pts.isEmpty()) {
            dao.replaceClusters(emptyList())
            settings.setLastCluster(now)
            return
        }
        val k = min(8, max(1, ceil(sqrt(pts.size / 1.5)).toInt()))
        val groups = kmeans(pts, k)

        val fresh = ArrayList<ClusterEntity>()
        val used = HashSet<String>()
        for (g in groups) {
            val dim = g[0].v.size
            val acc = DoubleArray(dim)
            var wsum = 0.0
            for (p in g) {
                for (i in 0 until dim) acc[i] += p.w * p.v[i]
                wsum += p.w
            }
            val cen = Vec.normalize(FloatArray(dim) { acc[it].toFloat() })
            val brands = g.filter { it.item.brand.isNotBlank() && !it.item.ref.contains(':') }
                .groupBy { it.item.brand }
                .mapValues { e -> e.value.sumOf { it.w } }
                .entries.sortedByDescending { it.value }.take(5).map { it.key }
            val leafW = g.filter { it.item.category.isNotBlank() }
                .groupBy { it.item.category.substringAfterLast(" › ").trim() }
                .mapValues { e -> e.value.sumOf { it.w } }
            val rootW = g.filter { it.item.category.isNotBlank() }
                .groupBy { it.item.category.substringBefore(" › ").trim() }
                .mapValues { e -> e.value.sumOf { it.w } }
            val cats = leafW.entries.sortedByDescending { it.value }.take(5).map { it.key }
            val roots = rootW.entries.sortedByDescending { it.value }.take(3).map { it.key }
            val (name, query) = nameFor(g, leafW, wsum)
            val prices = g.mapNotNull { it.item.price }.sorted()
            val median = if (prices.isEmpty()) null else prices[prices.size / 2]
            var match: ClusterEntity? = null
            var bestCos = 0.8
            for (o in old) {
                if (o.id in used) continue
                val c = Vec.cos(Vec.fromBytes(o.centroid), cen)
                if (c > bestCos) { bestCos = c; match = o }
            }
            val id = match?.id ?: UUID.randomUUID().toString()
            used += id
            val examples = g.sortedByDescending { it.w }.take(4).joinToString("|") { p ->
                when {
                    p.item.ref.startsWith("seed:") -> "Start quiz: " + p.item.title.substringBefore(':')
                    p.item.ref.startsWith("q:") -> "Searched: " + p.item.title
                    else -> p.item.title.take(70)
                }
            }
            fresh += ClusterEntity(
                id = id, autoName = name, customName = match?.customName, muted = match?.muted ?: false,
                centroid = Vec.toBytes(cen), weight = wsum, itemCount = g.size,
                topBrands = brands.joinToString("|"), topCategories = cats.joinToString("|"), topRoots = roots.joinToString("|"),
                query = query, medianPrice = median, examples = examples, updatedAt = now,
            )
        }
        dao.replaceClusters(fresh.sortedByDescending { it.weight })
        settings.setLastCluster(now)
    }

    private fun kmeans(pts: List<Pt>, k: Int): List<List<Pt>> {
        val rnd = Random(42)
        val cents = ArrayList<FloatArray>()
        cents += pts.maxByOrNull { it.w }!!.v.copyOf()
        while (cents.size < k) {
            val d = DoubleArray(pts.size) { i ->
                val best = cents.maxOf { c -> Vec.cos(pts[i].v, c) }
                pts[i].w * (1.0 - best).coerceAtLeast(0.0).pow(2)
            }
            val total = d.sum()
            if (total < 1e-9) break
            var r = rnd.nextDouble() * total
            var idx = 0
            while (idx < d.size - 1 && r > d[idx]) { r -= d[idx]; idx++ }
            cents += pts[idx].v.copyOf()
        }
        var groups: List<List<Pt>> = listOf(pts)
        repeat(12) {
            val g = List(cents.size) { ArrayList<Pt>() }
            for (p in pts) {
                var bi = 0
                var bs = -2.0
                for (c in cents.indices) {
                    val s = Vec.cos(p.v, cents[c])
                    if (s > bs) { bs = s; bi = c }
                }
                g[bi].add(p)
            }
            for (c in cents.indices) {
                if (g[c].isEmpty()) continue
                val dim = cents[c].size
                val acc = DoubleArray(dim)
                for (p in g[c]) for (i in 0 until dim) acc[i] += p.w * p.v[i]
                cents[c] = Vec.normalize(FloatArray(dim) { j -> acc[j].toFloat() })
            }
            groups = g
        }
        return groups.filter { it.isNotEmpty() }
    }

    private fun nameFor(g: List<Pt>, leafW: Map<String, Double>, wsum: Double): Pair<String, String> {
        val topLeaf = leafW.maxByOrNull { it.value }
        if (topLeaf != null && topLeaf.key.isNotBlank() && topLeaf.value >= 0.4 * wsum) {
            val chip = Quiz.CHIPS.find { it.label.equals(topLeaf.key, ignoreCase = true) }
            return topLeaf.key to (chip?.query ?: topLeaf.key.lowercase().replace("&", " ").replace(Regex("\\s+"), " "))
        }
        val counts = HashMap<String, Double>()
        for (p in g) {
            val title = if (p.item.ref.startsWith("seed:")) p.item.title.substringBefore(':') else p.item.title
            for (t in Words.contentTokens(title).map { Words.stem(it) }.toSet()) {
                if (t.length >= 3 && t.none { c -> c.isDigit() } && t !in Words.UNITS) counts[t] = (counts[t] ?: 0.0) + p.w
            }
        }
        val top = counts.entries.sortedByDescending { it.value }.take(2).map { it.key }
        if (top.isEmpty()) return "Things you like" to g.first().item.title.take(40)
        val name = top.joinToString(" & ") { w -> w.replaceFirstChar { c -> c.titlecase() } }
        return name to top.joinToString(" ")
    }

    suspend fun profile(): Profile = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val agg = aggregate(now)
        val clusters = dao.clusters().map { c ->
            ClusterVec(
                c, Vec.fromBytes(c.centroid),
                c.topBrands.split("|").filter { it.isNotBlank() }.map { it.lowercase() }.toSet(),
                c.topCategories.split("|").filter { it.isNotBlank() }.map { it.lowercase() }.toSet(),
                c.topRoots.split("|").filter { it.isNotBlank() },
            )
        }
        val negatives = itemsFor(agg.filter { it.value < -0.5 }.keys).filter { it.title.isNotBlank() }.map { vectorFor(it) }
        val blocks = dao.blocks()
        val topics = blocks.filter { it.kind == "topic" && it.vector != null }.map { Vec.fromBytes(it.vector!!) }
        val bBrands = blocks.filter { it.kind == "brand" || it.kind == "seller" }.map { it.value.lowercase() }.toSet()
        val bItems = blocks.filter { it.kind == "item" || it.kind == "hidden" }.map { it.value }.toSet()

        val orders = dao.orders()
        val itemMap = itemsFor(orders.map { it.asin }.distinct()).associateBy { it.ref }
        fun brandOf(o: OrderEntity): String =
            (itemMap[o.asin]?.brand?.takeIf { it.isNotBlank() } ?: Extract.guessBrand(o.title)).lowercase()
        val keptOrders = orders.filter { !it.returned }
        val keptItems = keptOrders.distinctBy { it.asin }.take(60).map { o ->
            itemMap[o.asin] ?: ItemEntity(ref = o.asin, title = o.title, image = o.image, price = o.price).also { signals.remember(it) }
        }
        val kept = keptItems.map { it to vectorFor(it) }
        val keptVec = kept.associate { it.first.ref to it.second }

        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.DAY_OF_YEAR)
        val year = cal.get(Calendar.YEAR)
        val seasonal = keptOrders.filter { o ->
            if (o.date <= 0) return@filter false
            val c = Calendar.getInstance().apply { timeInMillis = o.date }
            c.get(Calendar.YEAR) < year && abs(c.get(Calendar.DAY_OF_YEAR) - today) <= 20
        }.mapNotNull { keptVec[it.asin] }

        Profile(
            clusters = clusters,
            negatives = negatives,
            topics = topics,
            blockedBrands = bBrands,
            blockedItems = bItems,
            owned = keptOrders.map { it.asin }.toSet(),
            keptBrands = keptOrders.map { brandOf(it) }.toSet(),
            returnedBrands = orders.filter { it.returned }.map { brandOf(it) }.toSet(),
            kept = kept,
            seasonal = seasonal,
        )
    }

    private fun mapCos(c: Double): Double =
        if (embedder.usingModel) ((c - 0.15) / 0.6).coerceIn(0.0, 1.0) else ((c - 0.05) / 0.45).coerceIn(0.0, 1.0)

    /** How well an item matches your closest interest cluster, 0–1. Negative means "never show" topic. */
    fun fit(p: Profile, v: FloatArray, brand: String, category: String): Fit {
        if (p.topics.any { Vec.cos(v, it) > 0.75 }) return Fit(-1.0, null)
        val leaf = category.substringAfterLast(" › ").trim().lowercase()
        val b = brand.trim().lowercase()
        var best = 0.0
        var bc: ClusterVec? = null
        for (c in p.clusters) {
            if (c.e.muted) continue
            val s = 0.8 * mapCos(Vec.cos(v, c.v)) +
                0.1 * (if (b.isNotEmpty() && b in c.brands) 1.0 else 0.0) +
                0.1 * (if (leaf.isNotEmpty() && leaf in c.cats) 1.0 else 0.0)
            if (s > best) { best = s; bc = c }
        }
        val neg = p.negatives.maxOfOrNull { Vec.cos(v, it) } ?: 0.0
        if (neg > 0.8) best -= 0.25
        return Fit(best.coerceIn(0.0, 1.0), bc)
    }

    fun nearestKept(p: Profile, v: FloatArray): Pair<ItemEntity, Double>? =
        p.kept.map { it.first to Vec.cos(v, it.second) }.maxByOrNull { it.second }

    suspend fun applyQuiz(chips: List<Quiz.Chip>) {
        dao.deleteSignalTypes(listOf(SignalType.QUIZ))
        val now = System.currentTimeMillis()
        for (c in chips) {
            val ref = "seed:" + c.slug
            signals.remember(ItemEntity(ref = ref, title = c.label + ": " + c.query, category = c.root + " › " + c.label))
            dao.insertSignal(SignalEntity(ref = ref, type = SignalType.QUIZ, weight = SignalType.weight(SignalType.QUIZ), ts = now, note = c.label))
        }
        settings.setQuizDone(true)
        rebuild()
    }

    suspend fun quizPicks(): Set<String> = dao.refsWithType(SignalType.QUIZ).map { it.removePrefix("seed:") }.toSet()

    suspend fun wipe() {
        dao.clearSignals(); dao.clearClusters(); dao.clearOrders(); dao.clearFeed(); dao.clearImpressions(); dao.clearItems()
        settings.setQuizDone(false); settings.setLastCluster(0); settings.setLastFeed(0); settings.setOrdersImported(0)
    }
}
