package com.emotionalleaves.cartwise.ui

import com.emotionalleaves.cartwise.Graph
import com.emotionalleaves.cartwise.engine.Health
import com.emotionalleaves.cartwise.engine.WebEngine

/**
 * Text you can paste to an AI to fix a broken page-reading rule: what was found,
 * the current rules for that page kind, and a trimmed copy of the page's HTML.
 */
object PageReport {
    sealed class Out {
        data class Text(val text: String) : Out()
        data class Error(val message: String) : Out()
    }

    suspend fun build(kind: String, url: String, h: Health?): Out {
        return when (val r = Graph.engine.snapshot(url, kind)) {
            is WebEngine.Res.Ok -> {
                val sel = Graph.selectors.info.value
                val fields = h?.fields?.entries?.joinToString(", ") { "${it.key} ${(it.value * 100).toInt()}%" } ?: "not measured"
                Out.Text(
                    buildString {
                        appendLine("Cartwise page-reader report. Please update the \"$kind\" selectors in config/selectors.json so the fields below read correctly, and bump \"version\". Reply with the full updated \"$kind\" section as JSON.")
                        appendLine("Kind: $kind · items found: ${h?.count ?: "?"} · fields filled: $fields")
                        appendLine("Rules version: ${sel.version} · URL: ${r.url}")
                        if (kind == "orders" || kind == "lists") appendLine("(Note to self: this page includes my own order/list details. Check before sharing.)")
                        appendLine("Current $kind rules: " + (Graph.selectors.json().optJSONObject(kind)?.toString() ?: "{}"))
                        appendLine()
                        appendLine("Trimmed page HTML (" + r.json.optInt("length") + " chars before trimming):")
                        append(r.json.optString("html"))
                    },
                )
            }
            is WebEngine.Res.Fail -> Out.Error(r.message)
        }
    }
}
