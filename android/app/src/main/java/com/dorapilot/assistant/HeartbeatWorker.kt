package com.dorapilot.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.util.Calendar

/**
 * The "heartbeat": a periodic tick (and on-demand poke) that runs any due
 * automation jobs headlessly via [HeadlessAgentRunner] and delivers the result
 * as a notification. This is Dora's autonomy — it acts unprompted, surviving
 * app close and reboot, without a persistent foreground service.
 */
class HeartbeatWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val ctx = applicationContext
            val now = System.currentTimeMillis()

            val jobs = PersonalContextStore.listJobs(ctx).filter { it.optBoolean("enabled", false) }
            if (jobs.isNotEmpty()) {
                val runner = HeadlessAgentRunner(ctx)
                for (job in jobs) {
                    if (!isDue(job, now)) continue
                    val id = job.optLong("id")
                    val title = job.optString("title", "Dora").ifBlank { "Dora" }
                    Log.i(TAG, "Running due job #$id: ${job.optString("goal").take(80)}")
                    val result = runCatching { runner.run(job.optString("goal", "")) }
                        .getOrElse { "Job error: ${it.message}" }
                    PersonalContextStore.markJobRun(ctx, id, result)
                    notifyResult(ctx, id.toInt(), title, result)
                }
            }

            // Scheduled OpenClaw-style skills (stream their own step notifications).
            val skills = PersonalContextStore.listSkills(ctx).filter { it.optBoolean("enabled", false) }
            if (skills.isNotEmpty()) {
                val skillServer = SkillServer(ctx, HttpBridgeServer())
                for (skill in skills) {
                    if (!isDue(skill, now)) continue
                    Log.i(TAG, "Running due skill #${skill.optLong("id")}: ${skill.optString("name")}")
                    runCatching { skillServer.execute(skill) }
                }
            }
            Result.success()
        }.getOrElse {
            Log.w(TAG, "Heartbeat failed", it)
            Result.success()
        }
    }

    private fun isDue(job: JSONObject, now: Long): Boolean {
        val lastRun = job.optLong("last_run", 0L)
        return when (job.optString("trigger_type", "interval")) {
            "interval" -> {
                val intervalMs = job.optInt("interval_min", 60).coerceAtLeast(1) * 60_000L
                now - lastRun >= intervalMs
            }
            "daily" -> {
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                val todayAt = cal.timeInMillis + job.optInt("at_minutes", 0) * 60_000L
                now >= todayAt && lastRun < todayAt
            }
            else -> false // event-triggered jobs run via other paths
        }
    }

    private fun notifyResult(context: Context, id: Int, title: String, body: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Dora automations", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") android.app.Notification.Builder(context)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body.take(120))
            .setStyle(android.app.Notification.BigTextStyle().bigText(body.take(1500)))
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_BASE + id, notification)
    }

    companion object {
        private const val TAG = "HeartbeatWorker"
        private const val CHANNEL_ID = "dora_automations"
        private const val NOTIFICATION_BASE = 47000
    }
}
