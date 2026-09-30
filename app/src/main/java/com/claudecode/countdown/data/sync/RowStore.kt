package com.claudecode.countdown.data.sync

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.claudecode.countdown.data.db.AppDatabase
import org.json.JSONObject

/**
 * Tables that travel between devices, both in a backup file and through sync. Rows are identified
 * by [key] columns; widget bindings stay on the device and are not listed.
 */
enum class SyncTable(val table: String, val key: List<String> = listOf("id")) {
    FOLDERS("folders"),
    LISTS("task_lists"),
    SECTIONS("sections"),
    TASKS("tasks"),
    CHECKLIST("checklist_items"),
    TAGS("tags"),
    TASK_TAGS("task_tags", listOf("taskId", "tagId")),
    REMINDERS("reminders"),
    FOCUS("focus_sessions"),
    HABITS("habits"),
    CHECKINS("habit_checkins");

    fun idOf(row: JSONObject): String = key.joinToString("|") { row.getString(it) }

    companion object {
        fun byTable(name: String): SyncTable? = entries.firstOrNull { it.table == name }
    }
}

/** A row that lost a merge and should be removed from other copies too (see [RowStore.merge]). */
data class Superseded(val table: SyncTable, val id: String)

data class MergeResult(val changed: Int, val superseded: List<Superseded>)

/**
 * Reads and writes rows as JSON objects keyed by column name, straight from SQLite, so it needs no
 * per-entity code and survives schema changes: columns this version doesn't know are ignored.
 * Every synced row has `updatedAt` and `deleted`; merging keeps whichever version is newer.
 */
class RowStore(private val db: AppDatabase) {

    private val columns = HashMap<SyncTable, Map<String, String>>()

    /** Column name → declared SQLite type of [table]. */
    private fun columnsOf(table: SyncTable): Map<String, String> = columns.getOrPut(table) {
        db.openHelper.readableDatabase.query("PRAGMA table_info(`${table.table}`)").use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(c.getColumnIndexOrThrow("name")), c.getString(c.getColumnIndexOrThrow("type"))) }
        }
    }

    fun all(table: SyncTable): List<JSONObject> = rows("SELECT * FROM `${table.table}`")

    /** Rows written at or after [since] (device clock), deleted ones included. */
    fun changedSince(table: SyncTable, since: Long): List<JSONObject> =
        rows("SELECT * FROM `${table.table}` WHERE updatedAt >= ?", since)

    private fun rows(sql: String, vararg args: Any): List<JSONObject> =
        db.openHelper.readableDatabase.query(sql, args).use { c ->
            buildList { while (c.moveToNext()) add(c.toJson()) }
        }

    private fun Cursor.toJson(): JSONObject {
        val o = JSONObject()
        for (i in 0 until columnCount) {
            val name = getColumnName(i)
            when (getType(i)) {
                Cursor.FIELD_TYPE_NULL -> o.put(name, JSONObject.NULL)
                Cursor.FIELD_TYPE_INTEGER -> o.put(name, getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> o.put(name, getDouble(i))
                Cursor.FIELD_TYPE_STRING -> o.put(name, getString(i))
                else -> Unit // no blobs in the schema
            }
        }
        return o
    }

    /**
     * Last write wins: a row is stored when this device doesn't have it or has an older version.
     * Habit check-ins are unique per habit and day, so two devices can create competing rows for
     * the same day; the newer one wins and the loser is reported in [MergeResult.superseded].
     */
    fun merge(table: SyncTable, incoming: List<JSONObject>): MergeResult {
        if (incoming.isEmpty()) return MergeResult(0, emptyList())
        val known = columnsOf(table)
        var changed = 0
        val superseded = mutableListOf<Superseded>()
        db.runInTransaction {
            val sql = db.openHelper.writableDatabase
            for (row in incoming) {
                if (!table.key.all { row.has(it) && !row.isNull(it) }) continue
                val updatedAt = row.optLong("updatedAt", 0)
                val where = table.key.joinToString(" AND ") { "`$it` = ?" }
                val keyArgs = table.key.map { row.getString(it) }.toTypedArray()
                val local = sql.query("SELECT updatedAt FROM `${table.table}` WHERE $where", keyArgs).use { c ->
                    if (c.moveToFirst()) c.getLong(0) else null
                }
                if (local != null && local >= updatedAt) continue
                if (table == SyncTable.CHECKINS && !resolveCheckIn(sql, row, superseded)) continue
                sql.insert("`${table.table}`", SQLiteDatabase.CONFLICT_REPLACE, row.toValues(known))
                changed++
            }
        }
        return MergeResult(changed, superseded)
    }

    /** Returns false when a newer local check-in for the same habit and day keeps its place. */
    private fun resolveCheckIn(
        sql: SupportSQLiteDatabase,
        row: JSONObject,
        superseded: MutableList<Superseded>,
    ): Boolean {
        val id = row.getString("id")
        val other = sql.query(
            "SELECT id, updatedAt FROM habit_checkins WHERE habitId = ? AND day = ? AND id != ?",
            arrayOf(row.optString("habitId"), row.optLong("day"), id),
        ).use { c -> if (c.moveToFirst()) c.getString(0) to c.getLong(1) else null } ?: return true
        val (otherId, otherUpdatedAt) = other
        val updatedAt = row.optLong("updatedAt")
        // Same stamp: the larger id wins, so every device picks the same row.
        val incomingWins = updatedAt > otherUpdatedAt || (updatedAt == otherUpdatedAt && id > otherId)
        return if (incomingWins) {
            sql.delete("habit_checkins", "id = ?", arrayOf(otherId))
            superseded += Superseded(SyncTable.CHECKINS, otherId)
            true
        } else {
            superseded += Superseded(SyncTable.CHECKINS, id)
            false
        }
    }

    private fun JSONObject.toValues(known: Map<String, String>): ContentValues {
        val v = ContentValues()
        for ((name, type) in known) {
            if (!has(name)) continue
            when (val value = get(name)) {
                JSONObject.NULL -> v.putNull(name)
                is Boolean -> v.put(name, if (value) 1L else 0L)
                is Number -> if (type.equals("TEXT", ignoreCase = true)) v.put(name, value.toString()) else v.put(name, value.toLong())
                is String -> if (type.equals("INTEGER", ignoreCase = true)) v.put(name, value.toLongOrNull()) else v.put(name, value)
                else -> v.put(name, value.toString())
            }
        }
        return v
    }
}
