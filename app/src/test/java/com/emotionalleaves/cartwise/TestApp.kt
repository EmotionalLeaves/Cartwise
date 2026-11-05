package com.emotionalleaves.cartwise

import android.app.Application

/** Used by Robolectric instead of CartwiseApp: no background workers, no network fetch at launch. */
class TestApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}
