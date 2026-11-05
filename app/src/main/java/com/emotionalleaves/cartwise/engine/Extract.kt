package com.emotionalleaves.cartwise.engine

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Latest page-reader health per page kind, for the debug screen. */
/** Things the pages tell us about your Amazon session, e.g. the cart count shown in Amazon's top bar. */
object AmazonSession {
    val cartCount = MutableStateFlow<Int?>(null)
    fun saw(count: Int?) {
        if (count != null && count in 0..999) cartCount.value = count
    }
}

object HealthLog {
    /** The last search page's items, for the debug screen and the emulator tour. */
    val lastListings = MutableStateFlow<List<Listing>>(emptyList())
    val state = MutableStateFlow<Map<String, Health>>(emptyMap())
    fun record(h: Health) {
        state.value = state.value + (h.kind to h)
    }
}

/** Turns the page reader's JSON into app models. */
object Extract {

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
    }

    /** Drops Amazon's grey/transparent placeholder pixels so the UI never shows an empty tile as a "photo". */
    fun cleanImage(u: String): String {
        val t = u.trim()
        if (t.isBlank() || t.startsWith("data:") || !t.contains("/images/I/")) return ""
        if (Regex("(?i)\\.gif(\\?|$)").containsMatchIn(t) || t.contains("grey-pixel") || t.contains("transparent-pixel") || t.contains("01MKUOLsA5L")) return ""
        return t
    }

    fun guessBrand(title: String): String =
        title.trim().split(' ').firstOrNull()?.trim(',', '-', ':', '|', '(', ')')?.take(30) ?: ""

    fun listing(o: JSONObject): Listing? {
        val asin = o.optString("asin").trim()
        if (asin.isBlank()) return null
        val title = o.optString("title").replace(Regex("\\s+"), " ").trim()
        val priceText = o.optString("price")
        val delivery = o.optString("delivery").replace(Regex("\\s+"), " ").trim()
        val rawBrand = Parse.cleanBrand(o.optString("brand"))
        val brand = if (rawBrand.isNotBlank() && rawBrand.length < 40 && rawBrand != title) rawBrand else guessBrand(title)
        val price = Parse.price(priceText)
        val pctOff = o.optInt("pctOff", 0)
        val listPrice = Parse.price(o.optString("listPrice"))
            ?: if (pctOff in 1..95 && price != null) Math.round(price / (1 - pctOff / 100.0) * 100) / 100.0 else null
        return Listing(
            asin = asin,
            title = title,
            url = o.optString("url").ifBlank { "https://www.amazon.com/dp/$asin" },
            price = price,
            priceText = priceText,
            unitPrice = Parse.unitPrice(o.optString("unitPrice")),
            listPrice = listPrice,
            rating = Parse.rating(o.optString("rating")),
            reviews = Parse.reviews(o.optString("reviews")),
            image = cleanImage(o.optString("image")),
            deliveryText = delivery.take(120),
            deliveryDays = Parse.deliveryDays(delivery),
            prime = o.optBoolean("prime"),
            sponsored = o.optBoolean("sponsored"),
            brand = brand,
        )
    }

    private fun fill(items: List<Listing>, f: (Listing) -> Boolean): Double =
        if (items.isEmpty()) 0.0 else items.count(f).toDouble() / items.size

    fun searchPage(j: JSONObject, url: String): SearchPage {
        val items = j.optJSONArray("items").objects().mapNotNull { listing(it) }
        val fields = mapOf(
            "title" to fill(items) { it.title.isNotBlank() },
            "price" to fill(items) { it.price != null },
            "rating" to fill(items) { it.rating != null },
            "reviews" to fill(items) { it.reviews > 0 },
            "image" to fill(items) { it.image.isNotBlank() },
            "delivery" to fill(items) { it.deliveryText.isNotBlank() },
            "unit price" to fill(items) { it.unitPrice != null },
            "list price" to fill(items) { it.listPrice != null },
        )
        val ok = items.size >= 2 && (fields["title"] ?: 0.0) >= 0.6
        val h = Health("search", System.currentTimeMillis(), items.size, fields, ok, url)
        HealthLog.record(h)
        HealthLog.lastListings.value = items
        AmazonSession.saw(Parse.int(j.optString("cartCount")))
        return SearchPage(items.filter { it.title.isNotBlank() }, j.optBoolean("hasNext", true), h)
    }

    fun tiles(j: JSONObject, url: String, kind: String = "tiles"): List<Listing> {
        val items = j.optJSONArray("items").objects().mapNotNull { listing(it) }.filter { it.title.length > 3 }
        HealthLog.record(
            Health(
                kind, System.currentTimeMillis(), items.size,
                mapOf("title" to fill(items) { it.title.isNotBlank() }, "price" to fill(items) { it.price != null }, "image" to fill(items) { it.image.isNotBlank() }),
                items.isNotEmpty(), url,
            ),
        )
        return items
    }

    fun product(j: JSONObject, url: String): Product {
        val delivery = j.optString("delivery").replace(Regex("\\s+"), " ").trim()
        val pbPrice = j.optString("price")
        val variants = j.optJSONArray("variants").objects().map {
            Variant(it.optString("dim"), it.optString("label"), it.optString("asin"), it.optBoolean("selected"), it.optBoolean("available", true))
        }
        val reviews = j.optJSONArray("reviews").objects().map {
            Review(it.optString("title"), it.optString("body"), Parse.rating(it.optString("rating")), it.optString("author"), it.optString("date"))
        }.filter { it.body.isNotBlank() || it.title.isNotBlank() }
        val detailsArr = j.optJSONArray("details")
        val details = ArrayList<Pair<String, String>>()
        if (detailsArr != null) for (i in 0 until detailsArr.length()) {
            val row = detailsArr.optJSONArray(i) ?: continue
            val k = row.optString(0); val v = row.optString(1)
            if (k.isNotBlank() && v.isNotBlank() && details.none { it.first == k }) details += k to v
        }
        val crumbs = j.optJSONArray("breadcrumbs").strings().map { it.trim() }.filter { it.length in 1..60 }
        val brand = Parse.cleanBrand(j.optString("brand")).take(40)
        val title = j.optString("title").replace(Regex("\\s+"), " ").trim()
        val p = Product(
            asin = j.optString("asin"),
            title = title,
            brand = brand.ifBlank { guessBrand(title) },
            price = Parse.price(pbPrice),
            priceText = pbPrice,
            listPriceText = j.optString("listPrice"),
            unitPrice = Parse.unitPrice(j.optString("unitPrice")),
            images = j.optJSONArray("images").strings().map { cleanImage(it) }.filter { it.isNotBlank() }.distinct(),
            rating = Parse.rating(j.optString("rating")),
            reviews = Parse.reviews(j.optString("reviewCount")),
            histogram = j.optJSONArray("histogram").let { a -> if (a == null) emptyList() else (0 until a.length()).map { a.optInt(it) } },
            deliveryText = delivery.take(200),
            deliveryDays = Parse.deliveryDays(delivery),
            soldBy = j.optString("soldBy").replace(Regex("\\s+"), " ").trim().take(120),
            shipsFrom = j.optString("shipsFrom").replace(Regex("\\s+"), " ").trim().take(120),
            returns = j.optString("returns").replace(Regex("\\s+"), " ").trim().take(160),
            availability = j.optString("availability").replace(Regex("\\s+"), " ").trim().take(120),
            bullets = j.optJSONArray("bullets").strings().distinct(),
            details = details,
            reviewList = reviews,
            breadcrumbs = crumbs,
            variants = variants,
            related = j.optJSONArray("related").objects().mapNotNull { listing(it) }.filter { it.title.length > 3 },
            canAddToCart = j.optBoolean("canAddToCart"),
            cartCount = Parse.int(j.optString("cartCount")),
            url = url,
            pctOff = j.optInt("pctOff", 0),
        )
        val fields = mapOf(
            "title" to if (p.title.isNotBlank()) 1.0 else 0.0,
            "price" to if (p.price != null) 1.0 else 0.0,
            "images" to if (p.images.isNotEmpty()) 1.0 else 0.0,
            "rating" to if (p.rating != null) 1.0 else 0.0,
            "delivery" to if (p.deliveryText.isNotBlank()) 1.0 else 0.0,
            "sold by" to if (p.soldBy.isNotBlank()) 1.0 else 0.0,
            "category" to if (p.breadcrumbs.isNotEmpty()) 1.0 else 0.0,
            "add to cart" to if (p.canAddToCart) 1.0 else 0.0,
        )
        AmazonSession.saw(p.cartCount)
        HealthLog.record(
            Health("product", System.currentTimeMillis(), if (p.title.isNotBlank()) 1 else 0, fields, p.title.isNotBlank(), url, j.optJSONArray("healed").strings()),
        )
        return p
    }

    fun lists(j: JSONObject, url: String): ListsPage {
        val lists = j.optJSONArray("lists").objects().map { NamedList(it.optString("id"), it.optString("name").trim(), it.optString("url")) }
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
        val items = j.optJSONArray("items").objects().mapNotNull { listing(it) }.filter { it.title.isNotBlank() }
        HealthLog.record(
            Health("lists", System.currentTimeMillis(), items.size,
                mapOf("lists" to if (lists.isNotEmpty()) 1.0 else 0.0, "title" to fill(items) { it.title.isNotBlank() }, "price" to fill(items) { it.price != null }),
                true, url),
        )
        return ListsPage(lists, items, j.optString("name").trim())
    }

    fun orders(j: JSONObject, url: String): OrdersPage {
        val cards = j.optJSONArray("orders").objects().map { o ->
            OrderCard(
                id = o.optString("id"),
                date = Parse.orderDate(o.optString("date")),
                returned = o.optBoolean("returned"),
                items = o.optJSONArray("items").objects().mapNotNull {
                    val asin = it.optString("asin")
                    if (asin.isBlank()) null else OrderLine(asin, it.optString("title").trim(), cleanImage(it.optString("image")), Parse.price(it.optString("price")))
                }.filter { it.title.isNotBlank() },
            )
        }
        val lines = cards.flatMap { it.items }
        HealthLog.record(
            Health("orders", System.currentTimeMillis(), cards.size,
                mapOf("date" to if (cards.isEmpty()) 0.0 else cards.count { it.date > 0 }.toDouble() / cards.size,
                    "items" to if (cards.isEmpty()) 0.0 else cards.count { it.items.isNotEmpty() }.toDouble() / cards.size,
                    "titles" to if (lines.isEmpty()) 0.0 else lines.count { it.title.isNotBlank() }.toDouble() / lines.size),
                cards.isNotEmpty(), url),
        )
        return OrdersPage(cards, j.optBoolean("hasNext"))
    }
}
