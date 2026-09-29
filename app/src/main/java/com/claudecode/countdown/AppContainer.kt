package com.claudecode.countdown

import android.content.BroadcastReceiver
import android.content.Context
import com.claudecode.countdown.data.FocusRepository
import com.claudecode.countdown.data.HabitRepository
import com.claudecode.countdown.data.TaskRepository
import com.claudecode.countdown.pomodoro.PomodoroTimer
import com.claudecode.countdown.data.db.AppDatabase
import com.claudecode.countdown.reminders.ReminderNotifier
import com.claudecode.countdown.reminders.ReminderScheduler
import com.claudecode.countdown.ui.UndoBus
import com.claudecode.countdown.widget.CountdownWidgetProvider
import com.claudecode.countdown.widget.QuickAddWidget
import com.claudecode.countdown.widget.TodayWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(private val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database: AppDatabase by lazy { AppDatabase.build(context) }
    val reminders: ReminderScheduler by lazy { ReminderScheduler(context, database) }
    val tasks: TaskRepository by lazy {
        TaskRepository(database, onClosed = { ReminderNotifier.cancel(context, it) }) { onDataChanged() }
    }
    val habits: HabitRepository by lazy {
        HabitRepository(database, onClosed = { ReminderNotifier.cancelHabit(context, it) }) { reminders.reschedule() }
    }
    val focus: FocusRepository by lazy { FocusRepository(database) }
    val pomodoro: PomodoroTimer by lazy { PomodoroTimer(context, focus) }
    val undo = UndoBus(appScope)

    /** Everything that mirrors task data outside the app: widgets and the reminder alarm. */
    fun onDataChanged() {
        CountdownWidgetProvider.updateAllWidgets(context)
        appScope.launch { TodayWidget.refresh(context) }
        appScope.launch { reminders.reschedule() }
    }

    /** After a theme change every widget has to be redrawn with the new colors. */
    fun refreshAllWidgets() {
        appScope.launch { redrawWidgets() }
    }

    suspend fun redrawWidgets() {
        CountdownWidgetProvider.updateAllWidgets(context)
        TodayWidget.refresh(context)
        QuickAddWidget.refresh(context)
    }
}

val Context.container: AppContainer
    get() = (applicationContext as CountdownApp).container

/** Runs [block] off the main thread while keeping the receiver alive until it finishes. */
fun BroadcastReceiver.launchAsync(context: Context, block: suspend () -> Unit) {
    val pending = goAsync()
    context.container.appScope.launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}
