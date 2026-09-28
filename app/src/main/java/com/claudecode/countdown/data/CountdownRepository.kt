package com.claudecode.countdown.data

import android.content.Context
import com.claudecode.countdown.container
import com.claudecode.countdown.data.db.DisplayMode
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.data.db.WidgetBinding
import com.claudecode.countdown.data.db.WidgetKind
import com.claudecode.countdown.data.db.now
import com.claudecode.countdown.model.Countdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.TimeZone

/** Blocking bridge for the legacy View screens; the Compose UI talks to the DAOs directly. */
object CountdownRepository {

    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    private fun Task.toCountdown() = Countdown(id, title, dueAt ?: 0L)

    fun getAll(context: Context): List<Countdown> = io {
        context.container.database.taskDao().widgetCandidates().map { it.toCountdown() }
    }

    fun get(context: Context, id: String): Countdown? = io {
        context.container.database.taskDao().get(id)?.takeIf { !it.deleted && it.dueAt != null }?.toCountdown()
    }

    fun save(context: Context, id: String?, title: String, targetMillis: Long): String = io {
        val dao = context.container.database.taskDao()
        val existing = id?.let { dao.get(it) }
        val task = existing?.copy(title = title, dueAt = targetMillis, updatedAt = now())
            ?: Task(
                title = title,
                dueAt = targetMillis,
                timeZone = TimeZone.getDefault().id,
                displayMode = DisplayMode.COUNTDOWN,
            )
        dao.upsert(task)
        task.id
    }

    fun delete(context: Context, id: String) = io {
        context.container.database.taskDao().softDelete(id)
    }

    fun setWidgetCountdown(context: Context, appWidgetId: Int, countdownId: String) = io {
        context.container.database.widgetBindingDao()
            .upsert(WidgetBinding(appWidgetId, WidgetKind.COUNTDOWN, taskId = countdownId))
    }

    suspend fun getWidgetCountdown(context: Context, appWidgetId: Int): Countdown? {
        val db = context.container.database
        val taskId = db.widgetBindingDao().get(appWidgetId)?.taskId ?: return null
        return db.taskDao().get(taskId)?.takeIf { !it.deleted && it.dueAt != null }?.toCountdown()
    }

    suspend fun removeWidgetBinding(context: Context, appWidgetId: Int) {
        context.container.database.widgetBindingDao().delete(appWidgetId)
    }
}
