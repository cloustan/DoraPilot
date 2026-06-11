package com.dorapilot.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages background automation jobs (Dora's "routines"): create/list/delete/
 * enable, and run-now. Scheduled execution is handled by [HeartbeatWorker].
 */
class AutomationServer(private val context: Context) {

    fun create(args: JSONObject): JSONObject {
        val goal = args.optString("goal", "").trim()
        if (goal.isEmpty()) return JSONObject().put("ok", false).put("error", "goal is required")
        val title = args.optString("title", "").trim().ifBlank { goal.take(40) }
        val triggerType = args.optString("trigger_type", args.optString("trigger", "interval")).trim().lowercase()
            .let { if (it in setOf("interval", "daily", "event")) it else "interval" }
        val intervalMin = args.optInt("interval_min", args.optInt("every_minutes", 60)).coerceAtLeast(1)
        val atMinutes = parseAtMinutes(args)
        val event = args.optString("event", "").trim()

        val id = PersonalContextStore.addJob(context, title, goal, triggerType, intervalMin, atMinutes, event)
        if (id < 0) return JSONObject().put("ok", false).put("error", "Could not save the automation")
        AssistantWorkScheduler.ensureHeartbeat(context)
        return JSONObject()
            .put("ok", true)
            .put("id", id)
            .put("spoken", "Automation saved: $title.")
            .put("job", PersonalContextStore.jobById(context, id))
    }

    fun list(): JSONObject {
        val jobs = JSONArray()
        PersonalContextStore.listJobs(context).forEach { jobs.put(it) }
        return JSONObject().put("ok", true).put("count", jobs.length()).put("jobs", jobs)
    }

    fun delete(id: Long): JSONObject {
        if (id <= 0) return JSONObject().put("ok", false).put("error", "id is required")
        PersonalContextStore.deleteJob(context, id)
        return JSONObject().put("ok", true).put("spoken", "Automation deleted.")
    }

    fun setEnabled(id: Long, enabled: Boolean): JSONObject {
        if (id <= 0) return JSONObject().put("ok", false).put("error", "id is required")
        PersonalContextStore.setJobEnabled(context, id, enabled)
        return JSONObject().put("ok", true).put("spoken", if (enabled) "Automation enabled." else "Automation paused.")
    }

    fun runNow(id: Long): JSONObject {
        val job = PersonalContextStore.jobById(context, id)
            ?: return JSONObject().put("ok", false).put("error", "No automation with id $id")
        val result = HeadlessAgentRunner(context).run(job.optString("goal", ""))
        PersonalContextStore.markJobRun(context, id, result)
        return JSONObject().put("ok", true).put("id", id).put("result", result).put("spoken", result)
    }

    /** Accepts at_minutes (int), or "at"/"time" as "HH:mm". */
    private fun parseAtMinutes(args: JSONObject): Int {
        if (args.has("at_minutes")) return args.optInt("at_minutes").coerceIn(0, 1439)
        val at = args.optString("at", args.optString("time", "")).trim()
        val m = Regex("(\\d{1,2})\\s*:\\s*(\\d{2})").find(at) ?: return 0
        val h = m.groupValues[1].toIntOrNull() ?: 0
        val min = m.groupValues[2].toIntOrNull() ?: 0
        return (h * 60 + min).coerceIn(0, 1439)
    }
}
