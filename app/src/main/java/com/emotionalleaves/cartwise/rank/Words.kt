package com.emotionalleaves.cartwise.rank

import java.util.Locale

/** Small text helpers shared by ranking, clone grouping and the taste model. */
object Words {
    val STOP = setOf(
        "a", "an", "and", "or", "the", "of", "for", "with", "in", "on", "to", "by", "from", "at", "is", "it", "its",
        "this", "that", "your", "you", "our", "new", "set", "pcs", "piece", "pieces", "per", "each", "compatible",
        "pack", "count", "ct", "inch", "inches", "cm", "mm", "oz", "ml", "lb", "lbs", "size", "best", "premium",
        "upgraded", "version", "edition", "quality", "high", "great", "gift", "gifts", "men", "women", "kids",
    )
    val UNITS = setOf("oz", "ml", "lb", "lbs", "inch", "inches", "cm", "mm", "ft", "pack", "count", "ct", "pcs", "gb", "tb", "mah", "w", "v")

    private val splitRe = Regex("[^a-z0-9]+")

    fun tokens(s: String): List<String> =
        s.lowercase(Locale.US).split(splitRe).filter { it.length >= 2 || (it.isNotEmpty() && it.any { c -> c.isDigit() }) }

    fun contentTokens(s: String): List<String> = tokens(s).filter { it !in STOP }

    fun stem(t: String): String = if (t.length > 3 && t.endsWith("s") && !t.endsWith("ss")) t.dropLast(1) else t

    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        return inter.toDouble() / (a.size + b.size - inter)
    }

    /** Title tokens with the brand removed, for spotting the same product sold under different names. */
    fun cloneTokens(title: String, brand: String): Set<String> {
        val brandToks = tokens(brand).toSet()
        val toks = contentTokens(title).map { stem(it) }
        val rest = if (toks.isNotEmpty()) toks.drop(1) else toks
        return rest.filter { it !in brandToks }.take(18).toSet()
    }

    private val consumableRe = Regex(
        """\b(refills?|pods?|filters?|wipes|paper towels?|toilet paper|detergent|shampoo|conditioner|soap|toothpaste|vitamins?|supplements?|coffee|tea|snacks?|batteries|trash bags?|razors?|blades|cartridges?|ink|toner|litter|dog food|cat food|treats|diapers|lotion|deodorant|floss|sponges?|k-?cups?|capsules|tablets|gummies|protein|cleaner|tissues|napkins|paper plates|sunscreen|mouthwash|body wash)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun consumable(title: String): Boolean = consumableRe.containsMatchIn(title)
}
