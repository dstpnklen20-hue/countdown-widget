package com.claudecode.countdown.data.sync

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.claudecode.countdown.data.db.AppDatabase
import com.claudecode.countdown.data.db.TaskList
import java.util.Locale

/**
 * Merges copies of the same thing made separately on two devices (the same task typed on both
 * phones, countdowns imported from version 1.x on each, two "Работа" lists): sync matches rows by
 * id, and such copies have different ids, so they would show up twice.
 *
 * Copies are found by content. The oldest copy stays (by creation time, then id, so every device
 * picks the same one); what hangs on the others (subtasks, checklist, tags, reminders, focus
 * sessions, widgets, check-ins) moves over to it, and the others go to the trash. Everything is
 * an ordinary stamped edit, so the result travels to the other devices like any change.
 *
 * With [arrived] (ids per table that just came from another device) only groups that mix a new
 * row with one this device already had are merged, so two identical tasks the user made on one
 * device on purpose stay as they are. Null merges every group: the one-off cleanup of duplicates
 * that synced before this existed.
 */
class Deduplicator(private val db: AppDatabase, private val clock: () -> Long = System::currentTimeMillis) {

    private class Row(val id: String, val createdAt: Long, val key: List<Any?>)

    private lateinit var sql: SupportSQLiteDatabase
    private var at = 0L
    private var changed = 0

    /** Returns how many rows changed. */
    fun run(arrived: Map<SyncTable, Set<String>>?): Int {
        if (arrived != null && arrived.values.all { it.isEmpty() }) return 0
        changed = 0
        at = clock()
        db.runInTransaction {
            sql = db.openHelper.writableDatabase
            fun pick(table: SyncTable) = arrived?.let { it[table].orEmpty() }

            merge("folders", "SELECT id, createdAt, name FROM folders WHERE deleted = 0", pick(SyncTable.FOLDERS)) { from, to ->
                repoint("task_lists", "folderId", from, to)
            }
            merge(
                "task_lists",
                "SELECT id, createdAt, name FROM task_lists WHERE deleted = 0 AND isInbox = 0 AND id != '${TaskList.INBOX_ID}'",
                pick(SyncTable.LISTS),
            ) { from, to ->
                repoint("tasks", "listId", from, to)
                repoint("sections", "listId", from, to)
            }
            merge("sections", "SELECT id, createdAt, listId, name FROM sections WHERE deleted = 0", pick(SyncTable.SECTIONS)) { from, to ->
                repoint("tasks", "sectionId", from, to)
            }
            merge("tags", "SELECT id, createdAt, name FROM tags WHERE deleted = 0", pick(SyncTable.TAGS)) { from, to ->
                repoint("tags", "parentId", from, to)
                moveLinks("tagId", from, to)
            }

            // Tasks first, then subtasks: merging two parents brings their subtasks together.
            val merged = HashSet<String>()
            val taskColumns = "id, createdAt, title, listId, ifnull(parentId, ''), ifnull(dueAt, -1), isAllDay, displayMode, ifnull(repeatRule, ''), status"
            for (level in listOf("parentId IS NULL", "parentId IS NOT NULL")) {
                merge("tasks", "SELECT $taskColumns FROM tasks WHERE deleted = 0 AND $level", pick(SyncTable.TASKS)) { from, to ->
                    merged += to
                    repoint("tasks", "parentId", from, to)
                    repoint("checklist_items", "taskId", from, to)
                    repoint("reminders", "taskId", from, to)
                    repoint("focus_sessions", "taskId", from, to)
                    moveLinks("taskId", from, to)
                    // Local only: no stamp to move.
                    sql.execSQL("UPDATE widget_bindings SET taskId = ? WHERE taskId = ?", arrayOf(to, from))
                }
            }
            // What two merged copies both had is now there twice: keep one of each.
            for (task in merged) {
                merge("checklist_items", "SELECT id, createdAt, title FROM checklist_items WHERE deleted = 0 AND taskId = ?", null, task)
                merge(
                    "reminders",
                    "SELECT id, createdAt, ifnull(offsetMinutes, -1), ifnull(absoluteAt, -1) FROM reminders WHERE deleted = 0 AND taskId = ?",
                    null,
                    task,
                )
            }
            merge(
                "focus_sessions",
                "SELECT id, createdAt, startedAt, durationMs, kind, ifnull(taskId, '') FROM focus_sessions WHERE deleted = 0",
                pick(SyncTable.FOCUS),
            )
            merge("habits", "SELECT id, createdAt, name FROM habits WHERE deleted = 0", pick(SyncTable.HABITS)) { from, to ->
                moveCheckIns(from, to)
            }
        }
        return changed
    }

    /**
     * Groups the rows of [query] (id, createdAt, then the columns that must match) and merges each
     * group into its oldest row: [moveChildren] moves what hangs on a copy, then the copy is deleted.
     */
    private fun merge(
        table: String,
        query: String,
        arrived: Set<String>?,
        vararg args: Any,
        moveChildren: (from: String, to: String) -> Unit = { _, _ -> },
    ) {
        val rows = sql.query(query, args).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val key = (2 until c.columnCount).map { i ->
                        if (c.isNull(i)) null else c.getString(i).trim().lowercase(Locale.ROOT)
                    }
                    add(Row(c.getString(0), c.getLong(1), key))
                }
            }
        }
        for (group in rows.groupBy { it.key }.values) {
            if (group.size < 2) continue
            if (arrived != null && (group.none { it.id in arrived } || group.all { it.id in arrived })) continue
            val keep = group.minWith(compareBy<Row>({ it.createdAt }, { it.id }))
            for (copy in group) {
                if (copy === keep) continue
                moveChildren(copy.id, keep.id)
                sql.execSQL("UPDATE `$table` SET deleted = 1, updatedAt = ? WHERE id = ?", arrayOf(at, copy.id))
                changed++
            }
        }
    }

    private fun repoint(table: String, column: String, from: String, to: String) {
        changed += sql.update(
            "`$table`",
            SQLiteDatabase.CONFLICT_NONE,
            ContentValues().apply { put(column, to); put("updatedAt", at) },
            "`$column` = ?",
            arrayOf(from),
        )
    }

    /**
     * Task–tag links are keyed by both ids, so a link can't be re-pointed in place: the copy's
     * link is marked deleted (that reaches other devices) and a link to [to] is made unless one
     * is already there. [column] is the side that changes ("taskId" or "tagId").
     */
    private fun moveLinks(column: String, from: String, to: String) {
        val other = if (column == "taskId") "tagId" else "taskId"
        val ends = sql.query("SELECT `$other` FROM task_tags WHERE `$column` = ? AND deleted = 0", arrayOf(from)).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        for (end in ends) {
            sql.execSQL("UPDATE task_tags SET deleted = 1, updatedAt = ? WHERE `$column` = ? AND `$other` = ?", arrayOf(at, from, end))
            sql.insert("task_tags", SQLiteDatabase.CONFLICT_REPLACE, ContentValues().apply {
                put(column, to)
                put(other, end)
                put("updatedAt", at)
                put("deleted", 0)
            })
            changed += 2
        }
    }

    /** Check-ins are unique per habit and day: a day both copies have keeps the larger count. */
    private fun moveCheckIns(from: String, to: String) {
        val days = sql.query("SELECT id, day, count, deleted FROM habit_checkins WHERE habitId = ?", arrayOf(from)).use { c ->
            buildList { while (c.moveToNext()) add(listOf(c.getString(0), c.getLong(1), c.getLong(2), c.getLong(3))) }
        }
        for ((id, day, count, deleted) in days) {
            val existing = sql.query("SELECT id FROM habit_checkins WHERE habitId = ? AND day = ?", arrayOf(to, day)).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
            if (existing == null) {
                sql.execSQL("UPDATE habit_checkins SET habitId = ?, updatedAt = ? WHERE id = ?", arrayOf(to, at, id))
            } else {
                if (deleted == 0L) {
                    sql.execSQL(
                        "UPDATE habit_checkins SET count = max(CASE WHEN deleted = 1 THEN 0 ELSE count END, ?), deleted = 0, updatedAt = ? WHERE id = ?",
                        arrayOf(count, at, existing),
                    )
                }
                sql.execSQL("UPDATE habit_checkins SET deleted = 1, updatedAt = ? WHERE id = ?", arrayOf(at, id))
            }
            changed++
        }
    }
}
