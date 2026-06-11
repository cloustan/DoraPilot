package com.dorapilot.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages and runs OpenClaw-style skills. Skills can be authored locally or
 * imported from a ClawHub / raw SKILL.md URL, then run on demand or on a
 * schedule (via the heartbeat). Running a skill streams live step notifications.
 */
class SkillServer(
    private val context: Context,
    private val httpBridge: HttpBridgeServer
) {

    fun create(args: JSONObject): JSONObject {
        val name = normalizeName(args.optString("name", "").trim())
        val instructions = args.optString("instructions", args.optString("body", "")).trim()
        if (name.isBlank() || instructions.isBlank()) {
            return JSONObject().put("ok", false).put("error", "name and instructions are required")
        }
        val triggerType = args.optString("trigger_type", args.optString("trigger", "manual")).trim().lowercase()
            .let { if (it in setOf("manual", "interval", "daily", "event")) it else "manual" }
        val id = PersonalContextStore.upsertSkill(
            context, name,
            args.optString("description", "").trim().ifBlank { instructions.take(80) },
            instructions,
            toolsToJson(args.opt("tools")),
            triggerType,
            args.optInt("interval_min", 60).coerceAtLeast(1),
            parseAtMinutes(args),
            args.optString("event", "").trim(),
            "local"
        )
        if (triggerType in setOf("interval", "daily")) AssistantWorkScheduler.ensureHeartbeat(context)
        return JSONObject().put("ok", true).put("id", id).put("spoken", "Skill '$name' saved.")
            .put("skill", PersonalContextStore.skillById(context, id))
    }

    /** Import an OpenClaw/ClawHub skill from a raw SKILL.md URL. */
    fun importUrl(url: String): JSONObject {
        val u = url.trim()
        if (u.isBlank()) return JSONObject().put("ok", false).put("error", "url is required")
        val resp = httpBridge.request(JSONObject().put("url", u).put("max_bytes", 200_000))
        if (!resp.optBoolean("ok", false)) {
            return JSONObject().put("ok", false).put("error", "Could not fetch skill: ${resp.optString("error", resp.optString("status"))}")
        }
        return importMarkdown(resp.optString("body", ""), u)
    }

    fun importMarkdown(markdown: String, source: String): JSONObject {
        if (markdown.isBlank()) return JSONObject().put("ok", false).put("error", "empty skill file")
        val parsed = SkillParser.parse(markdown)
        val id = PersonalContextStore.upsertSkill(
            context, normalizeName(parsed.name), parsed.description, parsed.instructions,
            "[]", "manual", 60, 0, "", source.ifBlank { "import" }
        )
        val note = buildCompatibilityNote(parsed)
        return JSONObject()
            .put("ok", true)
            .put("id", id)
            .put("name", parsed.name)
            .put("spoken", "Imported skill '${parsed.name}'.${if (note.isEmpty()) "" else " $note"}")
            .put("compatibility", note)
            .put("skill", PersonalContextStore.skillById(context, id))
    }

    fun list(): JSONObject {
        val skills = JSONArray()
        PersonalContextStore.listSkills(context).forEach { skills.put(it) }
        return JSONObject().put("ok", true).put("count", skills.length()).put("skills", skills)
    }

    fun delete(id: Long): JSONObject {
        if (id <= 0) return JSONObject().put("ok", false).put("error", "id is required")
        PersonalContextStore.deleteSkill(context, id)
        return JSONObject().put("ok", true).put("spoken", "Skill deleted.")
    }

    fun setEnabled(id: Long, enabled: Boolean): JSONObject {
        if (id <= 0) return JSONObject().put("ok", false).put("error", "id is required")
        PersonalContextStore.setSkillEnabled(context, id, enabled)
        return JSONObject().put("ok", true)
    }

    fun run(id: Long): JSONObject {
        val skill = PersonalContextStore.skillById(context, id)
            ?: return JSONObject().put("ok", false).put("error", "No skill with id $id")
        val result = execute(skill)
        return JSONObject().put("ok", true).put("id", id).put("result", result).put("spoken", result)
    }

    /** Execute a skill: stream step notifications, return the final result. */
    fun execute(skill: JSONObject): String {
        val id = skill.optLong("id").toInt()
        val name = skill.optString("name", "Skill")
        val goal = skill.optString("instructions", "").trim()
            .ifBlank { skill.optString("description", "") }
        val allowed = toolNameSet(skill.optString("tools", "[]"))
        ProgressNotifier.step(context, id, name, "Starting\u2026")
        val result = HeadlessAgentRunner(context).run(
            goal = goal,
            allowedTools = allowed,
            onStep = { ProgressNotifier.step(context, id, name, it) }
        )
        ProgressNotifier.final(context, id, name, result)
        PersonalContextStore.markSkillRun(context, skill.optLong("id"), result)
        return result
    }

    private fun normalizeName(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "skill" }

    private fun toolsToJson(tools: Any?): String = when (tools) {
        is JSONArray -> tools.toString()
        is String -> if (tools.isBlank()) "[]" else JSONArray(tools.split(",").map { it.trim() }).toString()
        else -> "[]"
    }

    private fun toolNameSet(json: String): Set<String>? = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }.toSet()
            .takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun buildCompatibilityNote(parsed: SkillParser.ParsedSkill): String {
        val unsupported = parsed.requiresBins.filter { it.lowercase() !in DORA_COVERED_BINS }
        return when {
            unsupported.isEmpty() && parsed.requiresEnv.isEmpty() -> ""
            else -> buildString {
                if (unsupported.isNotEmpty()) append("May need tools Dora lacks: ${unsupported.joinToString(", ")}. ")
                if (parsed.requiresEnv.isNotEmpty()) append("Needs credentials: ${parsed.requiresEnv.joinToString(", ")} (add via vault).")
            }.trim()
        }
    }

    private fun parseAtMinutes(args: JSONObject): Int {
        if (args.has("at_minutes")) return args.optInt("at_minutes").coerceIn(0, 1439)
        val at = args.optString("at", args.optString("time", "")).trim()
        val m = Regex("(\\d{1,2})\\s*:\\s*(\\d{2})").find(at) ?: return 0
        return ((m.groupValues[1].toIntOrNull() ?: 0) * 60 + (m.groupValues[2].toIntOrNull() ?: 0)).coerceIn(0, 1439)
    }

    private companion object {
        // Bins whose typical use Dora already covers (http.request handles curl/http calls).
        val DORA_COVERED_BINS = setOf("curl", "wget", "http", "https", "jq")
    }
}
