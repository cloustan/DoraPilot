package com.dorapilot.assistant

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONObject

/**
 * Handles the inline "Ask Dora" reply on summary notifications: grabs the
 * typed question, flips the notification to a "Thinking" state immediately
 * (which dismisses the system's reply spinner), and hands the actual inference
 * to [AskDoraWorker] - broadcast receivers must not block for model latency.
 */
class NotificationAskReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ASK) return
        val question = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(NotificationSummarizer.KEY_ASK)?.toString()?.trim()
            ?.ifBlank { null }
            ?: intent.getStringExtra(EXTRA_DEBUG_QUESTION)?.trim()?.ifBlank { null }
            ?: return

        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
        if (notifId < 0) return
        val pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { "Notification" }
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        val summary = intent.getStringExtra(EXTRA_SUMMARY).orEmpty()

        NotificationSummarizer.postThinking(context, notifId, pkg, label, question)

        val work = OneTimeWorkRequestBuilder<AskDoraWorker>()
            .setInputData(
                Data.Builder()
                    .putInt(EXTRA_NOTIF_ID, notifId)
                    .putString(EXTRA_PKG, pkg)
                    .putString(EXTRA_LABEL, label)
                    .putString(EXTRA_BODY, body)
                    .putString(EXTRA_SUMMARY, summary)
                    .putString(EXTRA_QUESTION, question)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueue(work)
    }

    companion object {
        const val ACTION_ASK = "com.dorapilot.action.ASK_NOTIFICATION"
        const val EXTRA_NOTIF_ID = "notif_id"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_LABEL = "label"
        const val EXTRA_BODY = "body"
        const val EXTRA_SUMMARY = "summary"
        const val EXTRA_QUESTION = "question"
        const val EXTRA_DEBUG_QUESTION = "debug_question"
    }
}

/**
 * Answers a question about a notification. Prefers the on-device model
 * (private, offline); falls back to the cloud agent when no local model is
 * installed. Updates the same notification with the Q&A.
 */
class AskDoraWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val notifId = inputData.getInt(NotificationAskReceiver.EXTRA_NOTIF_ID, -1)
        val pkg = inputData.getString(NotificationAskReceiver.EXTRA_PKG).orEmpty()
        val label = inputData.getString(NotificationAskReceiver.EXTRA_LABEL).orEmpty()
        val body = inputData.getString(NotificationAskReceiver.EXTRA_BODY).orEmpty()
        val summary = inputData.getString(NotificationAskReceiver.EXTRA_SUMMARY).orEmpty()
        val question = inputData.getString(NotificationAskReceiver.EXTRA_QUESTION).orEmpty()
        if (notifId < 0 || question.isBlank()) return Result.success()

        val answer = runCatching { answer(ctx, label, body, summary, question) }
            .getOrElse { "Sorry, I couldn't work that out: ${it.message}" }
            .ifBlank { "Sorry, I couldn't work that out." }
        NotificationSummarizer.postAnswer(ctx, notifId, pkg, label, question, answer, body)
        Log.i(TAG, "Answered notification question (${answer.length} chars)")
        return Result.success()
    }

    private fun answer(
        ctx: Context,
        label: String,
        body: String,
        summary: String,
        question: String
    ): String {
        val contextBlock = buildString {
            append("Notification from ").append(label).append(":\n").append(body)
            if (summary.isNotBlank()) append("\n\nSummary: ").append(summary)
        }
        val engine = SharedLocalEngine.get(ctx)
        if (engine.isConfigured()) {
            val result = engine.infer(
                JSONObject()
                    .put(
                        "system",
                        "You answer the user's question about a notification they received. " +
                            "Be brief and direct (1-3 sentences). Never ask for clarification."
                    )
                    .put("prompt", "$contextBlock\n\nQuestion: $question")
                    .put("max_tokens", 160)
                    .put("temperature", 0.2)
            )
            val text = result.optString("output", "").trim()
            if (result.optBoolean("ok", false) && text.isNotBlank()) return text
        }
        // No local model (or it failed): use the cloud agent.
        return HeadlessAgentRunner(ctx).run(
            "Answer briefly: the user received this notification:\n$contextBlock\n\n" +
                "Their question: $question"
        )
    }

    private companion object {
        const val TAG = "AskDoraWorker"
    }
}
