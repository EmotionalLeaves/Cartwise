package com.emotionalleaves.cartwise.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class Settings(private val context: Context) {

    data class Prefs(
        val hideSponsored: Boolean = true,
        val preset: String = "BEST",
        val personalize: Boolean = false,
        val bgRefresh: Boolean = true,
        val theme: String = "system",
        val recent: List<String> = emptyList(),
        val saved: List<String> = emptyList(),
        val quizDone: Boolean = false,
        val lastCluster: Long = 0,
        val lastFeed: Long = 0,
        val ordersImported: Long = 0,
    )

    private object K {
        val hideSponsored = booleanPreferencesKey("hide_sponsored")
        val preset = stringPreferencesKey("preset")
        val personalize = booleanPreferencesKey("personalize")
        val bgRefresh = booleanPreferencesKey("bg_refresh")
        val theme = stringPreferencesKey("theme")
        val recent = stringPreferencesKey("recent")
        val saved = stringPreferencesKey("saved")
        val quizDone = booleanPreferencesKey("quiz_done")
        val lastCluster = longPreferencesKey("last_cluster")
        val lastFeed = longPreferencesKey("last_feed")
        val ordersImported = longPreferencesKey("orders_imported")
    }

    private val sep = "\u0001"
    private fun split(s: String?): List<String> = s?.split(sep)?.filter { it.isNotBlank() } ?: emptyList()

    val prefs: Flow<Prefs> = context.dataStore.data.map { p ->
        Prefs(
            hideSponsored = p[K.hideSponsored] ?: true,
            preset = p[K.preset] ?: "BEST",
            personalize = p[K.personalize] ?: false,
            bgRefresh = p[K.bgRefresh] ?: true,
            theme = p[K.theme] ?: "system",
            recent = split(p[K.recent]),
            saved = split(p[K.saved]),
            quizDone = p[K.quizDone] ?: false,
            lastCluster = p[K.lastCluster] ?: 0L,
            lastFeed = p[K.lastFeed] ?: 0L,
            ordersImported = p[K.ordersImported] ?: 0L,
        )
    }

    suspend fun current(): Prefs = prefs.first()

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    suspend fun setHideSponsored(v: Boolean) = put(K.hideSponsored, v)
    suspend fun setPreset(v: String) = put(K.preset, v)
    suspend fun setPersonalize(v: Boolean) = put(K.personalize, v)
    suspend fun setBgRefresh(v: Boolean) = put(K.bgRefresh, v)
    suspend fun setTheme(v: String) = put(K.theme, v)
    suspend fun setQuizDone(v: Boolean) = put(K.quizDone, v)
    suspend fun setLastCluster(v: Long) = put(K.lastCluster, v)
    suspend fun setLastFeed(v: Long) = put(K.lastFeed, v)
    suspend fun setOrdersImported(v: Long) = put(K.ordersImported, v)

    suspend fun addRecent(q: String) {
        val t = q.trim(); if (t.isBlank()) return
        context.dataStore.edit {
            val list = split(it[K.recent]).filterNot { s -> s.equals(t, ignoreCase = true) }
            it[K.recent] = (listOf(t) + list).take(12).joinToString(sep)
        }
    }

    suspend fun removeRecent(q: String) {
        context.dataStore.edit { it[K.recent] = split(it[K.recent]).filterNot { s -> s == q }.joinToString(sep) }
    }

    suspend fun clearRecent() = put(K.recent, "")

    suspend fun toggleSaved(q: String) {
        val t = q.trim(); if (t.isBlank()) return
        context.dataStore.edit {
            val list = split(it[K.saved])
            it[K.saved] = (if (list.any { s -> s.equals(t, true) }) list.filterNot { s -> s.equals(t, true) } else listOf(t) + list)
                .take(30).joinToString(sep)
        }
    }
}
