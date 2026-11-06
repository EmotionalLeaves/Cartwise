package com.emotionalleaves.cartwise.engine

import kotlinx.coroutines.delay
import java.net.URLEncoder

/** Everything the app asks of Amazon, in app terms. */
class ShopRepo(private val engine: WebEngine, private val selectors: SelectorStore) {

    companion object {
        fun enc(s: String): String = URLEncoder.encode(s.trim(), "UTF-8")

        private class Entry(val at: Long, val value: Any)

        /** Pages read in the last few minutes, shared by both engines: going back or reopening is instant. */
        private val cache = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > 80
        }

        fun clearCache() = synchronized(cache) { cache.clear() }
        fun forget(prefix: String) = synchronized(cache) { cache.keys.removeAll { it.startsWith(prefix) } }

        private const val MIN = 60_000L
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cached(key: String, ttlMs: Long, load: suspend () -> Load<T>): Load<T> {
        val now = System.currentTimeMillis()
        synchronized(cache) { cache[key] }?.let { if (now - it.at < ttlMs) return Load.Ok(it.value as T) }
        val r = load()
        if (r is Load.Ok) synchronized(cache) { cache[key] = Entry(now, r.v) }
        return r
    }

    fun url(name: String, vararg values: Pair<String, String>) = selectors.url(name, *values)

    fun isSignedIn(): Boolean = engine.isSignedIn()

    private fun fail(r: WebEngine.Res.Fail): Load.Fail = Load.Fail(r.reason, r.message, r.url)

    suspend fun search(query: String, page: Int, extra: String = ""): Load<SearchPage> =
        searchUrl(url("search", "q" to enc(query), "page" to page.toString()) + extra)

    suspend fun searchUrl(u: String): Load<SearchPage> = cached("search:$u", 5 * MIN) {
        when (val r = engine.run(u, "search")) {
            is WebEngine.Res.Ok -> Load.Ok(Extract.searchPage(r.json, r.url))
            is WebEngine.Res.Fail -> fail(r)
        }
    }

    suspend fun product(asin: String, maxAgeMs: Long = 5 * MIN): Load<Product> = cached("product:$asin", maxAgeMs) { productFresh(asin) }

    private suspend fun productFresh(asin: String): Load<Product> {
        val u = url("product", "asin" to asin)
        return when (val r = engine.run(u, "product")) {
            is WebEngine.Res.Ok -> {
                val p = Extract.product(r.json, r.url)
                if (p.title.isBlank()) Load.Fail(Reason.EMPTY, "Couldn't read this product page", u)
                else Load.Ok(if (p.asin.isBlank()) p.copy(asin = asin) else p)
            }
            is WebEngine.Res.Fail -> fail(r)
        }
    }

    suspend fun tiles(u: String): Load<List<Listing>> = cached("tiles:$u", 30 * MIN) {
        when (val r = engine.run(u, "tiles")) {
            is WebEngine.Res.Ok -> Load.Ok(Extract.tiles(r.json, r.url))
            is WebEngine.Res.Fail -> fail(r)
        }
    }

    suspend fun lists(listUrl: String?, maxAgeMs: Long = 2 * MIN): Load<ListsPage> {
        val u = listUrl ?: url("lists")
        return cached("lists:$u", maxAgeMs) {
            when (val r = engine.run(u, "lists")) {
                is WebEngine.Res.Ok -> Load.Ok(Extract.lists(r.json, r.url))
                is WebEngine.Res.Fail -> fail(r)
            }
        }
    }

    suspend fun orders(u: String): Load<OrdersPage> = when (val r = engine.run(u, "orders")) {
        is WebEngine.Res.Ok -> Load.Ok(Extract.orders(r.json, r.url))
        is WebEngine.Res.Fail -> fail(r)
    }

    /** Reads the cart page: item count and the ASINs it holds. */
    suspend fun cart(): Load<Pair<Int?, List<String>>> = when (val r = engine.run(url("cart"), "cartcount")) {
        is WebEngine.Res.Ok -> {
            val arr = r.json.optJSONArray("asins")
            val asins = if (arr == null) emptyList() else (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
            val count = Parse.int(r.json.optString("count"))
            AmazonSession.saw(count)
            Load.Ok(count to asins.distinct())
        }
        is WebEngine.Res.Fail -> fail(r)
    }

    /**
     * Taps Amazon's real Add to cart button in the hidden engine, then checks the
     * cart to confirm it worked. Never touches Buy Now or checkout.
     */
    suspend fun addToCart(asin: String): AddResult = engine.session { s -> addToCartSteps(s, asin) }

    private suspend fun addToCartSteps(s: WebEngine.Session, asin: String): AddResult {
        val productUrl = url("product", "asin" to asin)
        val before = when (val r = s.run(productUrl, "cartcount")) {
            is WebEngine.Res.Ok -> Parse.int(r.json.optString("count"))
            is WebEngine.Res.Fail -> return AddResult.Failed(r.message)
        }
        when (val click = s.run(null, "addtocart")) {
            is WebEngine.Res.Ok -> if (!click.json.optBoolean("clicked")) {
                return AddResult.Failed(click.json.optString("reason").ifBlank { "Couldn't find Add to cart" })
            }
            is WebEngine.Res.Fail -> return AddResult.Failed(click.message)
        }
        delay(4_000)
        return when (val cart = s.run(url("cart"), "cartcount")) {
            is WebEngine.Res.Ok -> {
                val after = Parse.int(cart.json.optString("count"))
                AmazonSession.saw(after)
                val arr = cart.json.optJSONArray("asins")
                val inCart = arr != null && (0 until arr.length()).any { arr.optString(it) == asin }
                if (inCart || (after != null && before != null && after > before)) AddResult.Added(after)
                else AddResult.Failed("Amazon didn't confirm the item was added")
            }
            is WebEngine.Res.Fail -> AddResult.Failed(cart.message)
        }
    }
}
