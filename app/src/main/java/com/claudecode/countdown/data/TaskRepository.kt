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
import com.claudecode.countdown.data.db.Section
import com.claudecode.countdown.data.db.newId
import com.claudecode.countdown.data.db.now
import com.claudecode.countdown.data.db.Priority
import com.claudecode.countdown.domain.Due
import com.claudecode.countdown.domain.TaskFilter
import com.claudecode.countdown.domain.allDayDue
import com.claudecode.countdown.domain.groupTasks
import com.claudecode.countdown.domain.matches
import com.claudecode.countdown.domain.nextOccurrence
import com.claudecode.countdown.domain.parseQuickAdd
import com.claudecode.countdown.domain.timedDue
import com.claudecode.countdown.domain.today
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Single write path for tasks and their satellites. [onChanged] refreshes widgets and alarms
 * after every write; [onClosed] takes down the reminder notification of a task that was
 * completed or deleted. [onPurged] gets the ids of tasks removed for good, so sync removes them
 * from the account too.
 */
class TaskRepository(
    private val db: AppDatabase,
    private val onClosed: (taskId: String) -> Unit = {},
    private val onPurged: (taskIds: List<String>) -> Unit = {},
    private val onChanged: suspend () -> Unit,
) {
    private val tasks = db.taskDao()
    private val checklist = db.checklistDao()
    private val tags = db.tagDao()
    private val lists = db.taskListDao()
    private val folders = db.folderDao()
    private val reminders = db.reminderDao()
    private val sections = db.sectionDao()

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

    /**
     * Quick add with natural-language parsing. Values picked with buttons win over the text;
     * otherwise tasks inherit list, tag or date from the view they are added in.
     */
    suspend fun quickAdd(text: String, filter: TaskFilter, pickedDue: Due?, pickedPriority: Int): Task {
        val parsed = parseQuickAdd(text)
        val listId = parsed.listName?.let { name -> lists.all().firstOrNull { it.name.equals(name, ignoreCase = true) }?.id }
            ?: (filter as? TaskFilter.ListFilter)?.listId
            ?: TaskList.INBOX_ID
        val parsedDue = parsed.date?.let { d -> parsed.time?.let { timedDue(d, it) } ?: allDayDue(d) }
        val due = pickedDue ?: parsedDue ?: when (filter) {
            TaskFilter.Today, TaskFilter.Next7Days -> allDayDue(today())
            TaskFilter.Tomorrow -> allDayDue(today().plusDays(1))
            else -> null
        }
        val filterTag = (filter as? TaskFilter.TagFilter)?.let { tags.get(it.tagId)?.name }
        val created = create(
            Task(
                title = parsed.title.ifBlank { text.trim() },
                listId = listId,
                priority = if (pickedPriority != Priority.NONE) pickedPriority else parsed.priority ?: Priority.NONE,
                dueAt = due?.at,
                isAllDay = due?.isAllDay ?: false,
                timeZone = due?.timeZone,
                repeatRule = parsed.repeat?.toRRule(),
                displayMode = if (filter == TaskFilter.Countdowns) DisplayMode.COUNTDOWN else DisplayMode.NORMAL,
            ),
            (parsed.tags + listOfNotNull(filterTag)).distinct(),
        )
        if (due != null && !due.isAllDay) addReminder(created.id, 0)
        return created
    }

    /** Open top-level tasks for today plus overdue ones, in list order. */
    suspend fun todayTasks(): List<Task> = todayOf(tasks.topLevel(), lists.all())

    fun observeTodayTasks(): Flow<List<Task>> = combine(tasks.observeTopLevel(), lists.observeAll(), ::todayOf)

    private fun todayOf(all: List<Task>, allLists: List<TaskList>): List<Task> {
        val listIds = allLists.mapTo(HashSet()) { it.id }
        val today = today()
        val open = all.filter { it.listId in listIds && !it.isDone && matches(TaskFilter.Today, it, emptySet(), today) }
        return groupTasks(TaskFilter.Today, open, now(), today).flatMap { it.tasks }
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
        onClosed(task.id)
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

    /** Moves the task and its subtasks to the trash; the returned stamp lets [restore] undo exactly this. */
    suspend fun delete(id: String): Long {
        val at = now()
        tasks.softDelete(id, at)
        onClosed(id)
        onChanged()
        return at
    }

    suspend fun restore(id: String, deletedAt: Long) {
        tasks.restore(id, deletedAt)
        onChanged()
    }

    fun observeTrash() = tasks.observeTrash()

    /** Brings a task back from the trash; if its list is gone too, the task lands in Inbox. */
    suspend fun restoreFromTrash(task: Task) {
        db.withTransaction {
            tasks.restore(task.id, task.updatedAt)
            val list = lists.get(task.listId)
            if (list == null || list.deleted) {
                val moved = tasks.idsWithSubtasks(task.id).mapNotNull { tasks.get(it) }.filter { !it.deleted }
                for (t in moved) tasks.upsert(t.copy(listId = TaskList.INBOX_ID, sectionId = null, updatedAt = now()))
            }
        }
        onChanged()
    }

    suspend fun purge(task: Task) {
        val ids = tasks.idsWithSubtasks(task.id)
        tasks.purge(ids)
        onPurged(ids)
        onChanged()
    }

    suspend fun emptyTrash() {
        val ids = tasks.deletedIds()
        tasks.purge(ids)
        onPurged(ids)
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

    /** Deletes the list with its tasks; returns the stamp for [restoreList], or null for Inbox. */
    suspend fun deleteList(list: TaskList): Long? {
        if (list.isInbox) return null
        val at = now()
        db.withTransaction {
            tasks.softDeleteInList(list.id, at)
            lists.upsert(list.copy(deleted = true, updatedAt = at))
        }
        onChanged()
        return at
    }

    suspend fun restoreList(list: TaskList, deletedAt: Long) {
        db.withTransaction {
            lists.upsert(list.copy(deleted = false, updatedAt = now()))
            tasks.restoreInList(list.id, deletedAt)
        }
        onChanged()
    }

    fun observeSections() = sections.observeAll()

    suspend fun createSection(listId: String, name: String): Section =
        Section(listId = listId, name = name, sortOrder = sections.maxSortOrder(listId) + 1).also { sections.upsert(it) }

    suspend fun updateSection(section: Section) = sections.upsert(section.copy(updatedAt = now()))

    suspend fun deleteSection(section: Section) {
        db.withTransaction {
            sections.detachTasks(section.id)
            sections.softDelete(section.id)
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
