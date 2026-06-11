package com.dorapilot.assistant

import org.json.JSONObject

/**
 * Parses OpenClaw / AgentSkills `SKILL.md` files: YAML frontmatter (single-line
 * keys, with `metadata` as single-line JSON) + a markdown instruction body.
 *
 * Dora runs the instruction body through its agent using its own tools. The
 * `metadata.openclaw.requires` (bins/env) fields are surfaced as compatibility
 * notes — many ClawHub skills only need `curl`, which Dora's http.request covers.
 */
object SkillParser {

    data class ParsedSkill(
        val name: String,
        val description: String,
        val homepage: String,
        val instructions: String,
        val requiresBins: List<String>,
        val requiresEnv: List<String>,
        val metadataRaw: String
    )

    fun parse(markdown: String, fallbackName: String = "imported-skill"): ParsedSkill {
        val text = markdown.replace("\r\n", "\n").trim('\u0000', ' ', '\n')
        val front: Map<String, String>
        val body: String
        if (text.startsWith("---")) {
            val end = text.indexOf("\n---", 3)
            if (end >= 0) {
                front = parseFrontmatter(text.substring(3, end).trim('\n'))
                body = text.substring(end + 4).trim('\n').trim()
            } else {
                front = emptyMap(); body = text
            }
        } else {
            front = emptyMap(); body = text
        }

        val metaRaw = front["metadata"].orEmpty()
        val (bins, env) = parseRequires(metaRaw)
        val name = (front["name"].orEmpty().ifBlank {
            Regex("^#\\s+(.+)$", RegexOption.MULTILINE).find(body)?.groupValues?.get(1)?.trim()
                ?.lowercase()?.replace(Regex("[^a-z0-9]+"), "-")?.trim('-')
                ?: fallbackName
        })
        return ParsedSkill(
            name = name,
            description = front["description"].orEmpty().ifBlank { "Imported OpenClaw skill." },
            homepage = front["homepage"].orEmpty(),
            instructions = body.ifBlank { "Follow the user's request." },
            requiresBins = bins,
            requiresEnv = env,
            metadataRaw = metaRaw
        )
    }

    private fun parseFrontmatter(block: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (line in block.lines()) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            var value = line.substring(idx + 1).trim()
            if ((value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("'") && value.endsWith("'"))
            ) {
                value = value.substring(1, value.length - 1)
            }
            if (key.isNotEmpty()) map[key] = value
        }
        return map
    }

    /** Pull requires.bins / requires.env from the single-line metadata JSON. */
    private fun parseRequires(metadataRaw: String): Pair<List<String>, List<String>> {
        if (metadataRaw.isBlank()) return emptyList<String>() to emptyList()
        return runCatching {
            val meta = JSONObject(metadataRaw)
            val oc = meta.optJSONObject("openclaw")
                ?: meta.optJSONObject("clawdbot")
                ?: meta.optJSONObject("clawdis")
                ?: return emptyList<String>() to emptyList()
            val req = oc.optJSONObject("requires") ?: JSONObject()
            fun arr(key: String): List<String> {
                val a = req.optJSONArray(key) ?: return emptyList()
                return (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
            }
            arr("bins") to arr("env")
        }.getOrDefault(emptyList<String>() to emptyList())
    }
}
