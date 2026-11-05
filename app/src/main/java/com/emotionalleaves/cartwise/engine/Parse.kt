package com.emotionalleaves.cartwise.engine

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Turns the raw text Amazon shows into numbers. */
object Parse {
    private const val DAY = 86_400_000L

    private val priceRe = Regex("""\$\s?(\d[\d,]*(?:\.\d{1,2})?)""")
    fun price(s: String?): Double? {
        if (s.isNullOrBlank()) return null
        val m = priceRe.find(s) ?: return null
        return m.groupValues[1].replace(",", "").toDoubleOrNull()
    }

    private val unitRe = Regex("""\$\s?([\d,]*\.?\d+)\s*/\s*(\d+(?:\.\d+)?)?\s*([A-Za-z][A-Za-z .]{0,22})""")
    fun unitPrice(s: String?): UnitPrice? {
        if (s.isNullOrBlank()) return null
        val m = unitRe.find(s) ?: return null
        val v = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val qty = m.groupValues[2].toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
        val (unit, factor) = normUnit(m.groupValues[3]) ?: return null
        return UnitPrice(v / qty / factor, unit)
    }

    /** Canonical unit plus how many canonical units are in one of the given unit. */
    private fun normUnit(raw: String): Pair<String, Double>? {
        var u = raw.lowercase(Locale.US).trim().trimEnd('.', ' ')
        if (u.length in 2..4 && u.endsWith("s")) u = u.dropLast(1)
        return when {
            u.startsWith("fl oz") || u.startsWith("fluid ounce") || u.startsWith("fl. oz") -> "fl oz" to 1.0
            u == "oz" || u.startsWith("ounce") -> "oz" to 1.0
            u == "lb" || u.startsWith("pound") -> "oz" to 16.0
            u == "g" || u.startsWith("gram") -> "oz" to 0.035274
            u == "kg" || u.startsWith("kilogram") -> "oz" to 35.274
            u == "ml" || u.startsWith("milliliter") || u.startsWith("millilitre") -> "fl oz" to 0.033814
            u == "l" || u.startsWith("liter") || u.startsWith("litre") -> "fl oz" to 33.814
            u.startsWith("gallon") -> "fl oz" to 128.0
            u.startsWith("quart") -> "fl oz" to 32.0
            u == "gb" || u.startsWith("gigabyte") -> "GB" to 1.0
            u == "tb" || u.startsWith("terabyte") -> "GB" to 1000.0
            u == "ft" || u.startsWith("foot") || u.startsWith("feet") -> "ft" to 1.0
            u.startsWith("yard") -> "ft" to 3.0
            u.startsWith("inch") -> "ft" to 1.0 / 12.0
            u.startsWith("count") || u == "ct" || u.startsWith("each") || u.startsWith("piece") ||
                u.startsWith("item") || u.startsWith("unit") || u.startsWith("sheet") || u.startsWith("load") ||
                u.startsWith("pod") || u.startsWith("bag") || u.startsWith("capsule") || u.startsWith("tablet") ||
                u.startsWith("wipe") || u.startsWith("roll") || u.startsWith("pair") || u.startsWith("serving") ||
                u.startsWith("pack") -> "count" to 1.0
            else -> null
        }
    }

    private val ratingRe = Regex("""(\d(?:[.,]\d)?)\s*out of\s*5""")
    private val bareRatingRe = Regex("""^\s*(\d\.\d)\b""")
    fun rating(s: String?): Double? {
        if (s.isNullOrBlank()) return null
        val m = ratingRe.find(s) ?: bareRatingRe.find(s) ?: return null
        return m.groupValues[1].replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..5.0 }
    }

    private val countRe = Regex("""(\d[\d,]*(?:\.\d+)?)\s*([KkMm])?""")
    fun reviews(s: String?): Int {
        if (s.isNullOrBlank() || s.contains("out of 5")) return 0
        val m = countRe.find(s) ?: return 0
        val n = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return 0
        val mult = when (m.groupValues[2].lowercase()) { "k" -> 1000.0; "m" -> 1_000_000.0; else -> 1.0 }
        return (n * mult).toInt()
    }

    fun int(s: String?): Int? = s?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() && it.length < 7 }?.toIntOrNull()

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val monthDayRe = Regex("""\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+(\d{1,2})\b""")
    private val weekdays = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
    private val weekdayRe = Regex("""\b(sunday|monday|tuesday|wednesday|thursday|friday|saturday)\b""")

    private fun startOfDay(c: Calendar): Calendar = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    /** Days until arrival, from text like "FREE delivery Fri, Nov 7" or "Tomorrow". */
    fun deliveryDays(s: String?, now: Calendar = Calendar.getInstance()): Int? {
        if (s.isNullOrBlank()) return null
        val t = s.lowercase(Locale.US)
        if ("today" in t || "same-day" in t || "same day" in t || ("within" in t && "hour" in t)) return 0
        if ("tomorrow" in t || "overnight" in t) return 1
        val today = startOfDay(now)
        val m = monthDayRe.find(t)
        if (m != null) {
            val mon = months.indexOf(m.groupValues[1])
            val day = m.groupValues[2].toIntOrNull() ?: return null
            if (mon < 0 || day !in 1..31) return null
            val c = (today.clone() as Calendar).apply { set(Calendar.MONTH, mon); set(Calendar.DAY_OF_MONTH, day) }
            if (c.timeInMillis < today.timeInMillis - 2 * DAY) c.add(Calendar.YEAR, 1)
            return ((c.timeInMillis - today.timeInMillis) / DAY).toInt().coerceAtLeast(0)
        }
        val w = weekdayRe.find(t)
        if (w != null) {
            val target = weekdays.indexOf(w.groupValues[1]) + 1 // Calendar.SUNDAY == 1
            val cur = today.get(Calendar.DAY_OF_WEEK)
            var diff = (target - cur + 7) % 7
            if (diff == 0) diff = 7
            return diff
        }
        return null
    }

    fun deliveryScore(days: Int?): Double = when {
        days == null -> 0.3
        days <= 1 -> 1.0
        days == 2 -> 0.8
        days == 3 -> 0.65
        days == 4 -> 0.5
        days == 5 -> 0.35
        days == 6 -> 0.2
        else -> 0.0
    }

    fun orderDate(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        for (p in listOf("MMMM d, yyyy", "MMM d, yyyy", "MMM. d, yyyy")) {
            try {
                val d = SimpleDateFormat(p, Locale.US).apply { isLenient = true }.parse(s.trim())
                if (d != null) return d.time
            } catch (_: Exception) {
            }
        }
        return 0
    }

    fun cleanBrand(s: String?): String {
        if (s.isNullOrBlank()) return ""
        return s.replace(Regex("(?i)^visit the\\s+"), "")
            .replace(Regex("(?i)\\s+store$"), "")
            .replace(Regex("(?i)^brand:\\s*"), "")
            .replace(Regex("(?i)^by\\s+"), "")
            .trim()
    }

    /** Sold and shipped by Amazon = 1, fulfilled by Amazon = 0.8, third-party shipped = 0.5. */
    fun sellerScore(soldBy: String, shipsFrom: String): Double {
        val s = soldBy.lowercase(Locale.US)
        val f = shipsFrom.lowercase(Locale.US)
        if (s.contains("ships from and sold by amazon")) return 1.0
        val soldAmazon = s.contains("amazon") && !s.contains("fulfilled by amazon")
        val shipAmazon = f.contains("amazon") || s.contains("fulfilled by amazon")
        return when {
            soldAmazon && (f.isBlank() || f.contains("amazon")) -> 1.0
            shipAmazon -> 0.8
            else -> 0.5
        }
    }

    private val imgIdRe = Regex("""/images/I/([A-Za-z0-9+%\-]+?)\.""")
    fun imageId(url: String): String = imgIdRe.find(url)?.groupValues?.get(1) ?: ""

    /** Whole percent off; ignores tiny or implausible gaps (under 3% or over 90%). */
    fun discountPct(price: Double?, list: Double?): Int {
        if (price == null || list == null || list <= 0 || price <= 0 || list <= price) return 0
        val pct = Math.round((1 - price / list) * 100).toInt()
        return if (pct in 3..90) pct else 0
    }

    fun bayes(rating: Double?, reviews: Int, prior: Double, k: Double = 200.0): Double {
        if (rating == null) return prior
        return (reviews * rating + k * prior) / (reviews + k)
    }
}
