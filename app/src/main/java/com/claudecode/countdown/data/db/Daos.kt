package com.claudecode.countdown.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Upsert
    suspend fun upsert(task: Task)

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: String): Task?

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observe(id: String): Flow<Task?>

    @Query("SELECT * FROM tasks WHERE deleted = 0 AND displayMode = 'COUNTDOWN' AND dueAt IS NOT NULL ORDER BY dueAt")
    suspend fun countdowns(): List<Task>

    @Query("UPDATE tasks SET deleted = 1, updatedAt = :at WHERE id = :id OR parentId = :id")
    suspend fun softDelete(id: String, at: Long = now())
}

@Dao
interface TaskListDao {
    @Upsert
    suspend fun upsert(list: TaskList)

    @Query("SELECT * FROM task_lists WHERE id = :id")
    suspend fun get(id: String): TaskList?
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
