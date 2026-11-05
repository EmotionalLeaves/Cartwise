package com.emotionalleaves.cartwise.engine

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * Requests Cartwise never needs: ads, trackers, telemetry, web fonts and video.
 * Skipping them is most of what makes pages read fast. Never applied on
 * sign-in, payment or checkout pages.
 */
object NetBlock {
    private val hosts = listOf(
        "amazon-adsystem.com", "doubleclick.net", "googlesyndication.com", "google-analytics.com",
        "googletagmanager.com", "fls-na.amazon.com", "fls-eu.amazon.com", "fls-fe.amazon.com",
        "unagi.amazon.com", "unagi-na.amazon.com", "device-metrics-us.amazon.com", "device-metrics-us-2.amazon.com",
        "aan.amazon.com", "mads.amazon.com", "aax-us-east.amazon-adsystem.com", "c.amazon-adsystem.com",
        "s.amazon-adsystem.com", "ara.paa-reporting-advertising.amazon", "dt.adsafeprotected.com",
    )
    private val paths = listOf(
        "/uedata", "/1/batch/1/", "/gp/csm", "/ah/ajax/counter", "/1/oc-csi", "/rd/uedata", "/api/1/batch",
        "/vse-vms", "/af/", "/csm/", "/remote-weblab-triggers", "/gp/aod/",
    )
    private val exts = listOf(".woff2", ".woff", ".ttf", ".otf", ".mp4", ".m3u8", ".webm", ".ts", ".mp3")

    fun blocks(host: String, path: String): Boolean {
        val h = host.lowercase(Locale.US)
        if (hosts.any { h == it || h.endsWith(".$it") }) return true
        val p = path.lowercase(Locale.US)
        if (paths.any { p.contains(it) }) return true
        return exts.any { p.endsWith(it) }
    }

    fun empty(): WebResourceResponse = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}
