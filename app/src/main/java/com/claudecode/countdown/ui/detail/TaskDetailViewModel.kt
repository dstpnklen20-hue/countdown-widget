package com.claudecode.countdown.ui.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claudecode.countdown.data.TaskRepository
import com.claudecode.countdown.data.db.ChecklistItem
import com.claudecode.countdown.data.db.DisplayMode
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.data.db.RepeatFrom
import com.claudecode.countdown.data.db.Reminder
import com.claudecode.countdown.domain.Due
import com.claudecode.countdown.domain.allDayDue
import com.claudecode.countdown.domain.today
import com.claudecode.countdown.ui.UndoBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TaskDetailViewModel(
    private val taskId: String,
    private val repo: TaskRepository,
    private val appScope: CoroutineScope,
    private val undo: UndoBus,
) : ViewModel() {

    val task: StateFlow<Task?> = repo.observeTask(taskId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val checklist = repo.observeChecklist(taskId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val subtasks = repo.observeSubtasks(taskId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val tagNames: StateFlow<List<String>> = repo.observeTaskTags()
        .map { links -> links.filter { it.taskId == taskId }.map { it.name } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val allTags = repo.observeTags().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val lists = repo.observeLists().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Text is edited locally and saved with a short debounce so typing never fights DB emissions.
    var title by mutableStateOf("")
        private set
    var content by mutableStateOf("")
        private set
    var loaded by mutableStateOf(false)
        private set
    private var textDirty = false
    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            val initial = repo.get(taskId)
            if (initial != null) {
                title = initial.title
                content = initial.content
            }
            loaded = true
        }
    }

    fun onTitleChange(value: String) {
        title = value
        scheduleTextSave()
    }

    fun onContentChange(value: String) {
        content = value
        scheduleTextSave()
    }

    private fun scheduleTextSave() {
        textDirty = true
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(500)
            mutate { it }
        }
    }

    /** Applies [change] to the freshest stored task together with the current text drafts. */
    private fun mutate(change: (Task) -> Task) = viewModelScope.launch { mutateNow(change) }

    private suspend fun mutateNow(change: (Task) -> Task) {
        val current = repo.get(taskId) ?: return
        val withText = if (textDirty) current.copy(title = title, content = content) else current
        textDirty = false
        val updated = change(withText)
        if (updated != current) repo.update(updated)
    }

    fun toggleDone() = viewModelScope.launch {
        mutateNow { it }
        repo.get(taskId)?.let { repo.setDone(it, !it.isDone) }
    }

    fun setDue(due: Due?) = viewModelScope.launch {
        mutateNow { it.copy(dueAt = due?.at, isAllDay = due?.isAllDay ?: false, timeZone = due?.timeZone) }
        // Like TickTick: giving a task a time switches on an "at time" reminder by default.
        if (due != null && !due.isAllDay && reminders.value.isEmpty()) repo.addReminder(taskId, 0)
    }

    val reminders = repo.observeReminders(taskId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun addReminder(offsetMinutes: Int) = viewModelScope.launch { repo.addReminder(taskId, offsetMinutes) }
    fun deleteReminder(reminder: Reminder) = viewModelScope.launch { repo.deleteReminder(reminder) }

    fun setRepeat(rule: String?, from: RepeatFrom) = mutate {
        val withDate = if (rule != null && it.dueAt == null) {
            val due = allDayDue(today())
            it.copy(dueAt = due.at, isAllDay = true, timeZone = due.timeZone)
        } else it
        withDate.copy(repeatRule = rule, repeatFrom = from)
    }

    fun setPriority(priority: Int) = mutate { it.copy(priority = priority) }

    fun setList(listId: String) = mutate { it.copy(listId = listId, sectionId = null) }

    fun setCountdown(enabled: Boolean) = mutate {
        it.copy(displayMode = if (enabled) DisplayMode.COUNTDOWN else DisplayMode.NORMAL)
    }

    fun addChecklistItem(text: String) = viewModelScope.launch { repo.addChecklistItem(taskId, text) }
    fun toggleChecklistItem(item: ChecklistItem) = viewModelScope.launch { repo.updateChecklistItem(item.copy(checked = !item.checked)) }
    fun renameChecklistItem(item: ChecklistItem, text: String) = viewModelScope.launch { repo.updateChecklistItem(item.copy(title = text)) }
    fun deleteChecklistItem(item: ChecklistItem) = viewModelScope.launch { repo.deleteChecklistItem(item) }

    fun addSubtask(text: String) = viewModelScope.launch {
        val parent = repo.get(taskId) ?: return@launch
        repo.create(Task(title = text, listId = parent.listId, parentId = taskId))
    }

    fun toggleSubtask(sub: Task) = viewModelScope.launch { repo.setDone(sub, !sub.isDone) }

    fun addTag(name: String) = viewModelScope.launch {
        val clean = name.trim().removePrefix("#").trim()
        if (clean.isNotEmpty()) repo.setTags(taskId, tagNames.value + clean)
    }

    fun removeTag(name: String) = viewModelScope.launch { repo.setTags(taskId, tagNames.value - name) }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        saveJob?.cancel()
        // Keep what was typed so that undo brings the task back exactly as it was.
        mutateNow { it }
        val at = repo.delete(taskId)
        undo.offer("Задача удалена") { repo.restore(taskId, at) }
        onDone()
    }

    override fun onCleared() {
        if (textDirty) {
            saveJob?.cancel()
            appScope.launch { mutateNow { it } }
        }
    }
}
