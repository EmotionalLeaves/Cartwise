package com.emotionalleaves.cartwise.engine

import kotlinx.coroutines.flow.MutableStateFlow

/** Photo addresses that failed to load, so a missing-photo report can be shared from Debug. */
object PhotoLog {
    data class Entry(val url: String, val why: String, val at: Long = System.currentTimeMillis())
    val failures = MutableStateFlow<List<Entry>>(emptyList())
    fun failed(url: String, why: String) {
        failures.value = (listOf(Entry(url, why.take(120))) + failures.value).take(100)
    }
}
