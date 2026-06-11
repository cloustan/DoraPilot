package com.dorapilot.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Live push notifications for agent/skill progress: an updating notification
 * that shows each step as it happens, then resolves to the final result.
 */
object ProgressNotifier {
    private const val CHANNEL_ID = "dora_skills"
    const val BASE_ID = 48000

    fun step(context: Context, id: Int, title: String, step: String) {
        val nm = ensureChannel(context) ?: return
        val n = builder(context)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(step)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        nm.notify(BASE_ID + id, n)
    }

    fun final(context: Context, id: Int, title: String, result: String) {
        val nm = ensureChannel(context) ?: return
        val n = builder(context)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(result.take(120))
            .setStyle(android.app.Notification.BigTextStyle().bigText(result.take(1500)))
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        nm.notify(BASE_ID + id, n)
    }

    private fun builder(context: Context): android.app.Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") android.app.Notification.Builder(context)
        }

    private fun ensureChannel(context: Context): NotificationManager? {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Dora skills", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return nm
    }
}
