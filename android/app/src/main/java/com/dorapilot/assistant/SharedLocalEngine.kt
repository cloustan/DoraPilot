package com.dorapilot.assistant

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Process-wide pool for the on-device GenAI engine, shared by every local-AI
 * consumer (assistant overlay, main app chat, local agent loop, notification
 * summarizer). One engine instance is handed out everywhere, so the ~1GB model
 * loads once and message bursts / chat turns reuse it.
 *
 * Every [get] marks the engine as used; after [IDLE_TTL_MS] without use the
 * model weights are unloaded via resetGenAiRuntime() so the memory is not held
 * by a background process. The instance itself stays valid - the next infer
 * transparently reloads the weights.
 */
object SharedLocalEngine {
    private const val TAG = "SharedLocalEngine"
    private const val IDLE_TTL_MS = 2 * 60_000L

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "dora-local-engine-pool").apply { isDaemon = true }
    }

    @Volatile
    private var engine: LocalOnnxRuntimeEngine? = null
    @Volatile
    private var lastUsed = 0L

    @Synchronized
    fun get(context: Context): LocalOnnxRuntimeEngine {
        val instance = engine ?: LocalOnnxRuntimeEngine(
            context.applicationContext,
            BackendConfig.load()
        ).also { engine = it }
        lastUsed = System.currentTimeMillis()
        scheduler.schedule(::unloadIfIdle, IDLE_TTL_MS + 1_000L, TimeUnit.MILLISECONDS)
        return instance
    }

    @Synchronized
    private fun unloadIfIdle() {
        val instance = engine ?: return
        if (System.currentTimeMillis() - lastUsed < IDLE_TTL_MS) return
        runCatching { instance.resetGenAiRuntime() }
        Log.i(TAG, "Unloaded idle local model")
    }
}
