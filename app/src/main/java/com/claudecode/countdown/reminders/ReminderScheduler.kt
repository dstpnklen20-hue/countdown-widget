package com.claudecode.countdown.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.claudecode.countdown.data.AppSettings
import com.claudecode.countdown.data.db.AppDatabase
import com.claudecode.countdown.data.db.now
import com.claudecode.countdown.domain.dueReminders
import com.claudecode.countdown.domain.nextTrigger
import com.claudecode.countdown.data.db.Habit
import com.claudecode.countdown.domain.isScheduled
import com.claudecode.countdown.domain.calendarEntries
import com.claudecode.countdown.data.db.ReminderKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
    private val settings: AppSettings,
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

    private fun allDayMinutes() = settings.current.allDayReminderMinutes

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
        val due = dueReminders(tasks, reminders, from, at, allDayMinutes()).groupBy { it.first.id }.values
        if (due.isNotEmpty()) {
            // Quiet hours and "time to work" events make reminders come without sound; alarms still ring.
            val minute = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalTime().let { it.hour * 60 + it.minute }
            val silent = settings.current.isQuiet(minute) || inFocusTime(at)
            for (group in due) {
                val task = group.first().first
                if (group.any { it.second.kind == ReminderKind.ALARM }) ReminderNotifier.showAlarm(context, task)
                else ReminderNotifier.show(context, task, silent)
            }
        }
        val habitDao = db.habitDao()
        for ((habit, trigger) in habitTriggers(from, at)) {
            val day = Instant.ofEpochMilli(trigger).atZone(ZoneId.systemDefault()).toLocalDate()
            val done = habitDao.checkIn(habit.id, day.toEpochDay())?.takeIf { !it.deleted }?.count ?: 0
            if (done < habit.goal) ReminderNotifier.showHabit(context, habit)
        }
        prefs.edit().putLong(KEY_CHECKED_UNTIL, at).apply()
        arm(earliest(nextTrigger(tasks, reminders, at, allDayMinutes()), nextHabitTrigger(at)))
    }

    /** Re-arms after data changes without delivering anything. */
    suspend fun reschedule() = mutex.withLock {
        val dao = db.reminderDao()
        val tasks = dao.tasksWithReminders().associateBy { it.id }
        val after = maxOf(checkedUntil(), now())
        arm(earliest(nextTrigger(tasks, dao.activeReminders(), after, allDayMinutes()), nextHabitTrigger(after)))
    }

    private fun earliest(a: Long?, b: Long?): Long? = listOfNotNull(a, b).minOrNull()

    /** Whether an event of the "time to work" kind is going on at [at]. */
    private suspend fun inFocusTime(at: Long): Boolean {
        val focus = db.taskDao().focusEvents()
        if (focus.isEmpty()) return false
        val zone = ZoneId.systemDefault()
        val now = Instant.ofEpochMilli(at).atZone(zone)
        val minute = now.hour * 60 + now.minute
        return calendarEntries(focus, now.toLocalDate(), now.toLocalDate())[now.toLocalDate()].orEmpty()
            .any { e -> e.timed && minute >= e.start!! && minute < e.end!! || !e.timed }
    }

    /** Daily habit reminders on scheduled days, from yesterday to a week ahead. */
    private suspend fun habitCandidates(): List<Pair<Habit, Long>> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        return db.habitDao().active().filter { it.reminderMinute != null }.flatMap { habit ->
            (-1L..7L).map { today.plusDays(it) }.filter { habit.isScheduled(it) }.map { day ->
                habit to day.atStartOfDay(zone).toInstant().toEpochMilli() + habit.reminderMinute!! * 60_000L
            }
        }
    }

    private suspend fun habitTriggers(from: Long, to: Long) = habitCandidates().filter { it.second in (from + 1)..to }

    private suspend fun nextHabitTrigger(after: Long): Long? = habitCandidates().map { it.second }.filter { it > after }.minOrNull()

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
