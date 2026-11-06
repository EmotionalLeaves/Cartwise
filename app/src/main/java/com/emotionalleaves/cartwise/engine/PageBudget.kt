package com.emotionalleaves.cartwise.engine

import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Hard rule 5: background page loads happen at human pace.
 * At most [max] pages, and 3–8 seconds apart when [paced].
 */
class PageBudget(val max: Int, private val paced: Boolean, private val quickGapMs: Long = 600) {
    var used = 0
        private set

    val left: Int get() = (max - used).coerceAtLeast(0)

    suspend fun take(): Boolean {
        if (used >= max) return false
        if (used > 0) delay(if (paced) Random.nextLong(3_000, 8_001) else quickGapMs)
        used++
        return true
    }
}
