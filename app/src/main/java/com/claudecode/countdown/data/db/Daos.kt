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

    @Query("SELECT * FROM tasks WHERE deleted = 0 AND status = 'OPEN' AND dueAt IS NOT NULL")
    suspend fun openWithDueDate(): List<Task>

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM tasks")
    suspend fun maxSortOrder(): Long

    @Query("UPDATE tasks SET deleted = 1, updatedAt = :at WHERE id = :id OR parentId = :id")
    suspend fun softDelete(id: String, at: Long = now())

    @Query("UPDATE tasks SET deleted = 1, updatedAt = :at WHERE listId = :listId")
    suspend fun softDeleteInList(listId: String, at: Long = now())
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

    @Query("UPDATE checklist_items SET deleted = 1, updatedAt = :at WHERE id = :id")
    suspend fun softDelete(id: String, at: Long = now())

    @Query("UPDATE checklist_items SET checked = 0, updatedAt = :at WHERE taskId = :taskId AND checked = 1")
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
interface FolderDao {
    @Upsert
    suspend fun upsert(folder: Folder)

    @Query("SELECT * FROM folders WHERE deleted = 0 ORDER BY sortOrder, createdAt")
    fun observeAll(): Flow<List<Folder>>

    @Query("UPDATE task_lists SET folderId = NULL, updatedAt = :at WHERE folderId = :id")
    suspend fun detachLists(id: String, at: Long = now())

    @Query("UPDATE folders SET deleted = 1, updatedAt = :at WHERE id = :id")
    suspend fun softDelete(id: String, at: Long = now())
}

@Dao
interface TagDao {
    @Upsert
    suspend fun upsert(tag: Tag)

    @Query("SELECT * FROM tags WHERE deleted = 0 ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<Tag>>

    @Query("SELECT * FROM tags WHERE deleted = 0 AND name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(name: String): Tag?

    @Query(
        "SELECT tt.taskId AS taskId, t.id AS tagId, t.name AS name, t.color AS color " +
            "FROM task_tags tt JOIN tags t ON t.id = tt.tagId WHERE t.deleted = 0 ORDER BY t.name"
    )
    fun observeTaskTags(): Flow<List<TaskTagName>>

    @Query("SELECT t.name FROM task_tags tt JOIN tags t ON t.id = tt.tagId WHERE tt.taskId = :taskId AND t.deleted = 0")
    suspend fun tagNamesFor(taskId: String): List<String>

    @Query("DELETE FROM task_tags WHERE taskId = :taskId")
    suspend fun clearTaskTags(taskId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTaskTags(links: List<TaskTag>)

    @Transaction
    suspend fun setTaskTags(taskId: String, tagIds: Collection<String>) {
        clearTaskTags(taskId)
        insertTaskTags(tagIds.map { TaskTag(taskId, it) })
    }

    @Query("UPDATE tags SET deleted = 1, updatedAt = :at WHERE id = :id")
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

    @Query("UPDATE reminders SET deleted = 1, updatedAt = :at WHERE id = :id")
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
