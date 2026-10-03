package com.claudecode.countdown.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.TimeZone

/**
 * Runs on every database open, before any query is served, so every entry point (activity,
 * widget, alarm) sees seeded data. Creates the Inbox and imports countdowns from the
 * SharedPreferences storage of versions 1.x. Both steps are idempotent; the old
 * preferences are left untouched as a backup.
 */
class DatabaseSeeder(private val context: Context) : RoomDatabase.Callback() {

    companion object {
        const val LEGACY_DATA_PREFS = "countdowns_data"
        const val LEGACY_WIDGET_PREFS = "countdowns_widget_map"
        private const val FLAG_PREFS = "db_migration"
        private const val KEY_LEGACY_IMPORTED = "legacy_countdowns_imported"
        /** "Павлин" from the calendar palette. */
        const val PERSONAL_CALENDAR_COLOR = 0xFF039BE5.toInt()
    }

    override fun onOpen(db: SupportSQLiteDatabase) {
        val flags = context.getSharedPreferences(FLAG_PREFS, Context.MODE_PRIVATE)
        val importLegacy = !flags.getBoolean(KEY_LEGACY_IMPORTED, false)

        db.beginTransaction()
        try {
            insertInbox(db)
            insertPersonalCalendar(db)
            if (importLegacy) importLegacyCountdowns(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (importLegacy) flags.edit().putBoolean(KEY_LEGACY_IMPORTED, true).commit()
    }

    private fun insertInbox(db: SupportSQLiteDatabase) {
        val at = now()
        db.insert("task_lists", SQLiteDatabase.CONFLICT_IGNORE, ContentValues().apply {
            put("id", TaskList.INBOX_ID)
            put("name", "Входящие")
            putNull("color")
            putNull("folderId")
            put("sortOrder", Long.MIN_VALUE)
            put("viewMode", ListViewMode.LIST.name)
            put("isInbox", 1)
            put("createdAt", at)
            put("updatedAt", at)
            put("deleted", 0)
        })
    }

    /** Every device has the same first calendar (same id), so sync keeps one copy of it. */
    private fun insertPersonalCalendar(db: SupportSQLiteDatabase) {
        db.insert("calendars", SQLiteDatabase.CONFLICT_IGNORE, ContentValues().apply {
            put("id", CalendarLayer.PERSONAL_ID)
            put("name", "Личное")
            put("color", PERSONAL_CALENDAR_COLOR)
            put("defaultReminder", 30)
            put("defaultAllDayReminder", 24 * 60)
            put("sortOrder", Long.MIN_VALUE)
            // Stamped at zero: any change the user makes on any device wins over the seed.
            put("createdAt", 0L)
            put("updatedAt", 0L)
            put("deleted", 0)
        })
    }

    private fun importLegacyCountdowns(db: SupportSQLiteDatabase) {
        val data = context.getSharedPreferences(LEGACY_DATA_PREFS, Context.MODE_PRIVATE)
        val ids = data.getStringSet("ids", emptySet()).orEmpty()
        val zone = TimeZone.getDefault().id
        val at = now()
        var order = 0L
        for (id in ids.sortedBy { data.getLong("target_$it", 0L) }) {
            val title = data.getString("title_$id", null) ?: continue
            val target = data.getLong("target_$id", -1L)
            if (target < 0) continue
            // Same id as the old countdown, so existing widget bindings keep pointing at it.
            db.insert("tasks", SQLiteDatabase.CONFLICT_IGNORE, ContentValues().apply {
                put("id", id)
                put("listId", TaskList.INBOX_ID)
                putNull("sectionId")
                putNull("parentId")
                put("title", title)
                put("content", "")
                put("priority", Priority.NONE)
                put("status", TaskStatus.OPEN.name)
                putNull("startAt")
                put("dueAt", target)
                put("isAllDay", 0)
                put("timeZone", zone)
                putNull("repeatRule")
                put("repeatFrom", RepeatFrom.DUE.name)
                putNull("completedAt")
                put("sortOrder", order++)
                put("displayMode", DisplayMode.COUNTDOWN.name)
                put("createdAt", at)
                put("updatedAt", at)
                put("deleted", 0)
            })
        }

        val widgets = context.getSharedPreferences(LEGACY_WIDGET_PREFS, Context.MODE_PRIVATE)
        for ((key, value) in widgets.all) {
            val appWidgetId = key.removePrefix("widget_").toIntOrNull() ?: continue
            val taskId = value as? String ?: continue
            db.insert("widget_bindings", SQLiteDatabase.CONFLICT_IGNORE, ContentValues().apply {
                put("appWidgetId", appWidgetId)
                put("kind", WidgetKind.COUNTDOWN.name)
                put("taskId", taskId)
                putNull("listId")
            })
        }
    }
}
