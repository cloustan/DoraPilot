package com.dorapilot.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * In-memory rolling buffer of recent notifications captured by
 * [DoraNotificationListener]. Kept in RAM only (not persisted) so personal
 * notification content never touches disk. Cleared when the user disables the
 * source.
 */
object NotificationStore {
    private const val MAX = 50
    private val items = ArrayDeque<JSONObject>()

    @Synchronized
    fun add(
        pkg: String,
        appLabel: String,
        title: String,
        text: String,
        category: String,
        postTime: Long,
        isMessage: Boolean
    ) {
        // De-dupe rapid repeats of the same notification.
        val last = items.lastOrNull()
        if (last != null && last.optString("package") == pkg &&
            last.optString("title") == title && last.optString("text") == text
        ) {
            return
        }
        items.addLast(
            JSONObject()
                .put("package", pkg)
                .put("app", appLabel)
                .put("title", title)
                .put("text", text)
                .put("category", category)
                .put("time", postTime)
                .put("is_message", isMessage)
        )
        while (items.size > MAX) items.removeFirst()
    }

    @Synchronized
    fun recent(limit: Int, messagesOnly: Boolean): JSONArray {
        val out = JSONArray()
        items.reversed().asSequence()
            .filter { !messagesOnly || it.optBoolean("is_message", false) }
            .take(limit.coerceIn(1, MAX))
            .forEach { out.put(it) }
        return out
    }

    @Synchronized
    fun count(messagesOnly: Boolean): Int =
        items.count { !messagesOnly || it.optBoolean("is_message", false) }

    @Synchronized
    fun clear() = items.clear()
}
