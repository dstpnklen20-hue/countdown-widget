package com.claudecode.countdown.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class Progress(val taskId: String, val total: Int, val done: Int)

data class TaskTagName(val taskId: String, val tagId: String, val name: String, val color: Int?)

@Dao
interface TaskDao {
    @Upsert
    suspend fun upsert(task: Task)

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: String): Task?

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observe(id: String): Flow<Task?>

    @Query("SELECT * FROM tasks WHERE deleted = 0 AND parentId IS NULL")
    fun observeTopLevel(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE deleted = 0 AND parentId IS NULL")
    suspend fun topLevel(): List<Task>

    @Query("SELECT * FROM tasks WHERE deleted = 0 AND parentId = :parentId ORDER BY sortOrder, createdAt")
    fun observeSubtasks(parentId: String): Flow<List<Task>>

    @Query(
        "SELECT parentId AS taskId, COUNT(*) AS total, " +
            "SUM(CASE WHEN status != 'OPEN' THEN 1 ELSE 0 END) AS done " +
            "FROM tasks WHERE deleted = 0 AND parentId IS NOT NULL GROUP BY parentId"
    )
    fun observeSubtaskProgress(): Flow<List<Progress>>

    @Query("SELECT * FROM tasks WHERE deleted = 0 AND displayMode = 'COUNTDOWN' AND dueAt IS NOT NULL ORDER BY dueAt")
    suspend fun countdowns(): List<Task>

    /** Tasks a countdown widget can show: countdowns first, then other open dated tasks. */
    @Query(
        "SELECT * FROM tasks WHERE deleted = 0 AND status = 'OPEN' AND dueAt IS NOT NULL " +
            "ORDER BY displayMode = 'COUNTDOWN' DESC, dueAt"
    )
    suspend fun widgetCandidates(): List<Task>

    /** Events of the "time to work" kind: reminders during them come silently. */
    @Query("SELECT * FROM tasks WHERE deleted = 0 AND isEvent = 1 AND eventType = 'FOCUS'")
    suspend fun focusEvents(): List<Task>

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM tasks")
    suspend fun maxSortOrder(): Long

    /** When tasks (subtasks included) were completed, for statistics. */
    @Query("SELECT completedAt FROM tasks WHERE deleted = 0 AND status = 'DONE' AND completedAt >= :since")
    fun observeCompletedSince(since: Long): Flow<List<Long>>

    @Query("SELECT COUNT(*) FROM tasks WHERE deleted = 0 AND status = 'DONE'")
    fun observeCompletedCount(): Flow<Int>

    // Already deleted rows keep their stamp, so undo does not bring back what was deleted earlier.
    @Query("UPDATE tasks SET deleted = 1, updatedAt = max(:at, updatedAt + 1) WHERE (id = :id OR parentId = :id) AND deleted = 0")
    suspend fun softDelete(id: String, at: Long = now())

    @Query("UPDATE tasks SET deleted = 1, updatedAt = max(:at, updatedAt + 1) WHERE listId = :listId AND deleted = 0")
    suspend fun softDeleteInList(listId: String, at: Long = now())

    /** Undoes one deletion: the task plus subtasks deleted with it (ones deleted earlier are stamped before [deletedAt]). */
    @Query(
        "UPDATE tasks SET deleted = 0, updatedAt = max(:at, updatedAt + 1) " +
            "WHERE deleted = 1 AND (id = :id OR (parentId = :id AND updatedAt >= :deletedAt))"
    )
    suspend fun restore(id: String, deletedAt: Long, at: Long = now())

    @Query("UPDATE tasks SET deleted = 0, updatedAt = max(:at, updatedAt + 1) WHERE deleted = 1 AND listId = :listId AND updatedAt >= :deletedAt")
    suspend fun restoreInList(listId: String, deletedAt: Long, at: Long = now())

    /** Deleted tasks, newest first; subtasks only while their parent is alive (otherwise they return with it). */
    @Query(
        "SELECT * FROM tasks WHERE deleted = 1 AND " +
            "(parentId IS NULL OR parentId IN (SELECT id FROM tasks WHERE deleted = 0)) ORDER BY updatedAt DESC"
    )
    fun observeTrash(): Flow<List<Task>>

    @Query("SELECT id FROM tasks WHERE id = :id OR parentId = :id")
    suspend fun idsWithSubtasks(id: String): List<String>

    @Query("SELECT id FROM tasks WHERE deleted = 1 AND updatedAt < :before")
    suspend fun deletedBefore(before: Long): List<String>

    @Query("SELECT id FROM tasks WHERE deleted = 1")
    suspend fun deletedIds(): List<String>

    @Query("DELETE FROM tasks WHERE id IN (:ids)")
    suspend fun purgeTasks(ids: List<String>)

    @Query("DELETE FROM checklist_items WHERE taskId IN (:ids)")
    suspend fun purgeChecklist(ids: List<String>)

    @Query("DELETE FROM reminders WHERE taskId IN (:ids)")
    suspend fun purgeReminders(ids: List<String>)

    @Query("DELETE FROM task_tags WHERE taskId IN (:ids)")
    suspend fun purgeTaskTags(ids: List<String>)

    /** Removes tasks for good together with everything attached to them. */
    @Transaction
    suspend fun purge(ids: List<String>) {
        // SQLite caps the number of bound parameters per statement.
        for (chunk in ids.chunked(500)) {
            purgeChecklist(chunk)
            purgeReminders(chunk)
            purgeTaskTags(chunk)
            purgeTasks(chunk)
        }
    }
}

@Dao
interface ChecklistDao {
    @Upsert
    suspend fun upsert(item: ChecklistItem)

    @Query("SELECT * FROM checklist_items WHERE deleted = 0 AND taskId = :taskId ORDER BY sortOrder, createdAt")
    fun observeForTask(taskId: String): Flow<List<ChecklistItem>>

    @Query("SELECT * FROM checklist_items WHERE deleted = 0 AND taskId = :taskId ORDER BY sortOrder, createdAt")
    suspend fun forTask(taskId: String): List<ChecklistItem>

    @Query(
        "SELECT taskId, COUNT(*) AS total, SUM(CASE WHEN checked THEN 1 ELSE 0 END) AS done " +
            "FROM checklist_items WHERE deleted = 0 GROUP BY taskId"
    )
    fun observeProgress(): Flow<List<Progress>>

    @Query("UPDATE checklist_items SET deleted = 1, updatedAt = max(:at, updatedAt + 1) WHERE id = :id")
    suspend fun softDelete(id: String, at: Long = now())

    @Query("UPDATE checklist_items SET checked = 0, updatedAt = max(:at, updatedAt + 1) WHERE taskId = :taskId AND checked = 1")
    suspend fun uncheckAll(taskId: String, at: Long = now())
}

@Dao
interface TaskListDao {
    @Upsert
    suspend fun upsert(list: TaskList)

    @Query("SELECT * FROM task_lists WHERE id = :id")
    suspend fun get(id: String): TaskList?

    @Query("SELECT * FROM task_lists WHERE deleted = 0 ORDER BY sortOrder, createdAt")
    fun observeAll(): Flow<List<TaskList>>

    @Query("SELECT * FROM task_lists WHERE deleted = 0 ORDER BY sortOrder, createdAt")
    suspend fun all(): List<TaskList>

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM task_lists")
    suspend fun maxSortOrder(): Long
}

@Dao
interface SectionDao {
    @Upsert
    suspend fun upsert(section: Section)

    @Query("SELECT * FROM sections WHERE deleted = 0 ORDER BY sortOrder, createdAt")
    fun observeAll(): Flow<List<Section>>

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM sections WHERE listId = :listId")
    suspend fun maxSortOrder(listId: String): Long

    @Query("UPDATE tasks SET sectionId = NULL, updatedAt = max(:at, updatedAt + 1) WHERE sectionId = :id")
    suspend fun detachTasks(id: String, at: Long = now())

    @Query("UPDATE sections SET deleted = 1, updatedAt = max(:at, updatedAt + 1) WHERE id = :id")
    suspend fun softDelete(id: String, at: Long = now())
}

@Dao
interface FolderDao {
    @Upsert
    suspend fun upsert(folder: Folder)

    @Query("SELECT * FROM folders WHERE deleted = 0 ORDER BY sortOrder, createdAt")
    fun observeAll(): Flow<List<Folder>>

    @Query("UPDATE task_lists SET folderId = NULL, updatedAt = max(:at, updatedAt + 1) WHERE folderId = :id")
    suspend fun detachLists(id: String, at: Long = now())

    @Query("UPDATE folders SET deleted = 1, updatedAt = max(:at, updatedAt + 1) WHERE id = :id")
    suspend fun softDelete(id: String, at: Long = now())
}

@Dao
interface TagDao {
    @Upsert
    suspend fun upsert(tag: Tag)

    @Query("SELECT * FROM tags WHERE deleted = 0 ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<Tag>>

    @Query("SELECT * FROM tags WHERE id = :id AND deleted = 0")
    suspend fun get(id: String): Tag?

    @Query("SELECT * FROM tags WHERE deleted = 0 AND name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(name: String): Tag?

    @Query(
        "SELECT tt.taskId AS taskId, t.id AS tagId, t.name AS name, t.color AS color " +
            "FROM task_tags tt JOIN tags t ON t.id = tt.tagId WHERE tt.deleted = 0 AND t.deleted = 0 ORDER BY t.name"
    )
    fun observeTaskTags(): Flow<List<TaskTagName>>

    @Query(
        "SELECT t.name FROM task_tags tt JOIN tags t ON t.id = tt.tagId " +
            "WHERE tt.taskId = :taskId AND tt.deleted = 0 AND t.deleted = 0"
    )
    suspend fun tagNamesFor(taskId: String): List<String>

    @Query("SELECT * FROM task_tags WHERE taskId = :taskId")
    suspend fun linksFor(taskId: String): List<TaskTag>

    @Upsert
    suspend fun upsertTaskTags(links: List<TaskTag>)

    /** Links that change are stamped (removed ones stay as deleted), so other devices learn about it. */
    @Transaction
    suspend fun setTaskTags(taskId: String, tagIds: Collection<String>, at: Long = now()) {
        val keep = tagIds.toSet()
        val current = linksFor(taskId).associateBy { it.tagId }
        val changes = current.values.filter { !it.deleted && it.tagId !in keep }.map { it.copy(deleted = true, updatedAt = maxOf(at, it.updatedAt + 1)) } +
            keep.filter { current[it]?.deleted != false }.map { TaskTag(taskId, it, updatedAt = maxOf(at, (current[it]?.updatedAt ?: 0) + 1)) }
        if (changes.isNotEmpty()) upsertTaskTags(changes)
    }

    @Query("UPDATE tags SET deleted = 1, updatedAt = max(:at, updatedAt + 1) WHERE id = :id")
    suspend fun softDelete(id: String, at: Long = now())
}

@Dao
interface ReminderDao {
    @Upsert
    suspend fun upsert(reminder: Reminder)

    @Query("SELECT * FROM reminders WHERE deleted = 0 AND taskId = :taskId ORDER BY offsetMinutes")
    fun observeForTask(taskId: String): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders WHERE deleted = 0 AND taskId = :taskId")
    suspend fun forTask(taskId: String): List<Reminder>

    @Query(
        "SELECT r.* FROM reminders r JOIN tasks t ON t.id = r.taskId " +
            "WHERE r.deleted = 0 AND t.deleted = 0 AND t.status = 'OPEN'"
    )
    suspend fun activeReminders(): List<Reminder>

    @Query(
        "SELECT * FROM tasks WHERE deleted = 0 AND status = 'OPEN' " +
            "AND id IN (SELECT taskId FROM reminders WHERE deleted = 0)"
    )
    suspend fun tasksWithReminders(): List<Task>

    @Query("UPDATE reminders SET deleted = 1, updatedAt = max(:at, updatedAt + 1) WHERE id = :id")
    suspend fun softDelete(id: String, at: Long = now())
}

@Dao
interface WidgetBindingDao {
    @Upsert
    suspend fun upsert(binding: WidgetBinding)

    @Query("SELECT * FROM widget_bindings WHERE appWidgetId = :appWidgetId")
    suspend fun get(appWidgetId: Int): WidgetBinding?

    @Query("DELETE FROM widget_bindings WHERE appWidgetId = :appWidgetId")
    suspend fun delete(appWidgetId: Int)
}

@Dao
interface FocusDao {
    @Upsert
    suspend fun upsert(session: FocusSession)

    @Query("SELECT * FROM focus_sessions WHERE deleted = 0 AND kind = 'FOCUS' AND startedAt >= :since ORDER BY startedAt DESC")
    fun observeFocusSince(since: Long): Flow<List<FocusSession>>

    @Query("SELECT COALESCE(SUM(durationMs), 0) FROM focus_sessions WHERE deleted = 0 AND kind = 'FOCUS'")
    fun observeTotalFocusMs(): Flow<Long>

    @Query("SELECT COUNT(*) FROM focus_sessions WHERE deleted = 0 AND kind = 'FOCUS'")
    fun observeFocusCount(): Flow<Int>
}

@Dao
interface HabitDao {
    @Upsert
    suspend fun upsert(habit: Habit)

    @Query("SELECT * FROM habits WHERE deleted = 0 AND archived = 0 ORDER BY sortOrder, createdAt")
    fun observeActive(): Flow<List<Habit>>

    @Query("SELECT * FROM habits WHERE deleted = 0 AND archived = 0")
    suspend fun active(): List<Habit>

    @Query("SELECT * FROM habits WHERE deleted = 0 AND archived = 1 ORDER BY updatedAt DESC")
    fun observeArchived(): Flow<List<Habit>>

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM habits")
    suspend fun maxSortOrder(): Long

    @Query("SELECT * FROM habit_checkins WHERE deleted = 0 AND count > 0")
    fun observeCheckIns(): Flow<List<HabitCheckIn>>

    @Query("SELECT * FROM habit_checkins WHERE habitId = :habitId AND day = :day")
    suspend fun checkIn(habitId: String, day: Long): HabitCheckIn?

    @Upsert
    suspend fun upsertCheckIn(checkIn: HabitCheckIn)

    @Query("UPDATE habits SET deleted = 1, updatedAt = max(:at, updatedAt + 1) WHERE id = :id")
    suspend fun softDelete(id: String, at: Long = now())
}

@Dao
interface CalendarDao {
    @Upsert
    suspend fun upsert(calendar: CalendarLayer)

    @Query("SELECT * FROM calendars WHERE deleted = 0 ORDER BY sortOrder, createdAt")
    fun observeAll(): Flow<List<CalendarLayer>>

    @Query("SELECT * FROM calendars WHERE deleted = 0 ORDER BY sortOrder, createdAt")
    suspend fun all(): List<CalendarLayer>

    @Query("SELECT * FROM calendars WHERE id = :id")
    suspend fun get(id: String): CalendarLayer?

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM calendars")
    suspend fun maxSortOrder(): Long

    /** Events of a removed calendar go with it to the trash. */
    @Query("UPDATE tasks SET deleted = 1, updatedAt = max(:at, updatedAt + 1) WHERE calendarId = :id AND deleted = 0")
    suspend fun deleteEvents(id: String, at: Long = now())

    @Query("UPDATE tasks SET deleted = 0, updatedAt = max(:at, updatedAt + 1) WHERE calendarId = :id AND deleted = 1 AND updatedAt >= :deletedAt")
    suspend fun restoreEvents(id: String, deletedAt: Long, at: Long = now())

    /** The occurrences taken out of a series on their own. */
    @Query("SELECT * FROM tasks WHERE seriesId = :seriesId AND deleted = 0")
    suspend fun exceptionsOf(seriesId: String): List<Task>
}
