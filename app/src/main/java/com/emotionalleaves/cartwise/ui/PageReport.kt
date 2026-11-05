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

    private const val MAX = 15_000

    /** Health field names → the reader's hint names for that page kind. */
    private fun hintName(kind: String, field: String): String = when {
        kind == "search" && field == "title" -> "title_s"
        kind == "search" && field == "price" -> "price_s"
        kind == "search" && field == "unit price" -> "unit"
        kind == "orders" && field != "date" -> "items"
        else -> field
    }

    suspend fun build(kind: String, url: String, h: Health?): Out {
        val missing = h?.fields?.filter { it.value < 0.6 && it.key != "list price" && it.key != "unit price" }?.keys?.map { hintName(kind, it) }?.distinct()
            ?: listOf("title", "price", "category")
        val arg = org.json.JSONObject().put("missing", org.json.JSONArray(if (missing.isEmpty() && h?.count == 0) listOf("items") else missing)).put("max", MAX).toString()
        return when (val r = Graph.engine.snapshot(url, kind, arg)) {
            is WebEngine.Res.Ok -> {
                val sel = Graph.selectors.info.value
                val fields = h?.fields?.entries?.joinToString(", ") { "${it.key} ${(it.value * 100).toInt()}%" } ?: "not measured"
                Out.Text(
                    buildString(MAX + 4000) {
                        appendLine("Cartwise page-reader report. Please update the \"$kind\" selectors in config/selectors.json so the fields below read correctly, and bump \"version\". Reply with the full updated \"$kind\" section as JSON.")
                        appendLine("Kind: $kind · items found: ${h?.count ?: "?"} · fields filled: $fields")
                        if (!h?.notes.isNullOrEmpty()) appendLine("Found another way (works, but a rule could be better): " + h!!.notes.joinToString(", "))
                        appendLine("Rules version: ${sel.version} · URL: ${r.url}")
                        if (kind == "orders" || kind == "lists") appendLine("(Note to self: this page includes my own order/list details. Check before sharing.)")
                        appendLine("Current $kind rules: " + (Graph.selectors.json().optJSONObject(kind)?.toString() ?: "{}"))
                        appendLine()
                        appendLine("Targeted page extract (full page was " + r.json.optInt("length") + " chars):")
                        append(r.json.optString("html"))
                    },
                )
            }
            is WebEngine.Res.Fail -> Out.Error(r.message)
        }
    }
}
