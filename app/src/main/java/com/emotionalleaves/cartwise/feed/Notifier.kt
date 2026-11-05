package com.emotionalleaves.cartwise.feed

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.emotionalleaves.cartwise.MainActivity
import com.emotionalleaves.cartwise.R
import com.emotionalleaves.cartwise.engine.Fmt

object Notifier {
    private const val CHANNEL = "prices"

    fun canNotify(c: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun priceHit(c: Context, asin: String, title: String, price: Double) {
        if (!canNotify(c)) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Price alerts", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(c, MainActivity::class.java)
            .putExtra("asin", asin)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(c, asin.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Target price reached")
            .setContentText(Fmt.short(title, 60) + " is now " + Fmt.money(price))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(asin.hashCode(), n)
        } catch (_: SecurityException) {
        }
    }
}
