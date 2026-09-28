package com.claudecode.countdown.data

import com.claudecode.countdown.data.db.AppDatabase
import com.claudecode.countdown.data.db.ChecklistItem
import com.claudecode.countdown.data.db.Folder
import com.claudecode.countdown.data.db.Tag
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.data.db.TaskList
import com.claudecode.countdown.data.db.TaskStatus
import com.claudecode.countdown.data.db.DisplayMode
import com.claudecode.countdown.data.db.Reminder
import com.claudecode.countdown.data.db.newId
import com.claudecode.countdown.data.db.now
import com.claudecode.countdown.domain.nextOccurrence
import androidx.room.withTransaction

/**
 * Single write path for tasks and their satellites. [onChanged] refreshes widgets and alarms
 * after every write.
 */
class TaskRepository(
    private val db: AppDatabase,
    private val onChanged: suspend () -> Unit,
) {
    private val tasks = db.taskDao()
    private val checklist = db.checklistDao()
    private val tags = db.tagDao()
    private val lists = db.taskListDao()
    private val folders = db.folderDao()
    private val reminders = db.reminderDao()

    fun observeTopLevel() = tasks.observeTopLevel()
    fun observeTask(id: String) = tasks.observe(id)
    fun observeSubtasks(parentId: String) = tasks.observeSubtasks(parentId)
    fun observeSubtaskProgress() = tasks.observeSubtaskProgress()
    fun observeChecklist(taskId: String) = checklist.observeForTask(taskId)
    fun observeChecklistProgress() = checklist.observeProgress()
    fun observeTaskTags() = tags.observeTaskTags()
    fun observeTags() = tags.observeAll()
    fun observeLists() = lists.observeAll()
    fun observeFolders() = folders.observeAll()

    suspend fun get(id: String): Task? = tasks.get(id)

    suspend fun create(task: Task, tagNames: Collection<String> = emptyList()): Task {
        val created = task.copy(sortOrder = tasks.maxSortOrder() + 1)
        db.withTransaction {
            tasks.upsert(created)
            if (tagNames.isNotEmpty()) tags.setTaskTags(created.id, tagNames.map { ensureTag(it).id })
        }
        onChanged()
        return created
    }

    suspend fun update(task: Task) {
        tasks.upsert(task.copy(updatedAt = now()))
        onChanged()
    }

    /**
     * Completing a repeating task keeps it open and moves it to the next occurrence; a completed
     * copy is stored for history. The original id is kept so widgets and reminders follow the series.
     */
    suspend fun setDone(task: Task, done: Boolean) {
        val at = now()
        if (!done) {
            update(task.copy(status = TaskStatus.OPEN, completedAt = null))
            return
        }
        val next = task.nextOccurrence(at)
        if (next == null) {
            update(task.copy(status = TaskStatus.DONE, completedAt = at))
            return
        }
        db.withTransaction {
            tasks.upsert(
                task.copy(
                    id = newId(),
                    status = TaskStatus.DONE,
                    completedAt = at,
                    repeatRule = null,
                    displayMode = DisplayMode.NORMAL,
                    createdAt = at,
                    updatedAt = at,
                )
            )
            tasks.upsert(next.copy(updatedAt = at))
            checklist.uncheckAll(task.id, at)
        }
        onChanged()
    }

    suspend fun delete(id: String) {
        tasks.softDelete(id)
        onChanged()
    }

    suspend fun addChecklistItem(taskId: String, title: String) {
        val order = checklist.forTask(taskId).maxOfOrNull { it.sortOrder + 1 } ?: 0
        checklist.upsert(ChecklistItem(taskId = taskId, title = title, sortOrder = order))
        touch(taskId)
    }

    suspend fun updateChecklistItem(item: ChecklistItem) {
        checklist.upsert(item.copy(updatedAt = now()))
        touch(item.taskId)
    }

    suspend fun deleteChecklistItem(item: ChecklistItem) {
        checklist.softDelete(item.id)
        touch(item.taskId)
    }

    fun observeReminders(taskId: String) = reminders.observeForTask(taskId)

    suspend fun addReminder(taskId: String, offsetMinutes: Int) {
        if (reminders.forTask(taskId).any { it.offsetMinutes == offsetMinutes && it.absoluteAt == null }) return
        reminders.upsert(Reminder(taskId = taskId, offsetMinutes = offsetMinutes))
        touch(taskId)
    }

    suspend fun deleteReminder(reminder: Reminder) {
        reminders.softDelete(reminder.id)
        touch(reminder.taskId)
    }

    /** Pushes every reminder of the task to fire again in [minutes]. */
    suspend fun snooze(taskId: String, minutes: Int) {
        val until = now() + minutes * 60_000L
        for (r in reminders.forTask(taskId)) reminders.upsert(r.copy(snoozedUntil = until, updatedAt = now()))
        onChanged()
    }

    suspend fun setTags(taskId: String, names: Collection<String>) {
        db.withTransaction {
            tags.setTaskTags(taskId, names.map { ensureTag(it).id })
        }
        touch(taskId)
    }

    private suspend fun ensureTag(rawName: String): Tag {
        val name = rawName.trim().removePrefix("#")
        return tags.findByName(name) ?: Tag(name = name).also { tags.upsert(it) }
    }

    /** Bumps updatedAt so a future sync notices changes made to the task's children. */
    private suspend fun touch(taskId: String) {
        tasks.get(taskId)?.let { tasks.upsert(it.copy(updatedAt = now())) }
        onChanged()
    }

    // Lists, folders and tags

    suspend fun createList(name: String, color: Int?, folderId: String?): TaskList {
        val list = TaskList(name = name, color = color, folderId = folderId, sortOrder = lists.maxSortOrder() + 1)
        lists.upsert(list)
        return list
    }

    suspend fun updateList(list: TaskList) = lists.upsert(list.copy(updatedAt = now()))

    suspend fun deleteList(list: TaskList) {
        if (list.isInbox) return
        db.withTransaction {
            tasks.softDeleteInList(list.id)
            lists.upsert(list.copy(deleted = true, updatedAt = now()))
        }
        onChanged()
    }

    suspend fun createFolder(name: String): Folder = Folder(name = name).also { folders.upsert(it) }

    suspend fun updateFolder(folder: Folder) = folders.upsert(folder.copy(updatedAt = now()))

    suspend fun deleteFolder(folder: Folder) = db.withTransaction {
        folders.detachLists(folder.id)
        folders.softDelete(folder.id)
    }

    suspend fun updateTag(tag: Tag) = tags.upsert(tag.copy(updatedAt = now()))

    suspend fun deleteTag(tag: Tag) {
        tags.softDelete(tag.id)
        onChanged()
    }
}
