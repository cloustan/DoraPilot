package com.dorapilot.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * Resolves a contact name to a phone number (READ_CONTACTS), used only when the
 * Contacts source is enabled - e.g. so "call mom" can dial the right number.
 * We never bulk-export the address book; lookups are by explicit name only.
 */
class ContactsReader(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun resolveNumber(name: String): String? {
        val query = name.trim()
        if (query.isEmpty() || !hasPermission()) return null
        return runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$query%"),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(1)?.replace(" ", "") else null
            }
        }.getOrNull()
    }
}
