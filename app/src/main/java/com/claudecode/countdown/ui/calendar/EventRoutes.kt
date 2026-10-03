package com.claudecode.countdown.ui.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claudecode.countdown.container
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.domain.dueDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The event editor with its data and actions: saves through the repository (in the app scope,
 * so leaving the screen does not cancel the write), offers "undo" after deleting.
 * [onTaskInstead] gets the id of an event the user turned into a task.
 */
@Composable
fun EventEditRoute(draft: EventDraft, pastEvents: List<Task>, onClose: () -> Unit, onTaskInstead: (String) -> Unit) {
    val container = LocalContext.current.container
    val repo = container.tasks
    val calendars by repo.observeCalendars().collectAsStateWithLifecycle(emptyList())
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val master = draft.master
    EventEditScreen(
        draft = draft,
        calendars = calendars,
        pastEvents = pastEvents,
        allDayMinutes = settings.allDayReminderMinutes,
        onSave = { task, reminders, scope ->
            container.appScope.launch { repo.saveEvent(task, reminders, master, draft.occurrence, scope) }
            onClose()
        },
        onDelete = master?.let { m ->
            { scope ->
                container.appScope.launch {
                    val undo = repo.deleteEvent(m, draft.occurrence ?: m.dueDay() ?: return@launch, scope)
                    container.undo.offer("Событие удалено") { undo() }
                }
                onClose()
            }
        },
        onMakeTask = master?.let { m ->
            {
                container.appScope.launch {
                    repo.update(m.copy(isEvent = false))
                    withContext(Dispatchers.Main) { onTaskInstead(m.id) }
                }
            }
        },
        onClose = onClose,
    )
}
