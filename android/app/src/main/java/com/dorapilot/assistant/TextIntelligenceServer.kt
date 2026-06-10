package com.dorapilot.assistant

import android.content.ClipboardManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Galaxy-AI style writing/intelligence tools. All are text-in / text-out so they
 * need no dedicated UI - results flow back into the existing chat surface. Works
 * on text supplied inline ("summarize: ...") or on the clipboard ("summarize my
 * clipboard"). Runs the cloud model through the existing backend client.
 */
class TextIntelligenceServer(
    private val context: Context,
    private val backendClient: MainBackendClient,
    private val configProvider: () -> BackendConfig
) {
    enum class Mode(val instruction: String) {
        SUMMARIZE("Summarize the following text concisely. Reply with the summary only."),
        KEY_POINTS("Extract the key points as a short bulleted list. Reply with the bullets only."),
        ACTION_ITEMS("Extract concrete action items as a short checklist. Reply with the list only."),
        PROOFREAD("Correct spelling, grammar and punctuation. Reply with ONLY the corrected text, preserving meaning and tone."),
        SHORTER("Make the following text shorter and tighter while preserving meaning. Reply with the rewrite only."),
        LONGER("Expand the following text with helpful detail while preserving meaning. Reply with the rewrite only."),
        PROFESSIONAL("Rewrite the following text in a professional, polished tone. Reply with the rewrite only."),
        CASUAL("Rewrite the following text in a friendly, casual tone. Reply with the rewrite only."),
        POLITE("Rewrite the following text in a warm, polite tone. Reply with the rewrite only."),
        TRANSLATE("Translate the following text. Reply with ONLY the translation."),
        COMPOSE("Write the requested message. Reply with ONLY the message text.");
    }

    fun transform(mode: Mode, text: String, language: String = ""): JSONObject {
        val content = text.trim()
        if (content.isEmpty()) {
            return JSONObject().put("ok", false).put("error", "There was no text to work with.")
        }
        val config = configProvider()
        if (config.apiKey.isBlank()) {
            return JSONObject().put("ok", false).put("error", "Backend is not configured.")
        }
        val system = buildString {
            append("You are Dora's writing assistant. ")
            append(mode.instruction)
            if (mode == Mode.TRANSLATE && language.isNotBlank()) {
                append(" Target language: $language.")
            }
            append(" Do not add commentary, preamble, or quotation marks.")
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", content.take(6000)))

        val result = backendClient.complete(
            MainBackendClient.CompletionRequest(
                endpoint = config.endpoint,
                apiKey = config.apiKey,
                model = config.model,
                messages = messages,
                headers = config.headers,
                temperature = if (mode == Mode.PROOFREAD) 0.0 else 0.4
            )
        )
        return if (result.ok) {
            JSONObject().put("ok", true).put("output", result.outputText.trim()).put("mode", mode.name.lowercase())
        } else {
            JSONObject().put("ok", false).put("error", result.error.ifBlank { "Writing tool failed." })
        }
    }

    /**
     * Detect and run a writing command from a free-text prompt. Returns an
     * inference-style {ok, output} result, or null if the prompt is not a
     * writing command (so the caller falls back to normal chat).
     */
    fun parseAndRun(rawPrompt: String): JSONObject? {
        val prompt = rawPrompt.trim()
        if (prompt.isEmpty()) return null
        val lower = prompt.lowercase()

        val usesClipboard = lower.contains("clipboard") ||
            lower.contains("what i copied") || lower.contains("copied text")

        // Translate: "translate to french: ..." / "translate my clipboard to spanish"
        Regex("translate(?:\\s+this|\\s+my\\s+clipboard|\\s+the\\s+clipboard)?\\s+(?:in)?to\\s+([a-z ]+?)\\s*[:\\-]?\\s*(.*)$", RegexOption.IGNORE_CASE)
            .find(prompt)?.let { m ->
                val language = m.groupValues[1].trim().trimEnd(':', '-').trim()
                val inline = m.groupValues[2].trim()
                val text = resolveText(inline, usesClipboard) ?: return clipboardError()
                if (text.isBlank()) return null
                return transform(Mode.TRANSLATE, text, language)
            }

        val mode = when {
            lower.startsWith("summarize") || lower.startsWith("summarise") || lower.startsWith("tldr") ||
                lower.startsWith("tl;dr") -> Mode.SUMMARIZE
            lower.startsWith("key points") || lower.contains("key points of") -> Mode.KEY_POINTS
            lower.startsWith("action items") || lower.contains("action items from") -> Mode.ACTION_ITEMS
            lower.startsWith("proofread") || lower.startsWith("fix grammar") ||
                lower.startsWith("fix the grammar") || lower.startsWith("correct grammar") ||
                lower.startsWith("check grammar") || lower.startsWith("spell check") -> Mode.PROOFREAD
            lower.startsWith("make shorter") || lower.startsWith("make this shorter") ||
                lower.startsWith("shorten") -> Mode.SHORTER
            lower.startsWith("make longer") || lower.startsWith("expand") ||
                lower.startsWith("elaborate") -> Mode.LONGER
            lower.contains("more professional") || lower.startsWith("make this professional") ||
                lower.startsWith("professional tone") -> Mode.PROFESSIONAL
            lower.contains("more casual") || lower.startsWith("make this casual") ||
                lower.startsWith("casual tone") -> Mode.CASUAL
            lower.contains("more polite") || lower.startsWith("make this polite") ||
                lower.startsWith("polite tone") -> Mode.POLITE
            else -> null
        } ?: return null

        val text = resolveText(stripCommandPrefix(prompt), usesClipboard) ?: return clipboardError()
        if (text.isBlank()) return null
        return transform(mode, text)
    }

    private fun resolveText(inline: String, usesClipboard: Boolean): String? {
        val cleaned = inline.trim().trim(':', '-', ' ').trim()
        if (cleaned.isNotBlank() && !usesClipboard) return cleaned
        if (usesClipboard) return readClipboard() ?: return null
        return cleaned
    }

    private fun stripCommandPrefix(prompt: String): String {
        // Take whatever follows the first ':' as the payload when present.
        val idx = prompt.indexOf(':')
        if (idx in 0 until prompt.length - 1) return prompt.substring(idx + 1).trim()
        return ""
    }

    private fun readClipboard(): String? {
        return runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            clip.getItemAt(0).coerceToText(context).toString()
        }.getOrNull()
    }

    private fun clipboardError(): JSONObject =
        JSONObject().put("ok", false)
            .put("output", "I couldn't read your clipboard. Copy some text first, then try again.")
}
