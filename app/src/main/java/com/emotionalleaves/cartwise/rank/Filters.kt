package com.emotionalleaves.cartwise.rank

import com.emotionalleaves.cartwise.engine.Listing

/** Search filters. Price is also sent to Amazon; the rest narrow the loaded results instantly. */
data class Filters(
    val minPrice: Double? = null,
    val maxPrice: Double? = null,
    val minRating: Double? = null,
    val minReviews: Int = 0,
    /** Max days until arrival: 1 = today or tomorrow, 2 = within two days. */
    val maxDays: Int? = null,
    /** 0 any, 1 Prime or sold by Amazon, 2 sold by Amazon only (when known). */
    val seller: Int = 0,
    val minDiscount: Int = 0,
    val brands: Set<String> = emptySet(),
    val hideNoPrice: Boolean = false,
) {
    val active: Int
        get() = listOf(
            minPrice != null || maxPrice != null, minRating != null, minReviews > 0, maxDays != null,
            seller > 0, minDiscount > 0, brands.isNotEmpty(), hideNoPrice,
        ).count { it }

    /** The part Amazon can do for us, as extra address parameters. */
    fun amazonParams(): String = buildString {
        minPrice?.let { append("&low-price=").append(it.toInt()) }
        maxPrice?.let { append("&high-price=").append(Math.ceil(it).toInt()) }
    }

    fun keep(l: Listing): Boolean {
        val p = l.price
        if (minPrice != null && (p == null || p < minPrice)) return false
        if (maxPrice != null && (p == null || p > maxPrice)) return false
        if (hideNoPrice && p == null) return false
        if (minRating != null && (l.rating ?: 0.0) < minRating) return false
        if (minReviews > 0 && l.reviews < minReviews) return false
        if (maxDays != null && (l.deliveryDays ?: 99) > maxDays) return false
        if (seller >= 1 && l.seller < 0.8) return false
        if (seller >= 2 && l.seller < 1.0) return false
        if (minDiscount > 0 && l.discountPct < minDiscount) return false
        if (brands.isNotEmpty() && Ranker.brandKey(l) !in brands) return false
        return true
    }
}
