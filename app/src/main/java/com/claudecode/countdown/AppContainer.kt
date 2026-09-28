package com.claudecode.countdown

import android.content.BroadcastReceiver
import android.content.Context
import com.claudecode.countdown.data.TaskRepository
import com.claudecode.countdown.data.db.AppDatabase
import com.claudecode.countdown.widget.CountdownWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(private val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database: AppDatabase by lazy { AppDatabase.build(context) }
    val tasks: TaskRepository by lazy { TaskRepository(database) { onDataChanged() } }

    private fun onDataChanged() {
        CountdownWidgetProvider.updateAllWidgets(context)
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
