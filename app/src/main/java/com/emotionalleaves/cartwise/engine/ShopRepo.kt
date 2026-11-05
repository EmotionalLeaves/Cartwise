package com.emotionalleaves.cartwise.engine

import kotlinx.coroutines.delay
import java.net.URLEncoder

/** Everything the app asks of Amazon, in app terms. */
class ShopRepo(private val engine: WebEngine, private val selectors: SelectorStore) {

    companion object {
        fun enc(s: String): String = URLEncoder.encode(s.trim(), "UTF-8")
    }

    fun url(name: String, vararg values: Pair<String, String>) = selectors.url(name, *values)

    fun isSignedIn(): Boolean = engine.isSignedIn()

    private fun fail(r: WebEngine.Res.Fail): Load.Fail = Load.Fail(r.reason, r.message, r.url)

    suspend fun search(query: String, page: Int): Load<SearchPage> =
        searchUrl(url("search", "q" to enc(query), "page" to page.toString()))

    suspend fun searchUrl(u: String): Load<SearchPage> = when (val r = engine.run(u, "search")) {
        is WebEngine.Res.Ok -> Load.Ok(Extract.searchPage(r.json, r.url))
        is WebEngine.Res.Fail -> fail(r)
    }

    suspend fun product(asin: String): Load<Product> {
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

    suspend fun tiles(u: String): Load<List<Listing>> = when (val r = engine.run(u, "tiles")) {
        is WebEngine.Res.Ok -> Load.Ok(Extract.tiles(r.json, r.url))
        is WebEngine.Res.Fail -> fail(r)
    }

    suspend fun lists(listUrl: String?): Load<ListsPage> {
        val u = listUrl ?: url("lists")
        return when (val r = engine.run(u, "lists")) {
            is WebEngine.Res.Ok -> Load.Ok(Extract.lists(r.json, r.url))
            is WebEngine.Res.Fail -> fail(r)
        }
    }

    suspend fun orders(u: String): Load<OrdersPage> = when (val r = engine.run(u, "orders")) {
        is WebEngine.Res.Ok -> Load.Ok(Extract.orders(r.json, r.url))
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
                val arr = cart.json.optJSONArray("asins")
                val inCart = arr != null && (0 until arr.length()).any { arr.optString(it) == asin }
                if (inCart || (after != null && before != null && after > before)) AddResult.Added(after)
                else AddResult.Failed("Amazon didn't confirm the item was added")
            }
            is WebEngine.Res.Fail -> AddResult.Failed(cart.message)
        }
    }
}
