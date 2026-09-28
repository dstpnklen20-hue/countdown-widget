package com.claudecode.countdown.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claudecode.countdown.data.TaskRepository
import com.claudecode.countdown.data.db.Folder
import com.claudecode.countdown.data.db.Progress
import com.claudecode.countdown.data.db.Tag
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.data.db.TaskList
import com.claudecode.countdown.data.db.TaskTagName
import com.claudecode.countdown.data.db.now
import com.claudecode.countdown.data.db.Priority
import com.claudecode.countdown.domain.Due
import com.claudecode.countdown.domain.TaskFilter
import com.claudecode.countdown.domain.timedDue
import com.claudecode.tiktak.core.QuickAddParser
import com.claudecode.tiktak.core.QuickAddResult
import java.time.LocalTime
import com.claudecode.countdown.domain.TaskGroup
import com.claudecode.countdown.domain.allDayDue
import com.claudecode.countdown.domain.groupTasks
import com.claudecode.countdown.domain.matches
import com.claudecode.countdown.domain.today
import kotlinx.coroutines.delay
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
    val now: Long = now(),
    val today: LocalDate = today(),
    val loaded: Boolean = false,
) {
    val listsById: Map<String, TaskList> by lazy { lists.associateBy { it.id } }

    fun filtered(filter: TaskFilter): List<Task> = tasks.filter {
        matches(filter, it, tagsByTask[it.id].orEmpty().mapTo(HashSet()) { t -> t.tagId }, today)
    }

    fun groups(filter: TaskFilter): List<TaskGroup> = groupTasks(filter, filtered(filter), now, today)

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

fun parseQuickAdd(text: String): QuickAddResult = QuickAddParser(today(), LocalTime.now()).parse(text)

class TasksViewModel(private val repo: TaskRepository) : ViewModel() {

    private val progress = combine(repo.observeChecklistProgress(), repo.observeSubtaskProgress()) { c, s ->
        c.associateBy { it.taskId } to s.associateBy { it.taskId }
    }
    private val structure = combine(repo.observeLists(), repo.observeFolders(), repo.observeTags()) { l, f, t ->
        Triple(l, f, t)
    }

    val snapshot: StateFlow<Snapshot> = combine(
        repo.observeTopLevel(), repo.observeTaskTags(), progress, structure, minuteClock,
    ) { tasks, taskTags, (checklist, subtasks), (lists, folders, tags), clock ->
        val listIds = lists.mapTo(HashSet()) { it.id }
        Snapshot(
            tasks = tasks.filter { it.listId in listIds },
            tagsByTask = taskTags.groupBy { it.taskId },
            checklist = checklist,
            subtasks = subtasks,
            lists = lists,
            folders = folders,
            tags = tags,
            now = clock,
            today = today(),
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Snapshot())

    fun toggleDone(task: Task) = viewModelScope.launch { repo.setDone(task, !task.isDone) }

    fun delete(task: Task) = viewModelScope.launch { repo.delete(task.id) }

    /**
     * Quick add with natural-language parsing. Values picked with the sheet's buttons win over the
     * text; otherwise tasks inherit list, tag or date from the view they are added in.
     */
    fun quickAdd(text: String, filter: TaskFilter, pickedDue: Due?, pickedPriority: Int) = viewModelScope.launch {
        val parsed = parseQuickAdd(text)
        val lists = snapshot.value.lists
        val listId = parsed.listName?.let { name -> lists.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id }
            ?: (filter as? TaskFilter.ListFilter)?.listId
            ?: TaskList.INBOX_ID
        val parsedDue = parsed.date?.let { d -> parsed.time?.let { timedDue(d, it) } ?: allDayDue(d) }
        val due = pickedDue ?: parsedDue ?: when (filter) {
            TaskFilter.Today, TaskFilter.Next7Days -> allDayDue(today())
            TaskFilter.Tomorrow -> allDayDue(today().plusDays(1))
            else -> null
        }
        val tagNames = parsed.tags + (filter as? TaskFilter.TagFilter)?.let { f ->
            snapshot.value.tags.firstOrNull { it.id == f.tagId }?.name
        }.let { listOfNotNull(it) }
        val created = repo.create(
            Task(
                title = parsed.title.ifBlank { text.trim() },
                listId = listId,
                priority = if (pickedPriority != Priority.NONE) pickedPriority else parsed.priority ?: Priority.NONE,
                dueAt = due?.at,
                isAllDay = due?.isAllDay ?: false,
                timeZone = due?.timeZone,
                repeatRule = parsed.repeat?.toRRule(),
            ),
            tagNames.distinct(),
        )
        if (due != null && !due.isAllDay) repo.addReminder(created.id, 0)
    }

    fun createList(name: String, color: Int?, folderId: String?, onCreated: (TaskList) -> Unit) = viewModelScope.launch {
        onCreated(repo.createList(name, color, folderId))
    }

    fun updateList(list: TaskList) = viewModelScope.launch { repo.updateList(list) }
    fun deleteList(list: TaskList) = viewModelScope.launch { repo.deleteList(list) }
    fun createFolder(name: String) = viewModelScope.launch { repo.createFolder(name) }
    fun updateFolder(folder: Folder) = viewModelScope.launch { repo.updateFolder(folder) }
    fun deleteFolder(folder: Folder) = viewModelScope.launch { repo.deleteFolder(folder) }
    fun updateTag(tag: Tag) = viewModelScope.launch { repo.updateTag(tag) }
    fun deleteTag(tag: Tag) = viewModelScope.launch { repo.deleteTag(tag) }
}
