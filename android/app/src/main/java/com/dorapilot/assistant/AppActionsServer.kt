package com.dorapilot.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import org.json.JSONObject

/**
 * Cross-app control via standard Android intents. These let Dora drive other
 * apps (Clock, Camera, Maps, Calendar, Spotify, YouTube, etc.) without any
 * app-specific SDKs. Where the platform supports it we skip the confirmation UI
 * (alarms/timers); actions that inherently need user confirmation open the
 * relevant app screen.
 */
class AppActionsServer(private val context: Context) {

    private fun launch(intent: Intent, spoken: String, extra: JSONObject.() -> Unit = {}): JSONObject {
        return runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            JSONObject().put("ok", true).put("spoken", spoken).apply(extra)
        }.getOrElse { error ->
            JSONObject().put("ok", false).put("error", error.message ?: "action failed")
        }
    }

    fun setAlarm(hour: Int, minute: Int, label: String, skipUi: Boolean = true): JSONObject {
        if (hour !in 0..23 || minute !in 0..59) {
            return JSONObject().put("ok", false).put("error", "Invalid alarm time")
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
        if (label.isNotBlank()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        val display = "%02d:%02d".format(hour, minute)
        return launch(intent, "Alarm set for $display.") { put("time", display) }
    }

    fun setTimer(seconds: Int, label: String, skipUi: Boolean = true): JSONObject {
        if (seconds <= 0) return JSONObject().put("ok", false).put("error", "Invalid timer length")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
        if (label.isNotBlank()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return launch(intent, "Timer set for ${humanDuration(seconds)}.") { put("seconds", seconds) }
    }

    fun openCamera(video: Boolean = false): JSONObject {
        val action = if (video) MediaStore.INTENT_ACTION_VIDEO_CAMERA
        else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        return launch(Intent(action), if (video) "Opening video camera." else "Opening camera.")
    }

    fun startNavigation(query: String, mode: String): JSONObject {
        val q = query.trim()
        if (q.isEmpty()) return JSONObject().put("ok", false).put("error", "destination is required")
        val travelMode = when (mode.trim().lowercase()) {
            "walk", "walking", "w" -> "w"
            "bike", "bicycling", "b" -> "b"
            "transit", "transport", "r" -> "r"
            else -> "d"
        }
        val uri = Uri.parse("google.navigation:q=${Uri.encode(q)}&mode=$travelMode")
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")
        val resolved = intent.resolveActivity(context.packageManager) != null
        val finalIntent = if (resolved) intent else
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(q)}"))
        return launch(finalIntent, "Navigating to $q.") { put("destination", q) }
    }

    fun createCalendarEvent(title: String, beginMs: Long, endMs: Long, location: String): JSONObject {
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
        if (beginMs > 0) intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginMs)
        if (endMs > 0) intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
        if (location.isNotBlank()) intent.putExtra(CalendarContract.Events.EVENT_LOCATION, location)
        return launch(intent, "Opening a new calendar event${if (title.isNotBlank()) " for $title" else ""}.")
    }

    fun playOnSpotify(query: String): JSONObject {
        val q = query.trim()
        if (q.isEmpty()) return JSONObject().put("ok", false).put("error", "query is required")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${Uri.encode(q)}"))
            .setPackage("com.spotify.music")
        val resolved = intent.resolveActivity(context.packageManager) != null
        val finalIntent = if (resolved) intent else
            Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/${Uri.encode(q)}"))
        return launch(finalIntent, "Searching Spotify for $q.") { put("query", q) }
    }

    fun playOnYouTube(query: String): JSONObject {
        val q = query.trim()
        if (q.isEmpty()) return JSONObject().put("ok", false).put("error", "query is required")
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(q)}")
        ).setPackage("com.google.android.youtube")
        val resolved = intent.resolveActivity(context.packageManager) != null
        val finalIntent = if (resolved) intent else
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(q)}"))
        return launch(finalIntent, "Searching YouTube for $q.") { put("query", q) }
    }

    fun openUrl(url: String): JSONObject {
        val u = url.trim()
        if (u.isEmpty()) return JSONObject().put("ok", false).put("error", "url is required")
        val normalized = if (u.startsWith("http")) u else "https://$u"
        return launch(Intent(Intent.ACTION_VIEW, Uri.parse(normalized)), "Opening $normalized.")
    }

    fun dialNumber(phone: String): JSONObject {
        val normalized = phone.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (normalized.isBlank()) return JSONObject().put("ok", false).put("error", "phone number is required")
        return launch(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$normalized")),
            "Opening dialer for $normalized."
        ) { put("phone", normalized) }
    }

    fun sendSms(phone: String, body: String): JSONObject {
        val normalized = phone.filter { it.isDigit() || it == '+' }
        if (normalized.isBlank()) return JSONObject().put("ok", false).put("error", "phone number is required")
        return launch(
            Intent(Intent.ACTION_VIEW, Uri.parse("sms:$normalized")).putExtra("sms_body", body),
            "Opening message composer for $normalized."
        ) {
            put("phone", normalized)
            put("body", body)
        }
    }

    private fun humanDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        val parts = mutableListOf<String>()
        if (h > 0) parts += "$h hour${if (h > 1) "s" else ""}"
        if (m > 0) parts += "$m minute${if (m > 1) "s" else ""}"
        if (s > 0 && h == 0) parts += "$s second${if (s > 1) "s" else ""}"
        return parts.joinToString(" ").ifEmpty { "$seconds seconds" }
    }
}
