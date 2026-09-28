package com.claudecode.countdown.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.claudecode.countdown.data.db.AppDatabase
import com.claudecode.countdown.data.db.now
import com.claudecode.countdown.domain.dueReminders
import com.claudecode.countdown.domain.nextTrigger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps exactly one alarm armed for the earliest upcoming reminder. A "checked until" watermark
 * remembers up to when reminders were delivered, so a reboot or a late alarm delivers what was
 * missed (at most [MISSED_WINDOW_MS] back) and nothing twice.
 */
class ReminderScheduler(
    private val context: Context,
    private val db: AppDatabase,
) {
    companion object {
        const val ACTION_FIRE = "com.claudecode.countdown.ACTION_FIRE_REMINDERS"
        private const val PREFS = "reminders"
        private const val KEY_CHECKED_UNTIL = "checked_until"
        private const val MISSED_WINDOW_MS = 24 * 60 * 60 * 1000L
    }

    private val mutex = Mutex()
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val alarmManager by lazy { context.getSystemService(Context.ALARM_SERVICE) as AlarmManager }

    private fun checkedUntil(): Long {
        if (!prefs.contains(KEY_CHECKED_UNTIL)) prefs.edit().putLong(KEY_CHECKED_UNTIL, now()).commit()
        return prefs.getLong(KEY_CHECKED_UNTIL, now())
    }

    /** Delivers every reminder that became due since the last check, then re-arms the alarm. */
    suspend fun deliverDueAndReschedule() = mutex.withLock {
        val dao = db.reminderDao()
        val tasks = dao.tasksWithReminders().associateBy { it.id }
        val reminders = dao.activeReminders()
        val at = now()
        val from = maxOf(checkedUntil(), at - MISSED_WINDOW_MS)
        for ((task, _) in dueReminders(tasks, reminders, from, at).distinctBy { it.first.id }) {
            ReminderNotifier.show(context, task)
        }
        prefs.edit().putLong(KEY_CHECKED_UNTIL, at).apply()
        arm(nextTrigger(tasks, reminders, at))
    }

    /** Re-arms after data changes without delivering anything. */
    suspend fun reschedule() = mutex.withLock {
        val dao = db.reminderDao()
        val tasks = dao.tasksWithReminders().associateBy { it.id }
        arm(nextTrigger(tasks, dao.activeReminders(), maxOf(checkedUntil(), now())))
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun arm(at: Long?) {
        val pi = pendingIntent()
        if (at == null) {
            alarmManager.cancel(pi)
            return
        }
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (exact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}
