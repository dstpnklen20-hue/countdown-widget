package com.claudecode.countdown.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claudecode.countdown.data.TaskRepository
import com.claudecode.countdown.data.db.Folder
import com.claudecode.countdown.data.db.Progress
import com.claudecode.countdown.data.db.Tag
import com.claudecode.countdown.data.db.Section
import com.claudecode.countdown.data.db.ListViewMode
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.data.db.TaskList
import com.claudecode.countdown.data.db.TaskTagName
import com.claudecode.countdown.data.db.now
import com.claudecode.countdown.domain.Due
import com.claudecode.countdown.domain.TaskFilter
import com.claudecode.countdown.domain.TaskGroup
import com.claudecode.countdown.domain.TaskSort
import com.claudecode.countdown.domain.groupTasks
import com.claudecode.countdown.domain.matches
import com.claudecode.countdown.domain.today
import com.claudecode.countdown.ui.UndoBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class Snapshot(
    val tasks: List<Task> = emptyList(),
    val tagsByTask: Map<String, List<TaskTagName>> = emptyMap(),
    val checklist: Map<String, Progress> = emptyMap(),
    val subtasks: Map<String, Progress> = emptyMap(),
    val lists: List<TaskList> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val sections: List<Section> = emptyList(),
    val now: Long = now(),
    val today: LocalDate = today(),
    val loaded: Boolean = false,
) {
    val listsById: Map<String, TaskList> by lazy { lists.associateBy { it.id } }
    private val listOrder: Map<String, Int> by lazy { lists.withIndex().associate { (i, l) -> l.id to i } }

    /** The task's own colour, else its list's; null when neither is set. */
    fun colorOf(task: Task): Int? = task.color ?: listsById[task.listId]?.color

    private val tagIdsByTask: Map<String, Set<String>> by lazy { tagsByTask.mapValues { (_, tags) -> tags.mapTo(HashSet()) { it.tagId } } }

    // The snapshot never changes, so a list filtered once (the drawer counts every list on each
    // recomposition) is kept for as long as the snapshot lives.
    private val filterCache = java.util.concurrent.ConcurrentHashMap<TaskFilter, List<Task>>()

    fun filtered(filter: TaskFilter): List<Task> = filterCache.getOrPut(filter) {
        tasks.filter { matches(filter, it, tagIdsByTask[it.id].orEmpty(), today) }
    }

    fun groups(filter: TaskFilter, sort: TaskSort = TaskSort.DATE): List<TaskGroup> =
        groupTasks(filter, filtered(filter), now, today, sort = sort, listOrder = listOrder)

    fun openCount(filter: TaskFilter): Int = filtered(filter).count { !it.isDone }

    fun title(filter: TaskFilter): String = when (filter) {
        TaskFilter.Inbox -> "Входящие"
        TaskFilter.Today -> "Сегодня"
        TaskFilter.Tomorrow -> "Завтра"
        TaskFilter.Next7Days -> "7 дней"
        TaskFilter.All -> "Все задачи"
        TaskFilter.Countdowns -> "Отсчёты"
        TaskFilter.Completed -> "Выполненные"
        is TaskFilter.ListFilter -> listsById[filter.listId]?.name ?: "Список"
        is TaskFilter.TagFilter -> "#" + (tags.firstOrNull { it.id == filter.tagId }?.name ?: "")
    }
}

/** Ticks once a minute so "today" and overdue markers roll over while the app is open. */
private val minuteClock = flow {
    while (true) {
        val t = now()
        emit(t)
        delay(60_000 - t % 60_000)
    }
}


class TasksViewModel(private val repo: TaskRepository, private val undo: UndoBus) : ViewModel() {

    private val progress = combine(repo.observeChecklistProgress(), repo.observeSubtaskProgress()) { c, s ->
        c.associateBy { it.taskId } to s.associateBy { it.taskId }
    }
    private class Structure(val lists: List<TaskList>, val folders: List<Folder>, val tags: List<Tag>, val sections: List<Section>)

    private val structure = combine(repo.observeLists(), repo.observeFolders(), repo.observeTags(), repo.observeSections()) { l, f, t, s ->
        Structure(l, f, t, s)
    }

    val snapshot: StateFlow<Snapshot> = combine(
        repo.observeTopLevel(), repo.observeTaskTags(), progress, structure, minuteClock,
    ) { tasks, taskTags, (checklist, subtasks), st, clock ->
        val lists = st.lists
        val listIds = lists.mapTo(HashSet()) { it.id }
        Snapshot(
            tasks = tasks.filter { it.listId in listIds },
            tagsByTask = taskTags.groupBy { it.taskId },
            checklist = checklist,
            subtasks = subtasks,
            lists = lists,
            folders = st.folders,
            tags = st.tags,
            sections = st.sections,
            now = clock,
            today = today(),
            loaded = true,
        )
    }
        // Building the snapshot (grouping, maps) is kept off the main thread, which only draws.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Snapshot())

    fun toggleDone(task: Task) = viewModelScope.launch { repo.setDone(task, !task.isDone) }

    fun delete(task: Task) = viewModelScope.launch {
        val at = repo.delete(task.id)
        undo.offer("Задача удалена") { repo.restore(task.id, at) }
    }

    /** Deleted tasks; null until loaded. */
    val trash: StateFlow<List<Task>?> = repo.observeTrash().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun restoreFromTrash(task: Task) = viewModelScope.launch { repo.restoreFromTrash(task) }
    fun purge(task: Task) = viewModelScope.launch { repo.purge(task) }
    fun emptyTrash() = viewModelScope.launch { repo.emptyTrash() }

    fun quickAdd(text: String, filter: TaskFilter, pickedDue: Due?, pickedPriority: Int) =
        viewModelScope.launch { repo.quickAdd(text, filter, pickedDue, pickedPriority) }

    fun createList(name: String, color: Int?, folderId: String?, onCreated: (TaskList) -> Unit) = viewModelScope.launch {
        onCreated(repo.createList(name, color, folderId))
    }

    fun updateList(list: TaskList) = viewModelScope.launch { repo.updateList(list) }
    fun deleteList(list: TaskList) = viewModelScope.launch {
        val at = repo.deleteList(list) ?: return@launch
        undo.offer("Список «${list.name}» удалён") { repo.restoreList(list, at) }
    }
    fun createFolder(name: String) = viewModelScope.launch { repo.createFolder(name) }
    fun updateFolder(folder: Folder) = viewModelScope.launch { repo.updateFolder(folder) }
    fun deleteFolder(folder: Folder) = viewModelScope.launch { repo.deleteFolder(folder) }
    fun updateTag(tag: Tag) = viewModelScope.launch { repo.updateTag(tag) }
    fun deleteTag(tag: Tag) = viewModelScope.launch { repo.deleteTag(tag) }

    fun setViewMode(list: TaskList, mode: ListViewMode) = viewModelScope.launch { repo.updateList(list.copy(viewMode = mode)) }
    fun createSection(listId: String, name: String) = viewModelScope.launch { repo.createSection(listId, name) }
    fun renameSection(section: Section, name: String) = viewModelScope.launch { repo.updateSection(section.copy(name = name)) }
    fun deleteSection(section: Section) = viewModelScope.launch { repo.deleteSection(section) }
    fun moveToSection(task: Task, sectionId: String?) = viewModelScope.launch { repo.update(task.copy(sectionId = sectionId)) }
    fun setPriority(task: Task, priority: Int) = viewModelScope.launch { repo.update(task.copy(priority = priority)) }

    /** A new event or task from the calendar; [remind] adds an "at the time" reminder. */
    fun createEntry(task: Task, remind: Boolean) = viewModelScope.launch {
        val created = repo.create(task)
        if (remind) repo.addReminder(created.id, 0)
    }

    fun addTask(title: String, listId: String, sectionId: String? = null, due: Due? = null) = viewModelScope.launch {
        repo.create(
            Task(
                title = title, listId = listId, sectionId = sectionId,
                dueAt = due?.at, isAllDay = due?.isAllDay ?: false, timeZone = due?.timeZone,
            )
        )
    }
}
