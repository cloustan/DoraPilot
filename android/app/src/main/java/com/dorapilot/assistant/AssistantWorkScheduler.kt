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
    private const val UNIQUE_HEARTBEAT_WORK = "dora_heartbeat"
    private const val UNIQUE_HEARTBEAT_POKE = "dora_heartbeat_poke"

    /** Periodic autonomy tick: runs any due automation jobs. */
    fun ensureHeartbeat(context: Context) {
        val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_HEARTBEAT_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /** Run the heartbeat soon (e.g. right after creating a job, or for testing). */
    fun pokeHeartbeat(context: Context, delaySeconds: Long = 0) {
        val request = OneTimeWorkRequestBuilder<HeartbeatWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_HEARTBEAT_POKE,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

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
