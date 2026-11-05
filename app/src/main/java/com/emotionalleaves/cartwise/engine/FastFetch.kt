package com.emotionalleaves.cartwise.engine

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * The fast path: downloads a page's HTML directly with your Amazon cookies, like the
 * browser's first request, but without running any of Amazon's scripts or loading ads,
 * fonts or images. The raw HTML also carries the real photo addresses, which Amazon's
 * scripts replace with grey placeholders until you scroll.
 */
object FastFetch {
    class Result(val html: String, val finalUrl: String, val status: Int)

    private val scriptRe = Regex("(?is)<script\\b[^>]*>.*?</script>|<link\\b[^>]*rel=[\"']?(?:preload|prefetch|stylesheet)[^>]*>|<iframe\\b[^>]*>.*?</iframe>|<noscript\\b[^>]*>.*?</noscript>")
    private val onAttrRe = Regex("(?i)\\son[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*')")

    /** Strips everything that could run, so the parser only reads. */
    fun inert(html: String): String = onAttrRe.replace(scriptRe.replace(html, ""), "")

    fun looksBlocked(html: String): Boolean =
        html.length < 3000 || Regex("(?i)validateCaptcha|Robot Check|Type the characters you see|api-services-support@amazon").containsMatchIn(html.take(60_000))

    fun looksSignedOut(finalUrl: String): Boolean = Sensitive.isSignIn(finalUrl)

    suspend fun get(url: String, userAgent: String, timeoutMs: Int = 12_000): Result = withContext(Dispatchers.IO) {
        var cur = url
        var status = 0
        var html = ""
        repeat(4) { hop ->
            val conn = (URL(cur).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("Upgrade-Insecure-Requests", "1")
                CookieManager.getInstance().getCookie(cur)?.let { setRequestProperty("Cookie", it) }
            }
            status = conn.responseCode
            conn.headerFields["Set-Cookie"]?.forEach { CookieManager.getInstance().setCookie(cur, it) }
            if (status in 300..399) {
                val loc = conn.getHeaderField("Location") ?: return@withContext Result("", cur, status)
                conn.disconnect()
                cur = URL(URL(cur), loc).toString()
                if (hop == 3) return@withContext Result("", cur, status)
                return@repeat
            }
            val stream = (if (status >= 400) conn.errorStream else conn.inputStream) ?: return@withContext Result("", cur, status)
            val raw = (if (conn.contentEncoding?.contains("gzip") == true) GZIPInputStream(stream) else stream)
            val buf = ByteArrayOutputStream()
            raw.use { it.copyTo(buf) }
            html = buf.toString("UTF-8")
            conn.disconnect()
            return@withContext Result(html, cur, status)
        }
        Result(html, cur, status)
    }
}
