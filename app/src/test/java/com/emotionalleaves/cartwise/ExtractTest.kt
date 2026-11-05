package com.emotionalleaves.cartwise

import com.emotionalleaves.cartwise.engine.Extract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures are real page-reader output on mock pages (see tools/make-fixtures.js). */
class ExtractTest {
    private fun fixture(name: String) = JSONObject(javaClass.getResource("/fixtures/$name.json")!!.readText())

    @Test fun search() {
        val p = Extract.searchPage(fixture("search_search"), "https://www.amazon.com/s?k=usb")
        assertEquals(2, p.items.size)
        assertTrue(p.health.ok)
        val first = p.items[0]
        assertEquals(12.99, first.price!!, 1e-9)
        assertEquals("count", first.unitPrice!!.unit)
        assertEquals(4.7, first.rating!!, 1e-9)
        assertEquals(45123, first.reviews)
        assertTrue(first.prime)
        assertEquals("Anker", first.brand)
        assertEquals(1, first.deliveryDays)
        assertFalse(first.sponsored)
        assertTrue(p.items[1].sponsored)
    }

    @Test fun product() {
        val p = Extract.product(fixture("product_product"), "https://www.amazon.com/dp/B0AAAAAAA1")
        assertEquals("B0AAAAAAA1", p.asin)
        assertEquals("Anker", p.brand)
        assertEquals(12.99, p.price!!, 1e-9)
        assertEquals("$15.99", p.listPriceText)
        assertNotNull(p.unitPrice)
        assertEquals(listOf(80, 10, 5, 2, 3), p.histogram)
        assertEquals(45123, p.reviews)
        assertEquals(1.0, p.seller, 1e-9)
        assertEquals("Electronics › Computers & Accessories › Cables", p.category)
        assertEquals(2, p.variants.size)
        assertTrue(p.variants.first { it.label == "6ft" }.selected)
        assertEquals("Size", p.variants[0].dim)
        assertEquals("B0BBBBBBB1", p.related.single().asin)
        assertTrue(p.canAddToCart)
        assertEquals(2, p.cartCount)
        assertEquals("Brand" to "Anker", p.details.first())
        assertEquals("Great cable", p.reviewList.single().title)
    }

    @Test fun orders() {
        val o = Extract.orders(fixture("orders_orders"), "https://www.amazon.com/your-orders/orders")
        assertEquals(2, o.orders.size)
        assertTrue(o.orders[0].returned)
        assertEquals("112-1234567-1234567", o.orders[0].id)
        assertTrue(o.orders[0].date > 0)
        assertEquals("B0CCCCCCC1", o.orders[0].items.single().asin)
        assertFalse(o.orders[1].returned)
        assertEquals("Paper towels 12 pack", o.orders[1].items.single().title)
    }

    @Test fun lists() {
        val l = Extract.lists(fixture("lists_lists"), "https://www.amazon.com/hz/wishlist/ls")
        assertEquals(2, l.lists.size)
        assertEquals(24.5, l.items.single().price!!, 1e-9)
        assertEquals("Shopping List", l.name)
    }

    @Test fun phoneSearchLayout() {
        val p = Extract.searchPage(fixture("search_searchMobile"), "https://www.amazon.com/s?k=sound+bar")
        val tcl = p.items[0]
        assertEquals("TCL 2.1 Channel Sound Bar with Wireless Subwoofer, Dolby Audio", tcl.title)
        assertEquals("TCL", tcl.brand)
        assertTrue("placeholder photo must be skipped: ${tcl.image}", tcl.image.contains("71SoundBarA"))
        assertEquals("LG S40T 2.1 Sound Bar with Subwoofer", p.items[1].title)
        assertTrue(p.items[1].image.contains("61LGbar"))
    }

    @Test fun phoneProductWithoutBreadcrumbs() {
        val p = Extract.product(fixture("product_productMobile"), "https://www.amazon.com/dp/B01GGKZ2SC")
        assertEquals("Kitchen & Dining › Burr Coffee Grinders", p.category)
        assertEquals(49.99, p.price!!, 1e-9)
        assertEquals(4.5, p.rating!!, 1e-9)
        assertEquals(12345, p.reviews)
        assertEquals(1.0, p.seller, 1e-9)
        assertTrue(p.canAddToCart)
        assertTrue(p.images.single().contains("SL1500"))
        assertEquals("FREE delivery Saturday, November 8", p.deliveryText)
        val h = com.emotionalleaves.cartwise.engine.HealthLog.state.value["product"]!!
        assertEquals(1.0, h.fields["category"]!!, 1e-9)
        assertTrue(h.notes.contains("category:best-sellers-rank"))
    }

    @Test fun reportIsSmallAndTargeted() {
        val html = fixture("snapshot_productMobile").getString("html")
        assertTrue(html.length < 16_000)
        assertTrue(html.contains("Best Sellers Rank"))
        assertFalse(html.contains("<script"))
    }

    @Test fun photos() {
        assertEquals("", Extract.cleanImage("https://m.media-amazon.com/images/I/01MKUOLsA5L._AC_UL320_.gif"))
        assertEquals("", Extract.cleanImage("data:image/gif;base64,AAA"))
        assertEquals(
            "https://m.media-amazon.com/images/I/71abc._AC_SL600_.jpg",
            com.emotionalleaves.cartwise.engine.Fmt.image("https://m.media-amazon.com/images/I/71abc._AC_UY218_.jpg", 600),
        )
        assertEquals(
            "https://m.media-amazon.com/images/I/71abc._AC_SL600_.jpg",
            com.emotionalleaves.cartwise.engine.Fmt.image("https://m.media-amazon.com/images/I/71abc.jpg", 600),
        )
    }

    @Test fun addToCartGuard() {
        assertTrue(fixture("addtocart_product").getBoolean("clicked"))
        assertFalse(fixture("addtocart_evil").getBoolean("clicked"))
    }
}
