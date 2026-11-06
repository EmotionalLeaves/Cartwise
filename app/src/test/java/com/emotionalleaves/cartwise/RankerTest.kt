package com.emotionalleaves.cartwise

import com.emotionalleaves.cartwise.engine.Listing
import com.emotionalleaves.cartwise.rank.Preset
import com.emotionalleaves.cartwise.rank.Ranker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RankerTest {
    private val a = Listing("A", "Alpha usb cable", price = 5.0, rating = 5.0, reviews = 3, deliveryDays = 6, brand = "Alpha", image = "https://m.media-amazon.com/images/I/aaa._AC_.jpg")
    private val b = Listing("B", "Beta usb cable", price = 15.0, rating = 4.6, reviews = 40_000, deliveryDays = 1, prime = true, brand = "Beta", image = "https://m.media-amazon.com/images/I/bbb._AC_.jpg")
    private val c = Listing("C", "Gamma usb cable", price = 10.0, rating = 4.2, reviews = 500, brand = "Gamma", image = "https://m.media-amazon.com/images/I/ccc._AC_.jpg")
    private val d = Listing("D", "Delta usb cable", price = 8.0, rating = 4.0, reviews = 1000, brand = "Delta", image = "https://m.media-amazon.com/images/I/ddd._AC_.jpg")
    private val page = listOf(a, b, c, d)

    private fun order(p: Preset, items: List<Listing> = page) = Ranker.rank(items, Ranker.Ctx("usb c cable", p)).map { it.item.asin }

    @Test fun presetsChangeTheOrder() {
        val cheapest = order(Preset.CHEAPEST)
        val trusted = order(Preset.TRUSTED)
        assertEquals("A", cheapest.first())
        assertEquals("B", trusted.first())
        assertTrue(cheapest != trusted)
    }

    @Test fun fewReviewsDontWin() {
        val r = Ranker.rank(page, Ranker.Ctx("usb c cable", Preset.TRUSTED))
        val ba = r.first { it.item.asin == "A" }.bayes
        val bb = r.first { it.item.asin == "B" }.bayes
        assertTrue("5.0 from 3 reviews should not beat 4.6 from 40k", bb > ba)
    }

    @Test fun sponsoredAndBlockedAreRemoved() {
        val sp = d.copy(asin = "S", sponsored = true, image = "x")
        val r = Ranker.rank(page + sp, Ranker.Ctx("usb c cable", Preset.BEST, hideSponsored = true, blockedBrands = setOf("gamma")))
        val ids = r.map { it.item.asin }
        assertTrue("S" !in ids)
        assertTrue("C" !in ids)
        val tagged = Ranker.rank(page + sp, Ranker.Ctx("usb c cable", Preset.BEST, hideSponsored = false))
        assertTrue(tagged.any { it.item.asin == "S" })
    }

    @Test fun clonesAreGrouped() {
        val clone = b.copy(asin = "B2", title = "Zzqx usb cable", brand = "Zzqx", rating = 4.1, reviews = 20)
        val r = Ranker.rank(page + clone, Ranker.Ctx("usb c cable", Preset.BEST))
        assertEquals(4, r.size)
        val leader = r.first { it.item.asin == "B" || it.item.asin == "B2" }
        assertEquals(1, leader.similar.size)
    }

    @Test fun weightsAreNormalized() {
        val r = Ranker.rank(page, Ranker.Ctx("usb c cable", Preset.BEST, custom = doubleArrayOf(2.0, 2.0, 2.0, 2.0, 2.0, 2.0)))
        assertEquals(1.0, r.first().weights.sum(), 1e-9)
        r.forEach { assertTrue(it.parts.all { p -> p in 0.0..1.0 }) }
    }

    @Test fun discountPresetAndSaleFilter() {
        val sale = c.copy(asin = "SALE", title = "Sale usb cable", brand = "Saley", image = "s", price = 9.0, listPrice = 18.0)
        val items = page + sale
        assertEquals(50, sale.discountPct)
        assertEquals("SALE", Ranker.rank(items, Ranker.Ctx("usb c cable", Preset.DISCOUNT)).first().item.asin)
        val onlySale = Ranker.rank(items, Ranker.Ctx("usb c cable", Preset.BEST, saleOnly = true))
        assertEquals(listOf("SALE"), onlySale.map { it.item.asin })
    }

    @Test fun shortCustomWeightsArePadded() {
        val r = Ranker.rank(page, Ranker.Ctx("usb c cable", Preset.BEST, custom = doubleArrayOf(1.0, 1.0, 1.0, 1.0, 1.0)))
        assertEquals(6, r.first().weights.size)
    }

    @Test fun filters() {
        val f = com.emotionalleaves.cartwise.rank.Filters(maxPrice = 12.0, minRating = 4.1)
        val ids = Ranker.rank(page, Ranker.Ctx("usb c cable", Preset.BEST, filters = f)).map { it.item.asin }.toSet()
        assertEquals(setOf("A", "C"), ids)
        assertEquals("&high-price=12", f.amazonParams())
        val fast = Ranker.rank(page, Ranker.Ctx("usb c cable", Preset.BEST, filters = com.emotionalleaves.cartwise.rank.Filters(maxDays = 1, seller = 1)))
        assertEquals(listOf("B"), fast.map { it.item.asin })
        assertEquals(2, com.emotionalleaves.cartwise.rank.Filters(minReviews = 100, brands = setOf("beta")).active)
    }
}
