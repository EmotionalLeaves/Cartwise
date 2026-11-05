package com.emotionalleaves.cartwise.engine

import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Hard rule 5: background page loads happen at human pace.
 * At most [max] pages, and 3–8 seconds apart when [paced].
 */
class PageBudget(val max: Int, private val paced: Boolean) {
    var used = 0
        private set

    val left: Int get() = (max - used).coerceAtLeast(0)

    suspend fun take(): Boolean {
        if (used >= max) return false
        if (paced && used > 0) delay(Random.nextLong(3_000, 8_001))
        used++
        return true
    }
}
