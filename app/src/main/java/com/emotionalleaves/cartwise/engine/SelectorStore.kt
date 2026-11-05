package com.emotionalleaves.cartwise.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Page-reading rules. Fetched from the repo at launch, cached on the phone,
 * with a bundled copy as fallback. Edit config/selectors.json on GitHub and bump
 * "version" to fix a broken field without rebuilding the app.
 */
class SelectorStore(private val context: Context) {
    companion object {
        const val REMOTE_URL = "https://raw.githubusercontent.com/EmotionalLeaves/Cartwise/main/config/selectors.json"
        private const val CACHE = "selectors.json"
    }

    data class Info(val version: Int, val source: String, val fetchedAt: Long, val lastError: String?)

    @Volatile private var raw: String = "{}"
    @Volatile private var parsed: JSONObject = JSONObject()
    val info = MutableStateFlow(Info(0, "none", 0, null))

    init {
        val bundled = readAsset()
        val cached = File(context.filesDir, CACHE).takeIf { it.exists() }?.readText()
        val b = bundled?.let { parse(it) }
        val c = cached?.let { parse(it) }
        when {
            c != null && (b == null || c.optInt("version") >= b.optInt("version")) -> use(cached, c, "cached from GitHub")
            b != null -> use(bundled, b, "bundled with app")
        }
    }

    private fun readAsset(): String? = try {
        context.assets.open(CACHE).bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        null
    }

    private fun parse(s: String): JSONObject? = try {
        JSONObject(s).takeIf { it.has("search") && it.has("product") }
    } catch (_: Exception) {
        null
    }

    private fun use(text: String, obj: JSONObject, source: String) {
        raw = text
        parsed = obj
        info.value = Info(obj.optInt("version"), source, info.value.fetchedAt, info.value.lastError)
    }

    fun raw(): String = raw
    fun json(): JSONObject = parsed

    suspend fun refreshFromRemote(): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(REMOTE_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                useCaches = false
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val obj = parse(text) ?: throw IllegalStateException("Fetched rules are not valid JSON")
            val bundled = readAsset()?.let { parse(it) }
            File(context.filesDir, CACHE).writeText(text)
            if (bundled == null || obj.optInt("version") >= bundled.optInt("version")) {
                use(text, obj, "fetched from GitHub")
            }
            info.value = info.value.copy(fetchedAt = System.currentTimeMillis(), lastError = null)
            true
        } catch (e: Exception) {
            info.value = info.value.copy(lastError = e.message ?: e.toString())
            false
        }
    }

    fun url(name: String, vararg values: Pair<String, String>): String {
        var t = parsed.optJSONObject("urls")?.optString(name).orEmpty()
        if (t.isBlank()) t = DEFAULT_URLS[name] ?: "https://www.amazon.com/"
        for ((k, v) in values) t = t.replace("{$k}", v)
        return t
    }

    fun list(section: String, field: String): List<String> {
        val a: JSONArray = parsed.optJSONObject(section)?.optJSONArray(field) ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    }

    fun string(section: String, field: String): String = parsed.optJSONObject(section)?.optString(field).orEmpty()

    private val DEFAULT_URLS = mapOf(
        "home" to "https://www.amazon.com/",
        "signin" to "https://www.amazon.com/gp/css/homepage.html",
        "search" to "https://www.amazon.com/s?k={q}&page={page}",
        "searchNew" to "https://www.amazon.com/s?k={q}&s=date-desc-rank",
        "searchPopular" to "https://www.amazon.com/s?k={q}&s=review-rank",
        "product" to "https://www.amazon.com/dp/{asin}",
        "reviews" to "https://www.amazon.com/product-reviews/{asin}",
        "cart" to "https://www.amazon.com/gp/cart/view.html",
        "orders" to "https://www.amazon.com/your-orders/orders?timeFilter=year-{year}&startIndex={start}",
        "ordersWeb" to "https://www.amazon.com/gp/css/order-history",
        "lists" to "https://www.amazon.com/hz/wishlist/ls",
        "bestsellers" to "https://www.amazon.com/gp/bestsellers/{slug}",
        "keepa" to "https://keepa.com/#!product/1-{asin}",
        "camel" to "https://camelcamelcamel.com/product/{asin}",
    )
}
