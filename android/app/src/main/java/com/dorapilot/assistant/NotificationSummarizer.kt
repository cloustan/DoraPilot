package com.dorapilot.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * On-device AI notification summaries (a private, no-API-key take on "Sum Up"):
 * when a long message notification arrives, the local model summarizes it into a
 * single line and posts that as Dora's notification, optionally replacing the
 * original. Everything runs on-device via [LocalOnnxRuntimeEngine] — nothing is
 * sent to any server, and no API key is needed.
 *
 * Gated by the user's [ContextSourcesConfig.SUMMARIES] toggle and notification
 * access; only acts when a local model is actually installed.
 */
object NotificationSummarizer {
    private val executor = Executors.newSingleThreadExecutor()
    private val lastFor = ConcurrentHashMap<String, Long>()
    private const val MIN_LEN = 120
    private const val DEBOUNCE_MS = 6_000L
    private const val CHANNEL_ID = "dora_summaries"
    private const val NOTIFICATION_BASE = 49_000

    fun isEnabled(context: Context): Boolean =
        ContextSourcesConfig(context).isEnabled(ContextSourcesConfig.SUMMARIES)

    /** Worth summarizing? Long message notifications only (short ones don't benefit). */
    fun shouldSummarize(text: String, isMessage: Boolean): Boolean =
        isMessage && text.length >= MIN_LEN

    /**
     * Summarize off-thread and post the result. [onReplace], if provided, is run
     * after a successful summary so the listener can cancel the original.
     */
    fun summarize(
        context: Context,
        pkg: String,
        app: String,
        title: String,
        text: String,
        originalKey: String,
        onReplace: (() -> Unit)?
    ) {
        val convo = "$pkg|$title"
        val now = System.currentTimeMillis()
        if (now - (lastFor[convo] ?: 0L) < DEBOUNCE_MS) return
        lastFor[convo] = now

        executor.execute {
            runCatching {
                val engine = LocalOnnxRuntimeEngine(context, BackendConfig.load())
                if (!engine.isConfigured()) return@runCatching
                val result = engine.infer(
                    JSONObject()
                        .put("system", "You summarize a chat message in one short, clear sentence. Output only the summary, no preamble.")
                        .put("prompt", "Message from ${title.ifBlank { app }}:\n\"$text\"")
                        .put("max_tokens", 80)
                        .put("temperature", 0.2)
                )
                val summary = result.optString("output", "").trim().removeSurrounding("\"")
                if (!result.optBoolean("ok", false) || summary.isBlank()) return@runCatching
                postSummary(context, originalKey.hashCode(), pkg, "$app \u00b7 ${title.ifBlank { "Message" }}", summary)
                onReplace?.let { runCatching { it() } }
            }
        }
    }

    private fun postSummary(context: Context, id: Int, pkg: String, title: String, summary: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Message summaries", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        // Tap opens the originating app.
        val launch = runCatching { context.packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(
                context, id, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") android.app.Notification.Builder(context)
        }
        builder.setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(android.app.Notification.BigTextStyle().bigText(summary))
            .setAutoCancel(true)
        if (contentIntent != null) builder.setContentIntent(contentIntent)
        nm.notify(NOTIFICATION_BASE + (id and 0xFFFF), builder.build())
    }
}
