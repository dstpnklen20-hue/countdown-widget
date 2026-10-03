package com.claudecode.countdown.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
fun now(): Long = System.currentTimeMillis()

/**
 * The stamp for an edit of a row stamped [previous]: now, but always later than [previous]. Sync
 * keeps the newer version of a row, so an edit must beat the one it changes even when this
 * device's clock is behind the device that wrote that version.
 */
fun stampAfter(previous: Long): Long = maxOf(now(), previous + 1)

enum class TaskStatus { OPEN, DONE, WONT_DO }
enum class DisplayMode { NORMAL, COUNTDOWN }
enum class RepeatFrom { DUE, COMPLETION }
enum class ListViewMode { LIST, KANBAN }
enum class WidgetKind { COUNTDOWN, TODAY, QUICK_ADD }

object Priority {
    const val NONE = 0
    const val LOW = 1
    const val MEDIUM = 2
    const val HIGH = 3
}

// Every synced entity carries id (UUID) + createdAt/updatedAt + a soft-delete flag.

@Entity(tableName = "folders")
data class Folder(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val sortOrder: Long = 0,
    val createdAt: Long = now(),
    val updatedAt: Long = createdAt,
    val deleted: Boolean = false,
)

@Entity(tableName = "task_lists", indices = [Index("folderId")])
data class TaskList(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val color: Int? = null,
    val folderId: String? = null,
    val sortOrder: Long = 0,
    val viewMode: ListViewMode = ListViewMode.LIST,
    val isInbox: Boolean = false,
    val createdAt: Long = now(),
    val updatedAt: Long = createdAt,
    val deleted: Boolean = false,
) {
    companion object {
        const val INBOX_ID = "inbox"
    }
}

@Entity(tableName = "sections", indices = [Index("listId")])
data class Section(
    @PrimaryKey val id: String = newId(),
    val listId: String,
    val name: String,
    val sortOrder: Long = 0,
    val createdAt: Long = now(),
    val updatedAt: Long = createdAt,
    val deleted: Boolean = false,
)

/**
 * [dueAt]/[startAt] are UTC millis; for all-day tasks they point at local midnight of [timeZone].
 * [repeatRule] is an RFC 5545 RRULE body, e.g. "FREQ=WEEKLY;BYDAY=MO,WE".
 */
@Entity(
    tableName = "tasks",
    indices = [Index("listId"), Index("parentId"), Index("dueAt"), Index("status")]
)
data class Task(
    @PrimaryKey val id: String = newId(),
    val listId: String = TaskList.INBOX_ID,
    val sectionId: String? = null,
    val parentId: String? = null,
    val title: String,
    val content: String = "",
    val priority: Int = Priority.NONE,
    val status: TaskStatus = TaskStatus.OPEN,
    val startAt: Long? = null,
    val dueAt: Long? = null,
    val isAllDay: Boolean = false,
    val timeZone: String? = null,
    val repeatRule: String? = null,
    val repeatFrom: RepeatFrom = RepeatFrom.DUE,
    val completedAt: Long? = null,
    val sortOrder: Long = 0,
    val displayMode: DisplayMode = DisplayMode.NORMAL,
    /**
     * An event (sleep, lunch, a lecture) rather than a task: it fills [startAt]..[dueAt] in the
     * calendar, has nothing to tick and stays out of the task lists.
     */
    @ColumnInfo(defaultValue = "0") val isEvent: Boolean = false,
    /** ARGB colour the user picked for the task; null means the list colour is used. */
    val color: Int? = null,
    val createdAt: Long = now(),
    val updatedAt: Long = createdAt,
    val deleted: Boolean = false,
) {
    val isDone: Boolean get() = status != TaskStatus.OPEN
}

@Entity(tableName = "checklist_items", indices = [Index("taskId")])
data class ChecklistItem(
    @PrimaryKey val id: String = newId(),
    val taskId: String,
    val title: String,
    val checked: Boolean = false,
    val sortOrder: Long = 0,
    val createdAt: Long = now(),
    val updatedAt: Long = createdAt,
    val deleted: Boolean = false,
)

@Entity(tableName = "tags")
data class Tag(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val color: Int? = null,
    val parentId: String? = null,
    val sortOrder: Long = 0,
    val createdAt: Long = now(),
    val updatedAt: Long = createdAt,
    val deleted: Boolean = false,
)

/** A removed tag stays as a deleted link (v4), so the removal reaches other devices. */
@Entity(tableName = "task_tags", primaryKeys = ["taskId", "tagId"], indices = [Index("tagId")])
data class TaskTag(
    val taskId: String,
    val tagId: String,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = now(),
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

/** Fires [offsetMinutes] before the task's due time, or at [absoluteAt] when set. */
@Entity(tableName = "reminders", indices = [Index("taskId")])
data class Reminder(
    @PrimaryKey val id: String = newId(),
    val taskId: String,
    val offsetMinutes: Int? = 0,
    val absoluteAt: Long? = null,
    val snoozedUntil: Long? = null,
    val createdAt: Long = now(),
    val updatedAt: Long = createdAt,
    val deleted: Boolean = false,
)

/** Local-only: which home-screen widget shows what. Not synced. */
@Entity(tableName = "widget_bindings")
data class WidgetBinding(
    @PrimaryKey val appWidgetId: Int,
    val kind: WidgetKind,
    val taskId: String? = null,
    val listId: String? = null,
)

enum class FocusKind { FOCUS, SHORT_BREAK, LONG_BREAK }

@Entity(tableName = "focus_sessions", indices = [Index("taskId"), Index("startedAt")])
data class FocusSession(
    @PrimaryKey val id: String = newId(),
    val taskId: String? = null,
    val kind: FocusKind = FocusKind.FOCUS,
    val startedAt: Long,
    val endedAt: Long,
    val durationMs: Long,
    val createdAt: Long = now(),
    val updatedAt: Long = createdAt,
    val deleted: Boolean = false,
)

/**
 * [days] lists RRULE weekday codes ("MO,WE,FR"); empty means every day.
 * [reminderMinute] is the minute of the day for a daily reminder, null for none.
 */
@Entity(tableName = "habits")
data class Habit(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val emoji: String = "✅",
    val color: Int? = null,
    val days: String = "",
    val goal: Int = 1,
    val reminderMinute: Int? = null,
    val archived: Boolean = false,
    val sortOrder: Long = 0,
    val createdAt: Long = now(),
    val updatedAt: Long = createdAt,
    val deleted: Boolean = false,
)

/** Progress of a habit on one day ([day] is LocalDate.toEpochDay()). */
@Entity(tableName = "habit_checkins", indices = [Index(value = ["habitId", "day"], unique = true)])
data class HabitCheckIn(
    @PrimaryKey val id: String = newId(),
    val habitId: String,
    val day: Long,
    val count: Int,
    val createdAt: Long = now(),
    val updatedAt: Long = createdAt,
    val deleted: Boolean = false,
)
