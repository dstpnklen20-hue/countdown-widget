package com.claudecode.countdown

import android.content.BroadcastReceiver
import android.content.Context
import com.claudecode.countdown.data.AppSettings
import com.claudecode.countdown.data.FocusRepository
import com.claudecode.countdown.data.HabitRepository
import com.claudecode.countdown.data.TaskRepository
import com.claudecode.countdown.data.sync.RowStore
import com.claudecode.countdown.data.sync.SyncManager
import com.claudecode.countdown.data.sync.SyncTable
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AppContainer(private val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database: AppDatabase by lazy { AppDatabase.build(context) }
    val settings: AppSettings by lazy { AppSettings(context) }
    val reminders: ReminderScheduler by lazy { ReminderScheduler(context, database, settings) }
    val tasks: TaskRepository by lazy {
        TaskRepository(
            database,
            onClosed = { ReminderNotifier.cancel(context, it) },
            onPurged = { sync.forget(SyncTable.TASKS, it) },
        ) { onDataChanged() }
    }
    val rows: RowStore by lazy { RowStore(database) }
    /** Account and sync; it watches the database itself, so every write gets pushed. */
    val sync: SyncManager by lazy { SyncManager(context, database, rows, appScope) { onDataChanged() } }
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

    /**
     * Bumped on every theme change. Glance widgets read their colours inside the live session,
     * keyed on this, because update() alone only recomposes that session.
     */
    val widgetTheme = MutableStateFlow(0)

    /** After a theme change every widget has to be redrawn with the new colors. */
    fun refreshAllWidgets() {
        widgetTheme.update { it + 1 }
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
