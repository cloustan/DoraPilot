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
    private const val PROGRESS_CHANNEL_ID = "dora_summaries_progress"
    private const val NOTIFICATION_BASE = 49_000
    private const val PROGRESS_BASE = 50_000

    fun isEnabled(context: Context): Boolean =
        ContextSourcesConfig(context).isEnabled(ContextSourcesConfig.SUMMARIES)

    /**
     * Worth summarizing? Anything long enough to benefit: a conversation/burst
     * (more than one message) or any notification with a long body, regardless
     * of which app posted it. Short content is skipped - the summary would be
     * longer than the original.
     */
    fun shouldSummarize(text: String, messageCount: Int): Boolean =
        messageCount > 1 || text.length >= MIN_LEN

    /**
     * Summarize off-thread and post the result. [isConversation]/[isMessage]
     * pick the prompt (conversation vs chat message vs generic notification).
     * [onReplace], if provided, runs after a successful summary so the listener
     * can cancel the original.
     */
    fun summarize(
        context: Context,
        pkg: String,
        app: String,
        title: String,
        text: String,
        isConversation: Boolean,
        isMessage: Boolean,
        originalKey: String,
        onReplace: (() -> Unit)?
    ) {
        val convo = "$pkg|$title"
        val now = System.currentTimeMillis()
        if (now - (lastFor[convo] ?: 0L) < DEBOUNCE_MS) return
        lastFor[convo] = now

        executor.execute {
            val id = originalKey.hashCode()
            val label = title.ifBlank { app.ifBlank { "Messages" } }
            runCatching {
                val engine = SharedLocalEngine.get(context)
                if (!engine.isConfigured()) return@execute
                // Live progress while the model works (silent, spinner, ongoing).
                postWorking(context, id, label)
                val system: String
                val prompt: String
                if (isConversation) {
                    system = "You condense a chat conversation into at most two short sentences. " +
                        "Prioritize anything the user must DO (with deadlines), then the key " +
                        "decisions or plans. Do NOT copy messages verbatim - write a new condensed " +
                        "overview of the WHOLE conversation. Output only the summary, no preamble."
                    prompt = "Conversation in ${title.ifBlank { app }}:\n$text\n\nSummary of the whole conversation:"
                } else if (isMessage) {
                    system = "You summarize a chat message in one short, clear sentence. Do NOT " +
                        "copy the text verbatim - condense it in your own words, keeping any " +
                        "action or deadline. Output only the summary, no preamble."
                    prompt = "Message from ${title.ifBlank { app }}:\n\"$text\"\n\nOne-sentence summary:"
                } else {
                    system = "You summarize an app notification in one short, clear sentence. Do " +
                        "NOT copy the text verbatim - condense it in your own words, keeping any " +
                        "action the user must take. Output only the summary, no preamble."
                    prompt = "Notification from ${app.ifBlank { title }}${if (title.isNotBlank() && app.isNotBlank()) " (\"$title\")" else ""}:\n\"$text\"\n\nOne-sentence summary:"
                }
                val result = engine.infer(
                    JSONObject()
                        .put("system", system)
                        .put("prompt", prompt)
                        .put("max_tokens", if (isConversation) 130 else 80)
                        .put("temperature", 0.2)
                )
                val summary = result.optString("output", "").trim().removeSurrounding("\"")
                cancelWorking(context, id)
                if (!result.optBoolean("ok", false) || summary.isBlank()) return@execute
                postSummary(context, id, pkg, label, summary)
                onReplace?.let { runCatching { it() } }
            }.onFailure {
                cancelWorking(context, id)
            }
        }
    }

    /** Ongoing spinner notification shown while the on-device model is working. */
    private fun postWorking(context: Context, id: Int, title: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    PROGRESS_CHANNEL_ID,
                    "Summary progress",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, PROGRESS_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") android.app.Notification.Builder(context)
        }
        val notification = builder
            .setSmallIcon(com.dorapilot.R.drawable.ic_stat_dora)
            .setContentTitle(title)
            .setContentText("Summarizing on-device\u2026")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        nm.notify(PROGRESS_BASE + (id and 0xFFFF), notification)
    }

    private fun cancelWorking(context: Context, id: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.cancel(PROGRESS_BASE + (id and 0xFFFF))
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
        builder.setSmallIcon(com.dorapilot.R.drawable.ic_stat_dora)
            .setContentTitle(title)
            .setContentText(summary)
            // Liability marker: shown in the header (collapsed) and as the
            // footer of the expanded view.
            .setSubText("AI generated")
            .setStyle(
                android.app.Notification.BigTextStyle()
                    .bigText(summary)
                    .setSummaryText("AI-generated summary \u00b7 may contain mistakes")
            )
            .setAutoCancel(true)
        // Lead with the source app's icon so the summary reads as belonging to
        // the conversation, not to Dora.
        appIconBitmap(context, pkg)?.let {
            builder.setLargeIcon(android.graphics.drawable.Icon.createWithBitmap(it))
        }
        if (contentIntent != null) builder.setContentIntent(contentIntent)
        nm.notify(NOTIFICATION_BASE + (id and 0xFFFF), builder.build())
    }

    /** Source app icon rendered to a bitmap for use as the large icon. */
    private fun appIconBitmap(context: Context, pkg: String): android.graphics.Bitmap? = runCatching {
        val drawable = context.packageManager.getApplicationIcon(pkg)
        val size = 192
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        bitmap
    }.getOrNull()
}
