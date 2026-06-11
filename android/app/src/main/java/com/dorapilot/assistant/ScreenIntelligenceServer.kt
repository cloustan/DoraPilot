package com.dorapilot.assistant

import org.json.JSONObject

/**
 * Gemini/Galaxy-style screen intelligence over Android AssistStructure text.
 *
 * This is intentionally text-only: it uses the latest AssistStructure dump that
 * Android provides to the voice interaction session. Apps that render custom
 * canvases or block assist data may provide little/no text; those cases return
 * an honest setup/unavailable message instead of pretending vision worked.
 */
class ScreenIntelligenceServer(
    private val activeScreenProvider: () -> JSONObject,
    private val textIntelligence: TextIntelligenceServer
) {
    fun summarize(): JSONObject = transform(TextIntelligenceServer.Mode.SUMMARIZE)

    fun keyPoints(): JSONObject = transform(TextIntelligenceServer.Mode.KEY_POINTS)

    fun actionItems(): JSONObject = transform(TextIntelligenceServer.Mode.ACTION_ITEMS)

    fun translate(language: String): JSONObject =
        transform(TextIntelligenceServer.Mode.TRANSLATE, language.ifBlank { "English" })

    private fun transform(mode: TextIntelligenceServer.Mode, language: String = ""): JSONObject {
        val screen = activeScreenProvider()
        val screenText = extractScreenText(screen)
        if (screenText.isBlank()) {
            return unavailable(screen)
        }

        val result = textIntelligence.transform(mode, screenText, language)
        if (result.optBoolean("ok", false)) {
            return result
                .put("source", "active_screen")
                .put("foreground_package", foregroundPackage(screen))
        }
        return result
            .put("source", "active_screen")
            .put("foreground_package", foregroundPackage(screen))
    }

    private fun extractScreenText(screen: JSONObject): String {
        val directDump = screen.optString("assist_dump", "").trim()
        val nestedDump = screen.optJSONObject("active_screen")
            ?.optString("assist_dump", "")
            ?.trim()
            .orEmpty()
        val raw = directDump.ifBlank { nestedDump }
        if (raw.isBlank()) return ""
        if (raw.contains("Assist structure unavailable", ignoreCase = true)) return ""
        if (raw.contains("No Assist Structure captured", ignoreCase = true)) return ""

        val extracted = mutableListOf<String>()
        raw.lineSequence().forEach { line ->
            TEXT_FIELD_REGEX.findAll(line).forEach { match ->
                val value = match.groupValues.getOrNull(2)?.trim().orEmpty()
                if (value.isNotBlank()) extracted += value
            }
        }

        val compact = extracted
            .distinct()
            .joinToString("\n")
            .ifBlank { raw }
            .take(6000)
            .trim()
        return compact
    }

    private fun unavailable(screen: JSONObject): JSONObject {
        val message = if (foregroundPackage(screen).isBlank() ||
            screen.optString("source") == "main_activity_webview"
        ) {
            "I do not have another app's screen text yet. Open the target app, invoke Dora over it, then ask again."
        } else {
            "I could not read useful text from this screen. The app may hide its content from Android AssistStructure."
        }
        return JSONObject()
            .put("ok", false)
            .put("output", message)
            .put("error", message)
            .put("source", "active_screen")
            .put("foreground_package", foregroundPackage(screen))
            .put("needs_screen_context", true)
    }

    private fun foregroundPackage(screen: JSONObject): String {
        return screen.optString(
            "foreground_package",
            screen.optJSONObject("active_screen")?.optString("foreground_package", "").orEmpty()
        )
    }

    companion object {
        private val TEXT_FIELD_REGEX = Regex("\\b(text|hint|desc)=([^|]+)")
    }
}
