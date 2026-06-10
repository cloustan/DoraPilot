package com.dorapilot.assistant

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object AssistantWorkScheduler {
    private const val UNIQUE_BOOTSTRAP_WORK = "dora_assistant_bootstrap"
    private const val UNIQUE_PERIODIC_SCAN_WORK = "dora_capability_periodic_scan"

    fun enqueueBootstrap(context: Context) {
        val request = OneTimeWorkRequestBuilder<AssistantBootstrapWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_BOOTSTRAP_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun ensurePeriodicCapabilityScan(context: Context) {
        val request = PeriodicWorkRequestBuilder<AssistantBootstrapWorker>(6, TimeUnit.HOURS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_SCAN_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
