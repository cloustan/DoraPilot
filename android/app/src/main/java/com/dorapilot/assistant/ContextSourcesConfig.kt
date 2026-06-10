package com.dorapilot.assistant

import android.content.Context
import org.json.JSONObject

/**
 * Per-source opt-in toggles for the personal context engine. EVERY source is
 * OFF by default; nothing personal is collected until the user explicitly turns
 * it on (and grants the matching OS permission). Stored locally only.
 *
 * Play-policy note: messages are sourced from the notification listener, NOT
 * from READ_SMS / READ_CALL_LOG (which are restricted for assistant apps).
 */
class ContextSourcesConfig(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(key: String): Boolean = prefs.getBoolean("src_$key", false)

    fun setEnabled(key: String, enabled: Boolean) {
        prefs.edit().putBoolean("src_$key", enabled).apply()
    }

    fun all(): JSONObject {
        val json = JSONObject()
        KEYS.forEach { json.put(it, isEnabled(it)) }
        return json
    }

    companion object {
        private const val PREFS = "dora_context_sources"
        const val NOTIFICATIONS = "notifications"
        const val MESSAGES = "messages"
        const val CALENDAR = "calendar"
        const val CONTACTS = "contacts"
        const val USAGE = "usage"
        val KEYS = listOf(NOTIFICATIONS, MESSAGES, CALENDAR, CONTACTS, USAGE)
    }
}
