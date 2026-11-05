package com.emotionalleaves.cartwise.engine

import android.net.Uri
import java.util.Locale

/**
 * Hard rule 3: no scripts or CSS on sign-in, payment or checkout pages.
 * Everything that injects anything asks this first.
 */
object Sensitive {
    private val signIn = listOf("/ap/", "/ax/", "/mfa", "/cvf", "/sign-in", "/signin")
    private val money = listOf(
        "/gp/buy", "/checkout", "/cpe/", "/payment", "/payments", "/wallet", "/gp/css/account/cards",
        "/spc/", "/ordering", "/thankyou", "/thank-you", "/your-payments", "/gp/prime", "/amazonprime",
        "/gp/aw/cart/checkout", "/gc/redeem", "/hz/mycd", "/a/addresses", "/gp/css/account/address",
    )

    private fun path(url: String): String = try {
        (Uri.parse(url).path ?: "").lowercase(Locale.US)
    } catch (_: Exception) {
        url.lowercase(Locale.US)
    }

    fun isSignIn(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val p = path(url)
        return signIn.any { p.startsWith(it) || p.contains(it) }
    }

    fun isSensitive(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (isSignIn(url)) return true
        val p = path(url)
        return money.any { p.contains(it) }
    }

    fun isCart(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val p = path(url)
        return p.contains("/cart") && !isSensitive(url)
    }

    fun isAmazonHost(host: String): Boolean {
        val h = host.lowercase(Locale.US)
        return h == "amazon.com" || h.endsWith(".amazon.com") || h == "a.co" ||
            h.endsWith(".media-amazon.com") || h.endsWith(".ssl-images-amazon.com") ||
            h.endsWith(".amazon-adsystem.com") || h.endsWith(".images-amazon.com")
    }
}
