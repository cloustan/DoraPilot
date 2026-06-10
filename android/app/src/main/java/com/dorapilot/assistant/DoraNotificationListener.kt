package com.dorapilot.assistant

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Captures recent notifications (and messaging-app messages) into the in-memory
 * [NotificationStore] when the user has enabled those context sources. This is
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
        val wantNotifications = config.isEnabled(ContextSourcesConfig.NOTIFICATIONS)
        val wantMessages = config.isEnabled(ContextSourcesConfig.MESSAGES)
        if (!wantNotifications && !wantMessages) return
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

        // If only messages are enabled, ignore non-message notifications.
        if (!wantNotifications && wantMessages && !isMessage) return

        val appLabel = runCatching {
            val pm = applicationContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)

        NotificationStore.add(
            pkg = sbn.packageName,
            appLabel = appLabel,
            title = title,
            text = text,
            category = category,
            postTime = sbn.postTime,
            isMessage = isMessage
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No-op: we keep a short rolling history for context.
    }

    companion object {
        private val MESSAGING_PACKAGES = setOf(
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
    }
}
