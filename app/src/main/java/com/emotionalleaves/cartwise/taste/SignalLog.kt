package com.emotionalleaves.cartwise.taste

import com.emotionalleaves.cartwise.data.CwDao
import com.emotionalleaves.cartwise.data.ItemEntity
import com.emotionalleaves.cartwise.data.SignalEntity
import com.emotionalleaves.cartwise.engine.Listing
import com.emotionalleaves.cartwise.engine.Product

/** Signal weights from the plan. Each fades with a 60-day half-life; purchases over 180 days. */
object SignalType {
    const val KEPT = "kept"
    const val LISTED = "listed"
    const val THUMB_UP = "thumb_up"
    const val DWELL = "dwell"
    const val SEARCH = "search"
    const val SKIP = "skip"
    const val THUMB_DOWN = "thumb_down"
    const val RETURNED = "returned"
    const val QUIZ = "quiz"
    const val OPEN = "open"

    fun weight(t: String): Double = when (t) {
        KEPT -> 5.0
        LISTED -> 3.0
        THUMB_UP -> 3.0
        QUIZ -> 3.0
        DWELL -> 1.0
        SEARCH -> 1.0
        SKIP -> -0.5
        THUMB_DOWN -> -3.0
        RETURNED -> -4.0
        else -> 0.0
    }

    fun halfLifeDays(t: String): Double = if (t == KEPT || t == RETURNED) 180.0 else 60.0

    fun label(t: String): String = when (t) {
        KEPT -> "Bought and kept"
        LISTED -> "Added to a list"
        THUMB_UP -> "Thumbs up"
        QUIZ -> "Start quiz pick"
        DWELL -> "Looked 20+ seconds"
        SEARCH -> "Searched"
        SKIP -> "Skipped 3 times"
        THUMB_DOWN -> "Thumbs down"
        RETURNED -> "Bought and returned"
        OPEN -> "Opened"
        else -> t
    }
}

class SignalLog(private val dao: CwDao) {

    /** Saves what we know about an item, keeping richer old fields when the new ones are blank. */
    suspend fun remember(n: ItemEntity) {
        val o = dao.item(n.ref)
        if (o == null) {
            dao.upsertItem(n); return
        }
        val title = n.title.ifBlank { o.title }
        val category = n.category.ifBlank { o.category }
        val keepVector = title == o.title && category == o.category
        dao.upsertItem(
            ItemEntity(
                ref = n.ref,
                title = title,
                category = category,
                brand = n.brand.ifBlank { o.brand },
                price = n.price ?: o.price,
                image = n.image.ifBlank { o.image },
                rating = n.rating ?: o.rating,
                reviews = maxOf(n.reviews, o.reviews),
                seller = if (n.seller != 0.5) n.seller else o.seller,
                vector = if (keepVector) o.vector else null,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun log(ref: String, type: String, note: String = "", ts: Long = System.currentTimeMillis()) {
        dao.insertSignal(SignalEntity(ref = ref, type = type, weight = SignalType.weight(type), ts = ts, note = note.take(120)))
    }

    suspend fun logListing(l: Listing, type: String, category: String = "") {
        remember(l.toItem(category))
        log(l.asin, type, l.title)
    }

    suspend fun logProduct(p: Product, type: String) {
        remember(p.toItem())
        log(p.asin, type, p.title)
    }

    suspend fun logSearch(q: String) {
        val t = q.trim(); if (t.isBlank()) return
        val ref = "q:" + t.lowercase()
        remember(ItemEntity(ref = ref, title = t))
        log(ref, SignalType.SEARCH, t)
    }
}

fun Listing.toItem(category: String = ""): ItemEntity = ItemEntity(
    ref = asin, title = title, category = category, brand = brand, price = price, image = image,
    rating = rating, reviews = reviews, seller = seller,
)

fun Product.toItem(): ItemEntity = ItemEntity(
    ref = asin, title = title, category = category, brand = brand, price = price, image = images.firstOrNull() ?: "",
    rating = rating, reviews = reviews, seller = seller,
)
