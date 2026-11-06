package com.emotionalleaves.cartwise.engine

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The fast path: downloads a page's HTML directly with your Amazon cookies, like the
 * browser's first request, but without running any of Amazon's scripts or loading ads,
 * fonts or images. One pooled HTTP/2 connection serves many pages, so after the first
 * request there is no new TLS handshake per page.
 */
object FastFetch {
    class Result(val html: String, val finalUrl: String, val status: Int)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val scriptRe = Regex("(?is)<script\\b[^>]*>.*?</script>|<link\\b[^>]*rel=[\"']?(?:preload|prefetch|stylesheet)[^>]*>|<iframe\\b[^>]*>.*?</iframe>|<noscript\\b[^>]*>.*?</noscript>")
    private val onAttrRe = Regex("(?i)\\son[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*')")

    /** Strips everything that could run, so the parser only reads. */
    fun inert(html: String): String = onAttrRe.replace(scriptRe.replace(html, ""), "")

    fun looksBlocked(html: String): Boolean =
        html.length < 3000 || Regex("(?i)validateCaptcha|Robot Check|Type the characters you see|api-services-support@amazon").containsMatchIn(html.take(60_000))

    fun looksSignedOut(finalUrl: String): Boolean = Sensitive.isSignIn(finalUrl)

    /** Amazon's transient "Sorry, something went wrong" page (usually a 503); worth one retry. */
    fun looksSorry(html: String, status: Int): Boolean =
        status == 503 || Regex("(?i)<title>\\s*Sorry!\\s*Something went wrong|Meet the dogs of Amazon|dogsofamazon").containsMatchIn(html.take(40_000))

    /** Opens the pooled connection early so the first real page doesn't pay for the handshake. */
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url("https://www.amazon.com/favicon.ico").head().build()).execute().close()
        } catch (_: Exception) {
        }
    }

    suspend fun get(url: String, userAgent: String): Result = withContext(Dispatchers.IO) {
        var cur = url
        var status = 0
        repeat(4) { hop ->
            val b = Request.Builder().url(cur)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Upgrade-Insecure-Requests", "1")
            CookieManager.getInstance().getCookie(cur)?.let { b.header("Cookie", it) }
            client.newCall(b.build()).execute().use { resp ->
                status = resp.code
                resp.headers("Set-Cookie").forEach { CookieManager.getInstance().setCookie(cur, it) }
                if (status in 300..399) {
                    val loc = resp.header("Location") ?: return@withContext Result("", cur, status)
                    cur = resp.request.url.resolve(loc)?.toString() ?: loc
                    if (hop == 3) return@withContext Result("", cur, status)
                } else {
                    return@withContext Result(resp.body?.string() ?: "", cur, status)
                }
            }
        }
        Result("", cur, status)
    }
}
