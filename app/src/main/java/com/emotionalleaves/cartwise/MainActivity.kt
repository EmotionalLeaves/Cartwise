package com.emotionalleaves.cartwise

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emotionalleaves.cartwise.ui.AppNav
import com.emotionalleaves.cartwise.ui.CartwiseTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        // The hidden page-reading engine sits behind the UI, fully transparent and untouchable.
        Graph.engine.attach(root)
        val compose = ComposeView(this).apply {
            setContent {
                val prefs by Graph.settings.prefs.collectAsStateWithLifecycle(initialValue = null)
                CartwiseTheme(prefs?.theme ?: "system") { AppNav() }
            }
        }
        root.addView(compose, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        i?.getStringExtra("asin")?.takeIf { it.isNotBlank() }?.let { Graph.pendingAsin.value = it }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        Graph.engine.detach()
        super.onDestroy()
    }
}
