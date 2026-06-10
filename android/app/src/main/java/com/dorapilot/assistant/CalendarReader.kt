package com.dorapilot.assistant

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Reads upcoming calendar events (READ_CALENDAR), used only when enabled. */
class CalendarReader(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun upcoming(limit: Int = 5, windowHours: Long = 48): JSONArray {
        val out = JSONArray()
        if (!hasPermission()) return out

        val now = System.currentTimeMillis()
        val end = now + windowHours * 60 * 60 * 1000
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let { builder ->
            ContentUris.appendId(builder, now)
            ContentUris.appendId(builder, end)
            builder.build()
        }
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY
        )

        runCatching {
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cursor ->
                val fmt = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a").withZone(ZoneId.systemDefault())
                while (cursor.moveToNext() && out.length() < limit) {
                    val title = cursor.getString(0)?.trim().orEmpty().ifEmpty { "(untitled event)" }
                    val begin = cursor.getLong(1)
                    val location = cursor.getString(3)?.trim().orEmpty()
                    val allDay = cursor.getInt(4) == 1
                    val whenText = if (allDay) "all day" else fmt.format(Instant.ofEpochMilli(begin))
                    out.put(
                        JSONObject()
                            .put("title", title)
                            .put("begin_ms", begin)
                            .put("when", whenText)
                            .put("starts_in_minutes", ((begin - now) / 60000).coerceAtLeast(0))
                            .apply { if (location.isNotEmpty()) put("location", location) }
                    )
                }
            }
        }
        return out
    }
}
