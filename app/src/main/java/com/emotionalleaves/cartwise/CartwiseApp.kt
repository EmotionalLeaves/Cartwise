package com.emotionalleaves.cartwise

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.emotionalleaves.cartwise.feed.Workers
import kotlinx.coroutines.launch

class CartwiseApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Graph.init(this)
        Graph.scope.launch { Graph.selectors.refreshFromRemote() }
        Workers.schedule(this)
    }

    /** Product photos never change at a given address, so keep them: big memory + disk cache, ignore no-cache headers. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .crossfade(120)
        .respectCacheHeaders(false)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("photos")).maxSizeBytes(200L * 1024 * 1024).build() }
        .apply { if (BuildConfig.DEBUG) logger(coil.util.DebugLogger()) }
        .build()
}
