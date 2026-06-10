package com.dorapilot.assistant

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Short bootstrap work that can run as expedited when triggered by user action
 * or lifecycle events, without entering a long-lived foreground-service loop.
 */
class AssistantBootstrapWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val scanner = SystemCapabilityScanner(applicationContext)
            val result = scanner.indexAllApps()
            Log.i(TAG, "Capability scan completed: $result")
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { error ->
                Log.w(TAG, "Capability scan failed", error)
                Result.retry()
            }
        )
    }

    companion object {
        private const val TAG = "AssistantBootstrapWorker"
    }
}
