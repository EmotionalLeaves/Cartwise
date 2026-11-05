package com.emotionalleaves.cartwise.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The one hidden WebView that talks to Amazon. It loads real amazon.com mobile pages
 * under your own login (shared cookies), runs the bundled page reader, and hands back JSON.
 * It never runs anything on sign-in, payment or checkout pages.
 */
class WebEngine(private val app: Context, private val selectors: SelectorStore) {

    sealed class Res {
        data class Ok(val json: JSONObject, val url: String) : Res()
        data class Fail(val reason: Reason, val message: String, val url: String? = null) : Res()
    }

    data class LogEntry(val time: Long, val kind: String, val url: String, val ms: Long, val result: String)

    companion object {
        fun cleanUa(ua: String?): String? = ua?.replace("; wv)", ")")?.replace(Regex("""\s?Version/\d+\.\d+"""), "")
        private const val VIEW_W = 1080
        private const val VIEW_H = 2340
    }

    val log = MutableStateFlow<List<LogEntry>>(emptyList())

    private val mutex = Mutex()
    private var web: WebView? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    @Volatile private var pageDone: CompletableDeferred<String?>? = null
    @Volatile private var awaitingStart = false
    @Volatile var currentUrl: String? = null
        private set

    private val extractorJs: String by lazy {
        app.assets.open("extractor.js").bufferedReader().use { it.readText() }
    }

    @MainThread
    fun view(): WebView = web ?: create().also { web = it }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun create(): WebView {
        val w = WebView(app)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.blockNetworkImage = true
        w.settings.userAgentString = cleanUa(w.settings.userAgentString)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.addJavascriptInterface(Bridge(), "CartwiseBridge")
        w.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                awaitingStart = false
                currentUrl = url
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                currentUrl = url
                if (!awaitingStart) pageDone?.complete(null)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    pageDone?.complete("Couldn't reach Amazon (" + (error?.description ?: "network error") + ")")
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return true
                val scheme = u.scheme ?: ""
                // The hidden engine only ever visits Amazon.
                return !((scheme == "https" || scheme == "http") && Sensitive.isAmazonHost(u.host ?: ""))
            }
        }
        w.setOnTouchListener { _, _ -> true }
        w.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        w.isFocusable = false
        w.layout(0, 0, VIEW_W, VIEW_H)
        return w
    }

    /** Puts the engine behind the app's UI so Chromium treats it as a live page. Never visible. */
    @MainThread
    fun attach(parent: FrameLayout) {
        val w = view()
        (w.parent as? ViewGroup)?.removeView(w)
        w.alpha = 0f
        parent.addView(w, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    @MainThread
    fun detach() {
        val w = web ?: return
        (w.parent as? ViewGroup)?.removeView(w)
        w.layout(0, 0, VIEW_W, VIEW_H)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun post(id: String, json: String) {
            pending.remove(id)?.complete(json)
        }
    }

    private fun online(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Load [url] (or stay on the current page when null) and run the page reader of [kind].
     * Calls are serialized: one page at a time.
     */
    suspend fun run(url: String?, kind: String, arg: String = "", timeoutMs: Long = 30_000): Res =
        mutex.withLock { withContext(Dispatchers.Main) { runLogged(url, kind, arg, timeoutMs) } }

    /** Runs several page steps back to back without another caller navigating the engine in between. */
    interface Session {
        suspend fun run(url: String?, kind: String, arg: String = ""): Res
    }

    suspend fun <T> session(block: suspend (Session) -> T): T = mutex.withLock {
        withContext(Dispatchers.Main) {
            block(object : Session {
                override suspend fun run(url: String?, kind: String, arg: String): Res = runLogged(url, kind, arg, 30_000)
            })
        }
    }

    private suspend fun runLogged(url: String?, kind: String, arg: String, timeoutMs: Long): Res {
        val started = SystemClock.elapsedRealtime()
        val res = try {
            runInner(view(), url, kind, arg, timeoutMs)
        } catch (e: TimeoutCancellationException) {
            Res.Fail(Reason.TIMEOUT, "Amazon took too long to answer", url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Res.Fail(Reason.ERROR, e.message ?: e.toString(), url)
        }
        val ms = SystemClock.elapsedRealtime() - started
        val summary = when (res) {
            is Res.Ok -> "ok"
            is Res.Fail -> res.reason.name.lowercase() + ": " + res.message
        }
        val entry = LogEntry(System.currentTimeMillis(), kind, url ?: (currentUrl ?: ""), ms, summary)
        log.value = (listOf(entry) + log.value).take(60)
        return res
    }

    /** Page kinds that read better from Amazon's desktop layout (configurable in selectors.json). */
    private fun wantsDesktop(kind: String): Boolean {
        val list = selectors.list("engine", "desktopKinds")
        return if (list.isEmpty()) kind == "orders" else kind in list
    }

    private fun desktopUa(mobile: String?): String {
        val chrome = Regex("""Chrome/[\d.]+""").find(mobile ?: "")?.value ?: "Chrome/130.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
    }

    /** Last page address read for each kind, so Debug can share that page's HTML. */
    val lastUrls = MutableStateFlow<Map<String, String>>(emptyMap())

    private suspend fun runInner(w: WebView, url: String?, kind: String, arg: String, timeoutMs: Long): Res {
        if (!online()) return Res.Fail(Reason.OFFLINE, "You're offline", url)
        val mobileUa = w.settings.userAgentString
        val desktop = url != null && wantsDesktop(kind)
        try {
            if (url != null) {
                if (Sensitive.isSensitive(url)) return Res.Fail(Reason.SENSITIVE, "That page is handled on Amazon's own screen", url)
                if (desktop) w.settings.userAgentString = desktopUa(mobileUa)
                val done = CompletableDeferred<String?>()
                awaitingStart = true
                pageDone = done
                w.loadUrl(url)
                val err = withTimeout(timeoutMs) { done.await() }
                if (err != null) return Res.Fail(Reason.OFFLINE, err, url)
                delay(700)
            }
            // A page can redirect itself while the reader waits; if the answer never comes, read the new page once more.
            var attempt = 0
            while (true) {
                val cur = w.url ?: currentUrl ?: ""
                if (Sensitive.isSignIn(cur)) return Res.Fail(Reason.SIGNED_OUT, "Amazon wants you to sign in", cur)
                if (Sensitive.isSensitive(cur)) return Res.Fail(Reason.SENSITIVE, "That page is handled on Amazon's own screen", cur)
                val out = extract(w, kind, arg)
                if (out == null) {
                    if (attempt++ >= 1) return Res.Fail(Reason.TIMEOUT, "The page didn't finish loading", cur)
                    delay(1_500)
                    continue
                }
                val json = JSONObject(out)
                val finalUrl = json.optString("__url", cur)
                if (json.optBoolean("__robot")) return Res.Fail(Reason.CAPTCHA, "Amazon wants to check you're human", finalUrl)
                if (json.has("error")) return Res.Fail(Reason.ERROR, json.optString("error"), finalUrl)
                if (kind != "snapshot") lastUrls.value = lastUrls.value + (kind to finalUrl)
                return Res.Ok(json, finalUrl)
            }
        } finally {
            if (desktop) w.settings.userAgentString = mobileUa
        }
    }

    private suspend fun extract(w: WebView, kind: String, arg: String): String? {
        val id = UUID.randomUUID().toString()
        val d = CompletableDeferred<String>()
        pending[id] = d
        val script = extractorJs + "\n;window.__cartwise(" + JSONObject.quote(kind) + "," + selectors.raw() + "," +
            JSONObject.quote(id) + "," + JSONObject.quote(arg) + ");"
        w.evaluateJavascript(script, null)
        return try {
            withTimeoutOrNull(15_000) { d.await() }
        } finally {
            pending.remove(id)
        }
    }

    /** Trimmed HTML of a page (scripts and styles removed), for fixing selectors. Never for sensitive pages. */
    suspend fun snapshot(url: String, kind: String, arg: String = "{}"): Res {
        if (Sensitive.isSensitive(url)) return Res.Fail(Reason.SENSITIVE, "Not allowed on this page", url)
        return mutex.withLock {
            withContext(Dispatchers.Main) {
                val w = view()
                val mobileUa = w.settings.userAgentString
                val desktop = wantsDesktop(kind)
                try {
                    if (desktop) w.settings.userAgentString = desktopUa(mobileUa)
                    runLogged(url, "snapshot", arg, 30_000)
                } finally {
                    if (desktop) w.settings.userAgentString = mobileUa
                }
            }
        }
    }

    fun isSignedIn(): Boolean {
        val c = CookieManager.getInstance().getCookie("https://www.amazon.com") ?: return false
        return c.contains("at-main=") || c.contains("x-main=") || c.contains("sess-at-main=")
    }

    fun signOut() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        android.webkit.WebStorage.getInstance().deleteAllData()
    }
}
