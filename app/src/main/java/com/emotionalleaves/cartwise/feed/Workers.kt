package com.emotionalleaves.cartwise.feed

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.emotionalleaves.cartwise.Graph
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** Daily For You refresh (Wi-Fi only) and the user's pull-to-refresh. */
class FeedWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean("manual", false)
        val prefs = Graph.settings.current()
        if (!manual && !prefs.bgRefresh) return Result.success()
        Graph.taste.rebuildIfStale()
        Graph.feed.refresh(if (manual) inputData.getInt("pages", 20) else 40)
        return Result.success()
    }
}

/** Nightly rebuild of interest clusters. */
class ClusterWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Graph.taste.rebuild()
        return Result.success()
    }
}

object Workers {
    private fun minutesUntil(hour: Int): Long {
        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        return (next.timeInMillis - now.timeInMillis) / 60_000
    }

    fun schedule(c: Context) {
        val wm = WorkManager.getInstance(c)
        val feed = PeriodicWorkRequestBuilder<FeedWorker>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setInitialDelay(minutesUntil(6), TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork("feed_daily", ExistingPeriodicWorkPolicy.KEEP, feed)

        val clusters = PeriodicWorkRequestBuilder<ClusterWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(minutesUntil(3), TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork("clusters_nightly", ExistingPeriodicWorkPolicy.KEEP, clusters)
    }

    fun refreshNow(c: Context, pages: Int = 20) {
        val req = OneTimeWorkRequestBuilder<FeedWorker>()
            .setInputData(workDataOf("manual" to true, "pages" to pages))
            .build()
        WorkManager.getInstance(c).enqueueUniqueWork("feed_manual", ExistingWorkPolicy.KEEP, req)
    }
}
