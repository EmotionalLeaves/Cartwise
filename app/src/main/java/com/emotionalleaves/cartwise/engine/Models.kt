package com.emotionalleaves.cartwise.engine

data class UnitPrice(val value: Double, val unit: String) {
    fun label(): String = Fmt.unit(value) + "/" + unit
}

/** One product as seen in a list: search results, best sellers, related items, lists. */
data class Listing(
    val asin: String,
    val title: String,
    val url: String = "",
    val price: Double? = null,
    val priceText: String = "",
    val unitPrice: UnitPrice? = null,
    val rating: Double? = null,
    val reviews: Int = 0,
    val image: String = "",
    val deliveryText: String = "",
    val deliveryDays: Int? = null,
    val prime: Boolean = false,
    val sponsored: Boolean = false,
    val brand: String = "",
) {
    /** Seller score for list views: Prime usually means sold or fulfilled by Amazon. */
    val seller: Double get() = if (prime) 0.8 else 0.5
}

data class SearchPage(val items: List<Listing>, val hasNext: Boolean, val health: Health)

data class Variant(
    val dim: String,
    val label: String,
    val asin: String,
    val selected: Boolean,
    val available: Boolean,
)

data class Review(
    val title: String,
    val body: String,
    val rating: Double?,
    val author: String,
    val date: String,
)

data class Product(
    val asin: String,
    val title: String,
    val brand: String,
    val price: Double?,
    val priceText: String,
    val listPriceText: String,
    val unitPrice: UnitPrice?,
    val images: List<String>,
    val rating: Double?,
    val reviews: Int,
    val histogram: List<Int>,
    val deliveryText: String,
    val deliveryDays: Int?,
    val soldBy: String,
    val shipsFrom: String,
    val returns: String,
    val availability: String,
    val bullets: List<String>,
    val details: List<Pair<String, String>>,
    val reviewList: List<Review>,
    val breadcrumbs: List<String>,
    val variants: List<Variant>,
    val related: List<Listing>,
    val canAddToCart: Boolean,
    val cartCount: Int?,
    val url: String,
) {
    val seller: Double get() = Parse.sellerScore(soldBy, shipsFrom)
    val category: String get() = breadcrumbs.joinToString(" › ")

    fun toListing(): Listing = Listing(
        asin = asin, title = title, url = url, price = price, priceText = priceText,
        unitPrice = unitPrice, rating = rating, reviews = reviews, image = images.firstOrNull() ?: "",
        deliveryText = deliveryText, deliveryDays = deliveryDays, prime = seller >= 0.8, brand = brand,
    )
}

data class NamedList(val id: String, val name: String, val url: String)
data class ListsPage(val lists: List<NamedList>, val items: List<Listing>, val name: String)

data class OrderLine(val asin: String, val title: String, val image: String, val price: Double?)
data class OrderCard(val id: String, val date: Long, val returned: Boolean, val items: List<OrderLine>)
data class OrdersPage(val orders: List<OrderCard>, val hasNext: Boolean)

/** How well the page reader did on one page, shown on the debug screen. */
data class Health(
    val kind: String,
    val at: Long,
    val count: Int,
    val fields: Map<String, Double>,
    val ok: Boolean,
    val url: String,
)

enum class Reason { OFFLINE, TIMEOUT, CAPTCHA, SIGNED_OUT, SENSITIVE, EMPTY, ERROR }

sealed class Load<out T> {
    data class Ok<T>(val v: T) : Load<T>()
    data class Fail(val reason: Reason, val message: String, val url: String? = null) : Load<Nothing>()
}

sealed class AddResult {
    data class Added(val count: Int?) : AddResult()
    data class Failed(val message: String) : AddResult()
}
