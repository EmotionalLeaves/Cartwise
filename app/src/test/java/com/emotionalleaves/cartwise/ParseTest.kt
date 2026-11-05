package com.emotionalleaves.cartwise

import com.emotionalleaves.cartwise.engine.Parse
import com.emotionalleaves.cartwise.engine.UnitPrice
import com.emotionalleaves.cartwise.rank.Words
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ParseTest {
    private fun cal(y: Int, m: Int, d: Int): Calendar = Calendar.getInstance().apply { clear(); set(y, m, d, 10, 0) }

    @Test fun prices() {
        assertEquals(1299.99, Parse.price("$1,299.99")!!, 1e-9)
        assertEquals(12.99, Parse.price("Now $12.99 was $15")!!, 1e-9)
        assertEquals(8.0, Parse.price("$8")!!, 1e-9)
        assertNull(Parse.price(""))
        assertNull(Parse.price("See options"))
    }

    @Test fun unitPrices() {
        assertEquals(UnitPrice(6.5, "count"), Parse.unitPrice("($6.50/Count)"))
        assertEquals(UnitPrice(6.5, "count"), Parse.unitPrice("($6.50 / Count)"))
        assertEquals(0.012, Parse.unitPrice("($1.20 / 100 Count)")!!.value, 1e-9)
        val lb = Parse.unitPrice("($12.00/lb)")!!
        assertEquals("oz", lb.unit); assertEquals(0.75, lb.value, 1e-9)
        assertEquals("fl oz", Parse.unitPrice("($3.99/Fl Oz)")!!.unit)
        assertEquals("oz", Parse.unitPrice("($0.42/Ounce)")!!.unit)
        assertNull(Parse.unitPrice("($3.00/Zorbles)"))
    }

    @Test fun ratingsAndReviews() {
        assertEquals(4.7, Parse.rating("4.7 out of 5 stars")!!, 1e-9)
        assertNull(Parse.rating("no stars"))
        assertEquals(45123, Parse.reviews("45,123 ratings"))
        assertEquals(1200, Parse.reviews("(1.2K)"))
        assertEquals(0, Parse.reviews("4.5 out of 5 stars"))
    }

    @Test fun deliveryDates() {
        val thu = cal(2026, Calendar.NOVEMBER, 5) // a Thursday
        assertEquals(1, Parse.deliveryDays("FREE delivery Tomorrow, Nov 6", thu))
        assertEquals(0, Parse.deliveryDays("Get it Today by 10 PM", thu))
        assertEquals(5, Parse.deliveryDays("FREE delivery Mon, Nov 10", thu))
        assertEquals(2, Parse.deliveryDays("FREE delivery Friday, November 7", thu))
        assertEquals(2, Parse.deliveryDays("Arrives Saturday", thu))
        assertEquals(3, Parse.deliveryDays("Arrives Jan 2", cal(2026, Calendar.DECEMBER, 30)))
        assertNull(Parse.deliveryDays("Ships soon", thu))
        assertEquals(1.0, Parse.deliveryScore(1), 1e-9)
        assertEquals(0.0, Parse.deliveryScore(9), 1e-9)
    }

    @Test fun sellerScores() {
        assertEquals(1.0, Parse.sellerScore("Amazon.com", "Amazon"), 1e-9)
        assertEquals(1.0, Parse.sellerScore("Ships from and sold by Amazon.com.", ""), 1e-9)
        assertEquals(0.8, Parse.sellerScore("Anker Direct", "Amazon"), 1e-9)
        assertEquals(0.5, Parse.sellerScore("Some Shop", "Some Shop"), 1e-9)
    }

    @Test fun misc() {
        assertEquals("Anker", Parse.cleanBrand("Visit the Anker Store"))
        assertEquals("Anker", Parse.cleanBrand("Brand: Anker"))
        assertEquals("71abcDEF", Parse.imageId("https://m.media-amazon.com/images/I/71abcDEF._AC_UY218_.jpg"))
        val d = Calendar.getInstance().apply { timeInMillis = Parse.orderDate("March 3, 2025") }
        assertEquals(2025, d.get(Calendar.YEAR)); assertEquals(Calendar.MARCH, d.get(Calendar.MONTH))
        assertEquals(2, Parse.int("2"))
        // Bayesian rating: 5.0 from 3 reviews lands near the prior, 4.6 from 40k stays near 4.6.
        assertTrue(Parse.bayes(5.0, 3, 4.4) < Parse.bayes(4.6, 40_000, 4.4))
    }

    @Test fun words() {
        assertTrue(Words.consumable("Bounty Paper Towels, 12 Pack"))
        assertFalse(Words.consumable("Mechanical keyboard"))
        val a = Words.cloneTokens("XYZ USB C Cable 6ft Braided Fast Charging", "XYZ")
        val b = Words.cloneTokens("QWERTY USB C Cable 6ft Braided Fast Charging", "QWERTY")
        assertTrue(Words.jaccard(a, b) >= 0.8)
    }
}
