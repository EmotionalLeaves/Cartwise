package com.emotionalleaves.cartwise.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.emotionalleaves.cartwise.Graph
import com.emotionalleaves.cartwise.engine.Sensitive
import com.emotionalleaves.cartwise.engine.WebEngine
import org.json.JSONArray
import org.json.JSONObject

enum class PageMode { RAW, CART, LOGIN }

/**
 * Amazon's own page, shown as-is. Used for sign-in, cart, checkout and as the fallback
 * whenever the simplified view can't read a page.
 *  - Sign-in, payment and checkout pages get no scripts or CSS (hard rule 3);
 *    only the WebView's own dark mode applies.
 *  - The cart page gets a CSS restyle and a pinned total.
 *  - You always tap Place Order yourself.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AmazonPage(
    url: String,
    mode: PageMode,
    modifier: Modifier = Modifier,
    onSignedIn: () -> Unit = {},
    onUrl: (String) -> Unit = {},
    onSubtotal: (String) -> Unit = {},
    webRef: MutableState<WebView?>? = null,
) {
    var progress by remember { mutableIntStateOf(0) }
    var canBack by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<WebView?>(null) }
    val signedInCb by rememberUpdatedState(onSignedIn)
    val urlCb by rememberUpdatedState(onUrl)
    val subtotalCb by rememberUpdatedState(onSubtotal)

    BackHandler(enabled = canBack) { view?.goBack() }

    Box(modifier.background(MaterialTheme.colorScheme.background)) {
        AndroidView(
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = WebEngine.cleanUa(settings.userAgentString)
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    try {
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
                        }
                    } catch (_: Throwable) {
                        // Older WebView: pages just stay light.
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(v: WebView?, newProgress: Int) {
                            progress = newProgress
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean {
                            val req = r ?: return false
                            if (!req.isForMainFrame) return false
                            val u = req.url
                            val scheme = u.scheme ?: ""
                            if (scheme == "http" || scheme == "https") {
                                if (Sensitive.isAmazonHost(u.host ?: "")) return false
                                openExternal(c, u.toString())
                                return true
                            }
                            if (scheme == "tel" || scheme == "mailto" || scheme == "sms") openExternal(c, u.toString())
                            return true
                        }

                        override fun onPageStarted(v: WebView?, u: String?, favicon: Bitmap?) {
                            canBack = v?.canGoBack() == true
                            if (u != null) urlCb(u)
                        }

                        override fun onPageFinished(v: WebView?, u: String?) {
                            val w = v ?: return
                            canBack = w.canGoBack()
                            CookieManager.getInstance().flush()
                            val cur = u ?: return
                            urlCb(cur)
                            if (mode == PageMode.LOGIN && Graph.shop.isSignedIn() && !Sensitive.isSignIn(cur)) signedInCb()
                            if (mode == PageMode.CART && Sensitive.isCart(cur)) CartPage.restyle(w) { subtotalCb(it) }
                        }
                    }
                    loadUrl(url)
                    view = this
                    webRef?.value = this
                }
            },
            onRelease = {
                it.stopLoading()
                it.destroy()
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
            )
        }
    }
}

/** Cart-page-only helpers. Both refuse to run on anything sensitive. */
object CartPage {
    private fun unquote(result: String?): String = try {
        JSONArray("[" + (result ?: "null") + "]").optString(0).let { if (it == "null") "" else it }
    } catch (_: Exception) {
        ""
    }

    private fun selectorsJson(field: String): String = JSONArray(Graph.selectors.list("cart", field)).toString()

    fun restyle(w: WebView, onSubtotal: (String) -> Unit) {
        if (!Sensitive.isCart(w.url)) return
        val css = Graph.selectors.string("cart", "css")
        val js = "(function(){try{var s=document.getElementById('cw-style');if(!s){s=document.createElement('style');s.id='cw-style';(document.head||document.documentElement).appendChild(s);}s.textContent=" +
            JSONObject.quote(css) + ";}catch(e){}" +
            "var sels=" + selectorsJson("subtotal") + ";for(var i=0;i<sels.length;i++){try{var e=document.querySelector(sels[i]);if(e&&e.textContent.trim())return e.textContent.replace(/\\s+/g,' ').trim();}catch(x){}}return '';})()"
        w.evaluateJavascript(js) { onSubtotal(unquote(it)) }
    }

    /** Taps Amazon's own "Proceed to checkout". Never Place Order, never Buy Now. */
    fun proceed(w: WebView) {
        if (!Sensitive.isCart(w.url)) return
        val js = "(function(){var bad=/place\\s*(your\\s*)?order|buy\\s*now|pay\\s*now|1-click/i;var sels=" + selectorsJson("proceed") +
            ";for(var i=0;i<sels.length;i++){try{var b=document.querySelector(sels[i]);if(b){var sig=[b.id,b.name,b.value,b.getAttribute('aria-label'),b.textContent].join(' ');if(bad.test(sig))return 'refused';b.click();return 'ok';}}catch(e){}}return 'missing';})()"
        w.evaluateJavascript(js, null)
    }
}
