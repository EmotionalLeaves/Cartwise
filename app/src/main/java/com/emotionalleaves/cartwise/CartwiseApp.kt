package com.emotionalleaves.cartwise

import android.app.Application
import com.emotionalleaves.cartwise.feed.Workers
import kotlinx.coroutines.launch

class CartwiseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Graph.init(this)
        Graph.scope.launch { Graph.selectors.refreshFromRemote() }
        Workers.schedule(this)
    }
}
