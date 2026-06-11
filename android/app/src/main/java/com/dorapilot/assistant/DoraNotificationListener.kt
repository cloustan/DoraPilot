package com.dorapilot.assistant

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

/**
 * Captures recent notifications (and messaging-app messages) into the encrypted
 * [PersonalContextStore] when the user has enabled those context sources. This is
 * the Play-policy-compliant way to give Dora message/notification awareness
 * without the restricted READ_SMS / READ_CALL_LOG permissions.
 *
 * Requires the user to grant Notification access in Settings, gated behind the
 * per-source toggles in [ContextSourcesConfig].
 */
class DoraNotificationListener : NotificationListenerService() {

    private val config by lazy { ContextSourcesConfig(applicationContext) }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == applicationContext.packageName) return

        val notification = sbn.notification ?: return
        val flags = notification.flags
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
        if (title.isEmpty() && text.isEmpty()) return

        val category = notification.category ?: ""
        val isMessage = category == Notification.CATEGORY_MESSAGE ||
            sbn.packageName in MESSAGING_PACKAGES

        val appLabel = runCatching {
            val pm = applicationContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)

        // Real-time event triggers fire any matching event-skills/automations.
        // Independent of the storage toggles (the user opted in by creating one).
        EventTriggerEvaluator.onNotification(
            applicationContext, sbn.packageName, appLabel, title, text, isMessage
        )

        // On-device AI summaries (private, no key). Summarize a long single
        // message OR a conversation/burst of multiple messages. Replaces the
        // original with a one-line summary once it's ready.
        if (NotificationSummarizer.isEnabled(applicationContext)) {
            val conversation = extractConversationMessages(notification)
            val body = if (conversation.isNotEmpty()) conversation.joinToString("\n") else text
            val count = if (conversation.isNotEmpty()) conversation.size else if (text.isNotEmpty()) 1 else 0
            if (NotificationSummarizer.shouldSummarize(body, isMessage, count)) {
                val originalKey = sbn.key
                NotificationSummarizer.summarize(
                    applicationContext, sbn.packageName, appLabel, title, body, count > 1, originalKey
                ) {
                    runCatching { cancelNotification(originalKey) }
                        .onSuccess { android.util.Log.i(TAG, "Cancelled original $originalKey") }
                        .onFailure { android.util.Log.w(TAG, "Cancel failed for $originalKey", it) }
                }
            }
        }

        // Capture into the encrypted context store, gated by the per-source toggles.
        val wantNotifications = config.isEnabled(ContextSourcesConfig.NOTIFICATIONS)
        val wantMessages = config.isEnabled(ContextSourcesConfig.MESSAGES)
        if (wantNotifications || (wantMessages && isMessage)) {
            PersonalContextStore.insertItem(
                context = applicationContext,
                source = if (isMessage) "message" else "notification",
                app = appLabel,
                title = title,
                body = text,
                ts = sbn.postTime,
                isMessage = isMessage
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No-op: we keep a short rolling history for context.
    }

    /**
     * Pull individual messages out of a notification that bundles several:
     * MessagingStyle (group/stacked chats) first, then InboxStyle text lines.
     * Returns empty for a plain single-message notification.
     */
    private fun extractConversationMessages(notification: Notification): List<String> {
        val out = mutableListOf<String>()
        runCatching {
            val style = NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(notification)
            style?.messages?.forEach { m ->
                val body = m.text?.toString()?.trim().orEmpty()
                if (body.isNotEmpty()) {
                    val sender = m.person?.name?.toString().orEmpty()
                    out.add(if (sender.isNotBlank()) "$sender: $body" else body)
                }
            }
        }
        if (out.isEmpty()) {
            notification.extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { line ->
                line?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
            }
        }
        return out
    }

    companion object {
        private const val TAG = "DoraNotificationListener"
        private val MESSAGING_PACKAGES = buildSet {
            addAll(
                setOf(
                    "com.whatsapp",
                    "com.whatsapp.w4b",
                    "com.google.android.apps.messaging",
                    "com.samsung.android.messaging",
                    "org.telegram.messenger",
                    "org.thoughtcrime.securesms",
                    "com.facebook.orca",
                    "com.instagram.android",
                    "com.google.android.gm",
                    "com.discord",
                    "com.slack",
                    "com.microsoft.teams",
                    "com.linkedin.android"
                )
            )
            // Debug-only: let adb `cmd notification post` exercise the message
            // pipeline (shell-posted notifications have no messaging category).
            if (com.dorapilot.BuildConfig.DEBUG) add("com.android.shell")
        }
    }
}
