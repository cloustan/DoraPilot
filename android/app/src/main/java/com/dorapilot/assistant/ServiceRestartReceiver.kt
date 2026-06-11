package com.dorapilot.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            AssistantWorkScheduler.enqueueBootstrap(context)
            AssistantWorkScheduler.ensureHeartbeat(context)
        }
    }
}
