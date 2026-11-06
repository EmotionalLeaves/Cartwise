package com.emotionalleaves.cartwise.engine

import java.util.Locale

object Fmt {
    fun money(v: Double?): String = if (v == null) "" else "$" + String.format(Locale.US, "%,.2f", v)

    fun unit(v: Double): String = when {
        v >= 0.1 -> "$" + String.format(Locale.US, "%.2f", v)
        else -> String.format(Locale.US, "%.1f", v * 100) + "¢"
    }

    fun count(n: Int): String = when {
        n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
        n >= 10_000 -> "${n / 1000}K"
        n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1000.0)
        else -> n.toString()
    }

    fun stars(r: Double?): String = if (r == null) "" else String.format(Locale.US, "%.1f", r)

    fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
        if (ms <= 0) return "never"
        val d = (now - ms) / 1000
        return when {
            d < 60 -> "just now"
            d < 3600 -> "${d / 60} min ago"
            d < 86400 -> "${d / 3600} h ago"
            else -> "${d / 86400} days ago"
        }
    }

    fun delivery(days: Int?, raw: String): String = when (days) {
        null -> raw.replace(Regex("(?i)^free\\s+"), "").take(48)
        0 -> "Arrives today"
        1 -> "Arrives tomorrow"
        else -> "Arrives in $days days"
    }

    private val sizeToken = Regex("""\._[^/]*?_\.(jpe?g|png|webp)$""", RegexOption.IGNORE_CASE)
    private val plainExt = Regex("""\.(jpe?g|png|webp)$""", RegexOption.IGNORE_CASE)

    /** Asks Amazon's image server for a sharp square photo of about [px] pixels. */
    fun image(url: String, px: Int = 500): String {
        if (!url.contains("/images/I/")) return url
        // Repairs addresses cut off at a comma by older builds ("…/I/61abc._AC_SR525"): rebuild from the photo ID.
        if (!plainExt.containsMatchIn(url)) {
            val id = Parse.imageId(url)
            return if (id.isNotEmpty()) "https://m.media-amazon.com/images/I/$id._AC_SL${px}_.jpg" else url
        }
        return when {
            sizeToken.containsMatchIn(url) -> sizeToken.replace(url) { "._AC_SL${px}_." + it.groupValues[1] }
            plainExt.containsMatchIn(url) -> plainExt.replace(url) { "._AC_SL${px}_." + it.groupValues[1] }
            else -> url
        }
    }

    fun short(title: String, n: Int = 40): String {
        val t = title.trim()
        if (t.length <= n) return t
        val cut = t.take(n)
        val sp = cut.lastIndexOf(' ')
        return (if (sp > n / 2) cut.take(sp) else cut).trimEnd(',', ' ', '-') + "…"
    }
}
