package com.dorapilot.assistant

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Real-time reactive autonomy: when a notification arrives, fire any enabled
 * event-triggered skills/automations whose filter matches it (e.g. "auto-summarize
 * messages from Sam", "capture OTP codes"). Runs off the listener thread, honors
 * the global pause, and debounces so notification updates don't double-fire.
 *
 * Event filter syntax (the `event` field on a skill/automation):
 *   "*" / "any"   -> any notification
 *   "message"     -> any messaging-app message
 *   "app:com.whatsapp" -> a specific package
 *   "from:Sam"    -> sender/app/title contains "Sam"
 *   "<keyword>"   -> app/title/body contains the keyword
 */
object EventTriggerEvaluator {
    private val executor = Executors.newSingleThreadExecutor()
    private val lastFire = ConcurrentHashMap<String, Long>()
    private const val DEBOUNCE_MS = 8_000L
    private const val MAX_PER_NOTIFICATION = 2

    fun onNotification(
        context: Context,
        pkg: String,
        app: String,
        title: String,
        text: String,
        isMessage: Boolean
    ) {
        executor.execute {
            runCatching {
                if (AutomationServer.isPaused(context)) return@runCatching
                val skills = PersonalContextStore.listSkills(context)
                    .filter { it.optBoolean("enabled", false) && it.optString("trigger_type") == "event" }
                val jobs = PersonalContextStore.listJobs(context)
                    .filter { it.optBoolean("enabled", false) && it.optString("trigger_type") == "event" }
                if (skills.isEmpty() && jobs.isEmpty()) return@runCatching

                val note = "From $app: $title $text".trim()
                var fired = 0

                for (skill in skills) {
                    if (fired >= MAX_PER_NOTIFICATION) break
                    if (!matches(skill.optString("event"), pkg, app, title, text, isMessage)) continue
                    if (!debounceOk("s" + skill.optLong("id"))) continue
                    fired++
                    runCatching { SkillServer(context, HttpBridgeServer()).execute(skill, note) }
                }

                for (job in jobs) {
                    if (fired >= MAX_PER_NOTIFICATION) break
                    if (!matches(job.optString("event"), pkg, app, title, text, isMessage)) continue
                    if (!debounceOk("j" + job.optLong("id"))) continue
                    fired++
                    val goal = job.optString("goal", "") + "\n\nTriggering event: " + note
                    val result = runCatching { HeadlessAgentRunner(context).run(goal) }
                        .getOrElse { "Job error: ${it.message}" }
                    PersonalContextStore.markJobRun(context, job.optLong("id"), result)
                    ProgressNotifier.final(
                        context,
                        job.optLong("id").toInt(),
                        job.optString("title", "Dora").ifBlank { "Dora" },
                        result
                    )
                }
            }
        }
    }

    private fun debounceOk(key: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - (lastFire[key] ?: 0L) < DEBOUNCE_MS) return false
        lastFire[key] = now
        return true
    }

    private fun matches(
        spec: String,
        pkg: String,
        app: String,
        title: String,
        text: String,
        isMessage: Boolean
    ): Boolean {
        val s = spec.trim().lowercase()
        val from = "$app $title".lowercase()
        val hay = "$app $title $text".lowercase()
        return when {
            s == "*" || s == "any" -> true
            s == "message" || s == "messages" -> isMessage
            s.isBlank() -> false
            s.startsWith("app:") -> pkg.lowercase().contains(s.removePrefix("app:").trim())
            s.startsWith("from:") -> from.contains(s.removePrefix("from:").trim())
            else -> hay.contains(s)
        }
    }
}
