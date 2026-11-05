package com.emotionalleaves.cartwise

import com.emotionalleaves.cartwise.engine.Sensitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hard rule 3 depends on these: nothing may be injected on sign-in, payment or checkout pages. */
class SensitiveTest {
    @Test fun signInAndMoneyPagesAreSensitive() {
        assertTrue(Sensitive.isSignIn("https://www.amazon.com/ap/signin?openid.return_to=x"))
        assertTrue(Sensitive.isSensitive("https://www.amazon.com/ap/mfa?x=1"))
        assertTrue(Sensitive.isSensitive("https://www.amazon.com/gp/buy/spc/handlers/display.html?hasWorkingJavascript=1"))
        assertTrue(Sensitive.isSensitive("https://www.amazon.com/checkout/p/p-106-123/spc?pipelineType=Chewbacca"))
        assertTrue(Sensitive.isSensitive("https://www.amazon.com/cpe/yourpayments/wallet"))
        assertTrue(Sensitive.isSensitive("https://www.amazon.com/gp/buy/thankyou/handlers/display.html"))
    }

    @Test fun normalPagesAreNot() {
        assertFalse(Sensitive.isSensitive("https://www.amazon.com/s?k=usb+c+cable&page=1"))
        assertFalse(Sensitive.isSensitive("https://www.amazon.com/dp/B0AAAAAAA1"))
        assertFalse(Sensitive.isSensitive("https://www.amazon.com/gp/cart/view.html"))
        assertFalse(Sensitive.isSensitive("https://www.amazon.com/your-orders/orders?timeFilter=year-2026"))
    }

    @Test fun cartDetection() {
        assertTrue(Sensitive.isCart("https://www.amazon.com/gp/cart/view.html?ref_=nav_cart"))
        assertTrue(Sensitive.isCart("https://www.amazon.com/gp/aw/c"))
        assertFalse(Sensitive.isCart("https://www.amazon.com/gp/buy/spc/handlers/display.html"))
        assertFalse(Sensitive.isCart("https://www.amazon.com/dp/B0AAAAAAA1"))
    }

    @Test fun hosts() {
        assertTrue(Sensitive.isAmazonHost("www.amazon.com"))
        assertTrue(Sensitive.isAmazonHost("m.media-amazon.com"))
        assertFalse(Sensitive.isAmazonHost("evilamazon.com"))
        assertFalse(Sensitive.isAmazonHost("amazon.com.evil.net"))
    }

    @Test fun adsAndTrackersAreSkippedButNotPagesOrPhotos() {
        val nb = com.emotionalleaves.cartwise.engine.NetBlock
        assertTrue(nb.blocks("aax-us-east.amazon-adsystem.com", "/x/px"))
        assertTrue(nb.blocks("fls-na.amazon.com", "/1/batch/1/OE/"))
        assertTrue(nb.blocks("www.amazon.com", "/uedata/x"))
        assertTrue(nb.blocks("m.media-amazon.com", "/images/S/sash/font.woff2"))
        assertFalse(nb.blocks("www.amazon.com", "/s"))
        assertFalse(nb.blocks("www.amazon.com", "/dp/B0AAAAAAA1"))
        assertFalse(nb.blocks("m.media-amazon.com", "/images/I/71abc._AC_SL500_.jpg"))
        assertFalse(nb.blocks("www.amazon.com", "/gp/cart/view.html"))
    }
}
