package com.dorapilot.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * Dora Timeline: an LLM-assisted, chronological view of the user's day/context.
 *
 * The source collection remains on-device via [PersonalContextEngine]. When a
 * backend is configured we send only the compact timeline context to the model
 * for wording; otherwise we return a deterministic local timeline fallback.
 */
class TimelineIntelligenceServer(
    private val personalContext: PersonalContextEngine,
    private val backendClient: MainBackendClient,
    private val configProvider: () -> BackendConfig
) {
    fun doraTimeline(query: String = ""): JSONObject {
        val snapshot = personalContext.getSnapshot()
        val relevant = if (query.isNotBlank()) {
            personalContext.searchPersonalData(query, 8)
        } else {
            JSONObject().put("ok", true).put("count", 0).put("results", JSONArray())
        }
        val fallback = buildLocalTimeline(snapshot, relevant)
        val config = configProvider()
        if (config.apiKey.isBlank()) {
            return JSONObject()
                .put("ok", true)
                .put("output", fallback)
                .put("mode", "local_fallback")
                .put("note", "Backend is not configured, so Dora built this timeline locally.")
        }

        val messages = JSONArray()
            .put(
                JSONObject()
                    .put("role", "system")
                    .put(
                        "content",
                        "You are Dora Timeline, a private on-phone assistant feature. " +
                            "Create a concise chronological timeline from the provided on-device context. " +
                            "Do not invent events. If data sources are missing, mention that briefly at the end. " +
                            "Use short bullets with times/relative times when available."
                    )
            )
            .put(
                JSONObject()
                    .put("role", "user")
                    .put("content", buildTimelinePrompt(query, snapshot, relevant))
            )

        val completion = backendClient.complete(
            MainBackendClient.CompletionRequest(
                endpoint = config.endpoint,
                apiKey = config.apiKey,
                model = config.model,
                messages = messages,
                headers = config.headers,
                temperature = 0.25
            )
        )

        val output = completion.outputText.trim()
        return if (completion.ok && output.isNotBlank()) {
            JSONObject()
                .put("ok", true)
                .put("output", output)
                .put("mode", "llm")
        } else {
            JSONObject()
                .put("ok", true)
                .put("output", fallback)
                .put("mode", "local_fallback")
                .put(
                    "note",
                    completion.error.ifBlank { "Timeline model response was unavailable, so Dora used a local fallback." }
                )
        }
    }

    private fun buildTimelinePrompt(query: String, snapshot: JSONObject, relevant: JSONObject): String {
        return buildString {
            appendLine("User request: ${query.ifBlank { "Dora Timeline" }}")
            appendLine()
            appendLine("On-device context JSON:")
            appendLine(snapshot.toString(2).take(9000))
            val results = relevant.optJSONArray("results") ?: JSONArray()
            if (results.length() > 0) {
                appendLine()
                appendLine("Query-relevant personal search results:")
                appendLine(relevant.toString(2).take(4000))
            }
            appendLine()
            appendLine("Return format:")
            appendLine("Dora Timeline")
            appendLine("- Time/when — event or context (source)")
            appendLine("- ...")
        }
    }

    private fun buildLocalTimeline(snapshot: JSONObject, relevant: JSONObject): String {
        val lines = mutableListOf<String>()
        val device = snapshot.optJSONObject("device") ?: JSONObject()
        val now = device.optString("local_time", "now")
        lines += "Dora Timeline"
        lines += "- Now — $now"

        if (device.has("battery_percent")) {
            val charging = if (device.optBoolean("charging", false)) ", charging" else ""
            lines += "- Device — Battery ${device.optInt("battery_percent")}%$charging; network ${device.optString("network", "unknown")}."
        }

        appendEvents(lines, "Calendar", snapshot.optJSONArray("calendar") ?: JSONArray())
        appendMessages(lines, "Messages", snapshot.optJSONArray("messages") ?: JSONArray())
        appendMessages(lines, "Notifications", snapshot.optJSONArray("notifications") ?: JSONArray())
        appendRelevant(lines, relevant.optJSONArray("results") ?: JSONArray())

        val topApps = snapshot.optJSONObject("usage")?.optJSONArray("top_apps") ?: JSONArray()
        if (topApps.length() > 0) {
            val names = (0 until minOf(5, topApps.length())).mapNotNull {
                topApps.optJSONObject(it)?.optString("label")?.takeIf(String::isNotBlank)
            }
            if (names.isNotEmpty()) lines += "- Usage pattern — Frequently used: ${names.joinToString(", ")}."
        }

        val hints = sourceHints(snapshot)
        if (hints.isNotEmpty()) {
            lines += "- Setup tip — Enable ${hints.joinToString(", ")} in Personal context to make Dora Timeline richer."
        }

        return lines.joinToString("\n")
    }

    private fun appendEvents(lines: MutableList<String>, label: String, events: JSONArray) {
        for (i in 0 until minOf(6, events.length())) {
            val event = events.optJSONObject(i) ?: continue
            val title = event.optString("title").ifBlank { "(untitled)" }
            val whenText = event.optString("when").ifBlank { label }
            val location = event.optString("location").let { if (it.isNotBlank()) " @ $it" else "" }
            lines += "- $whenText — $title$location ($label)"
        }
    }

    private fun appendMessages(lines: MutableList<String>, label: String, items: JSONArray) {
        for (i in 0 until minOf(5, items.length())) {
            val item = items.optJSONObject(i) ?: continue
            val whenText = item.optString("when").ifBlank { label }
            val who = item.optString("title").ifBlank { item.optString("app", label) }
            val text = item.optString("text").take(120)
            lines += "- $whenText — $who: $text ($label)"
        }
    }

    private fun appendRelevant(lines: MutableList<String>, results: JSONArray) {
        for (i in 0 until minOf(4, results.length())) {
            val item = results.optJSONObject(i) ?: continue
            val whenText = item.optString("when").ifBlank { item.optString("source", "Relevant") }
            val head = listOf(item.optString("title"), item.optString("text").take(100))
                .filter { it.isNotBlank() }
                .joinToString(": ")
            if (head.isNotBlank()) lines += "- $whenText — $head (relevant)"
        }
    }

    private fun sourceHints(snapshot: JSONObject): List<String> {
        val sources = snapshot.optJSONObject("sources") ?: return emptyList()
        val missing = mutableListOf<String>()
        ContextSourcesConfig.KEYS.forEach { key ->
            val raw = sources.opt(key)
            val enabled = when (raw) {
                is JSONObject -> raw.optBoolean("enabled", false)
                is Boolean -> raw
                else -> false
            }
            val granted = when (raw) {
                is JSONObject -> raw.optBoolean("granted", enabled)
                is Boolean -> raw
                else -> false
            }
            if (!enabled || !granted) {
                missing += key
            }
        }
        return missing.take(4)
    }
}
