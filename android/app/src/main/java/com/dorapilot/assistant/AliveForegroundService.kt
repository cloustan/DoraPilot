package com.dorapilot.assistant

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * Legacy wrapper retained for compatibility with existing call sites.
 * Startup is now delegated to WorkManager to avoid foreground-service limits.
 */
class AliveForegroundService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AssistantWorkScheduler.enqueueBootstrap(this)
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun ensureRunning(context: Context) {
            AssistantWorkScheduler.enqueueBootstrap(context)
        }
    }
}
