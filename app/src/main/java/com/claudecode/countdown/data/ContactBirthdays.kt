package com.claudecode.countdown.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.claudecode.countdown.domain.Birthday
import java.time.MonthDay

/** Birthdays from the phone's contacts (read only, nothing is stored). */
object ContactBirthdays {
    fun allowed(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun load(context: Context): List<Birthday> {
        if (!allowed(context)) return emptyList()
        val event = ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE
        val projection = arrayOf(ContactsContract.Data.DISPLAY_NAME, ContactsContract.CommonDataKinds.Event.START_DATE)
        val where = "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ?"
        val args = arrayOf(event, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString())
        return runCatching {
            context.contentResolver.query(ContactsContract.Data.CONTENT_URI, projection, where, args, null)?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val name = c.getString(0) ?: continue
                        parse(name, c.getString(1) ?: continue)?.let(::add)
                    }
                }
            }.orEmpty().distinctBy { it.name to it.day }
        }.getOrDefault(emptyList())
    }

    /** Contacts keep birthdays as "1990-05-17", "--05-17" (no year) or "1990-05-17T00:00:00Z". */
    fun parse(name: String, raw: String): Birthday? {
        val match = Regex("""^(\d{4}|-)-?(\d{2})-(\d{2})""").find(raw.trim()) ?: return null
        val (year, month, day) = match.destructured
        val md = runCatching { MonthDay.of(month.toInt(), day.toInt()) }.getOrNull() ?: return null
        return Birthday(name, md, year.toIntOrNull())
    }
}
