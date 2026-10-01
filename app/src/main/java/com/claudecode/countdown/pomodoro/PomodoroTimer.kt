package com.claudecode.countdown.pomodoro

import android.app.AlarmManager
import android.app.Notification
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.SystemClock
import android.widget.RemoteViews
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.claudecode.countdown.MainActivity
import com.claudecode.countdown.R
import com.claudecode.countdown.ThemeManager
import com.claudecode.countdown.container
import com.claudecode.countdown.data.FocusRepository
import com.claudecode.countdown.data.db.FocusKind
import com.claudecode.countdown.data.db.FocusSession
import com.claudecode.countdown.data.db.now
import com.claudecode.countdown.launchAsync
import com.claudecode.countdown.reminders.ReminderNotifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PomodoroPhase(val label: String, val kind: FocusKind) {
    FOCUS("Фокус", FocusKind.FOCUS),
    SHORT_BREAK("Перерыв", FocusKind.SHORT_BREAK),
    LONG_BREAK("Длинный перерыв", FocusKind.LONG_BREAK),
}

enum class PomodoroStatus { IDLE, RUNNING, PAUSED }

data class PomodoroSettings(
    val focusMin: Int = 25,
    val shortMin: Int = 5,
    val longMin: Int = 15,
    val longEvery: Int = 4,
    /** Ring like an alarm clock when a phase ends (otherwise a plain notification). */
    val alarm: Boolean = true,
)

data class PomodoroState(
    val phase: PomodoroPhase = PomodoroPhase.FOCUS,
    val status: PomodoroStatus = PomodoroStatus.IDLE,
    /** When RUNNING: the moment the phase ends. */
    val endAt: Long = 0,
    /** When PAUSED: time left. */
    val remainingMs: Long = 0,
    val startedAt: Long = 0,
    val taskId: String? = null,
    /** Focus sessions finished in the current cycle (for the long break). */
    val cycleCount: Int = 0,
)

/**
 * Timer state lives in preferences, not in a service: the UI derives the remaining time from
 * [PomodoroState.endAt], and an exact alarm finishes the phase if the app is not open.
 */
class PomodoroTimer(private val context: Context, private val focus: FocusRepository) {

    companion object {
        const val ACTION_END = "com.claudecode.countdown.ACTION_POMODORO_END"
        const val ACTION_PAUSE = "com.claudecode.countdown.ACTION_POMODORO_PAUSE"
        const val ACTION_RESUME = "com.claudecode.countdown.ACTION_POMODORO_RESUME"
        const val ACTION_RESET = "com.claudecode.countdown.ACTION_POMODORO_RESET"
        const val ACTION_ALARM_OFF = "com.claudecode.countdown.ACTION_POMODORO_ALARM_OFF"
        const val ACTION_START_NEXT = "com.claudecode.countdown.ACTION_POMODORO_START_NEXT"
        private const val PREFS = "pomodoro"
        private const val CHANNEL = "focus"
        // Separate channel: a channel's sound can't change after creation, and this one rings like an alarm.
        private const val ALARM_CHANNEL = "focus_alarm"
        private const val ALARM_RING_MS = 60_000L
        private const val ONGOING_ID = 7001
        private const val DONE_ID = 7002
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()
    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<PomodoroState> = _state.asStateFlow()

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<PomodoroSettings> = _settings.asStateFlow()

    fun durationMs(phase: PomodoroPhase, s: PomodoroSettings = _settings.value): Long = 60_000L * when (phase) {
        PomodoroPhase.FOCUS -> s.focusMin
        PomodoroPhase.SHORT_BREAK -> s.shortMin
        PomodoroPhase.LONG_BREAK -> s.longMin
    }

    fun remainingMs(s: PomodoroState = _state.value, at: Long = now()): Long = when (s.status) {
        PomodoroStatus.RUNNING -> (s.endAt - at).coerceAtLeast(0)
        PomodoroStatus.PAUSED -> s.remainingMs
        PomodoroStatus.IDLE -> durationMs(s.phase)
    }

    fun updateSettings(s: PomodoroSettings) {
        prefs.edit().putInt("focusMin", s.focusMin).putInt("shortMin", s.shortMin)
            .putInt("longMin", s.longMin).putInt("longEvery", s.longEvery).putBoolean("alarm", s.alarm).apply()
        _settings.value = s
    }

    fun selectTask(taskId: String?) = mutate { it.copy(taskId = taskId) }

    fun selectPhase(phase: PomodoroPhase) = mutate { if (it.status == PomodoroStatus.IDLE) it.copy(phase = phase) else it }

    fun start() = mutate {
        if (it.status != PomodoroStatus.IDLE) return@mutate it
        // Starting the next phase silences the "time is up" alarm.
        stopAlarm()
        val at = now()
        it.copy(status = PomodoroStatus.RUNNING, startedAt = at, endAt = at + durationMs(it.phase))
    }

    fun pause() = mutate {
        if (it.status != PomodoroStatus.RUNNING) it
        else it.copy(status = PomodoroStatus.PAUSED, remainingMs = (it.endAt - now()).coerceAtLeast(0))
    }

    fun resume() = mutate {
        if (it.status != PomodoroStatus.PAUSED) it
        else it.copy(status = PomodoroStatus.RUNNING, endAt = now() + it.remainingMs)
    }

    /** Abandons the phase. Only a focus that runs to its end counts, so nothing is recorded here. */
    fun reset() = mutate { it.copy(status = PomodoroStatus.IDLE, endAt = 0, remainingMs = 0) }

    fun stopAlarm() = NotificationManagerCompat.from(context).cancel(DONE_ID)

    fun skip() = mutate { next(it) }

    /** Finishes the running phase if its time is up. Safe to call repeatedly (UI tick and alarm). */
    suspend fun completeIfDue() {
        var finished: PomodoroState? = null
        synchronized(lock) {
            val s = _state.value
            if (s.status == PomodoroStatus.RUNNING && now() >= s.endAt - 500) {
                finished = s
                apply(next(s))
            }
        }
        val s = finished ?: return
        if (s.phase == PomodoroPhase.FOCUS) record(s, durationMs(s.phase))
        notifyFinished(s.phase)
    }

    /** Alarms are lost on reboot; restore the one for a running phase. */
    suspend fun restoreAfterBoot() {
        completeIfDue()
        arm(_state.value)
        showOngoing(_state.value)
    }

    private fun next(s: PomodoroState): PomodoroState {
        val settings = _settings.value
        return if (s.phase == PomodoroPhase.FOCUS) {
            val cycles = s.cycleCount + 1
            val longBreak = cycles % settings.longEvery.coerceAtLeast(1) == 0
            s.copy(
                phase = if (longBreak) PomodoroPhase.LONG_BREAK else PomodoroPhase.SHORT_BREAK,
                status = PomodoroStatus.IDLE, endAt = 0, remainingMs = 0,
                cycleCount = if (longBreak) 0 else cycles,
            )
        } else {
            s.copy(phase = PomodoroPhase.FOCUS, status = PomodoroStatus.IDLE, endAt = 0, remainingMs = 0)
        }
    }

    private suspend fun record(s: PomodoroState, durationMs: Long) {
        focus.record(
            FocusSession(
                taskId = s.taskId,
                kind = s.phase.kind,
                startedAt = s.startedAt,
                endedAt = s.startedAt + durationMs,
                durationMs = durationMs,
            )
        )
    }

    private fun mutate(change: (PomodoroState) -> PomodoroState) {
        synchronized(lock) { apply(change(_state.value)) }
    }

    private fun apply(s: PomodoroState) {
        if (s == _state.value) return
        prefs.edit()
            .putString("phase", s.phase.name).putString("status", s.status.name)
            .putLong("endAt", s.endAt).putLong("remainingMs", s.remainingMs).putLong("startedAt", s.startedAt)
            .putString("taskId", s.taskId).putInt("cycleCount", s.cycleCount)
            .apply()
        _state.value = s
        arm(s)
        showOngoing(s)
    }

    private fun loadState() = PomodoroState(
        phase = runCatching { PomodoroPhase.valueOf(prefs.getString("phase", null)!!) }.getOrDefault(PomodoroPhase.FOCUS),
        status = runCatching { PomodoroStatus.valueOf(prefs.getString("status", null)!!) }.getOrDefault(PomodoroStatus.IDLE),
        endAt = prefs.getLong("endAt", 0),
        remainingMs = prefs.getLong("remainingMs", 0),
        startedAt = prefs.getLong("startedAt", 0),
        taskId = prefs.getString("taskId", null),
        cycleCount = prefs.getInt("cycleCount", 0),
    )

    private fun loadSettings() = PomodoroSettings(
        focusMin = prefs.getInt("focusMin", 25),
        shortMin = prefs.getInt("shortMin", 5),
        longMin = prefs.getInt("longMin", 15),
        longEvery = prefs.getInt("longEvery", 4),
        alarm = prefs.getBoolean("alarm", true),
    )

    // --- Alarm and notifications ---

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 1001,
        Intent(context, PomodoroReceiver::class.java).setAction(ACTION_END),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun arm(s: PomodoroState) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmIntent()
        if (s.status != PomodoroStatus.RUNNING) {
            am.cancel(pi)
            return
        }
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s.endAt, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s.endAt, pi)
    }

    private fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Фокус (Pomodoro)", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 1002,
        MainActivity.launchIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun action(action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        context, code,
        Intent(context, PomodoroReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * While a phase runs or is paused, the notification counts down. "Стоп" only pauses (it can be
     * resumed); "Сбросить" abandons the phase, as the buttons in the app do.
     */
    private fun showOngoing(s: PomodoroState) {
        val nm = NotificationManagerCompat.from(context)
        if (s.status == PomodoroStatus.IDLE || !ReminderNotifier.canNotify(context)) {
            nm.cancel(ONGOING_ID)
            return
        }
        ensureChannel()
        val running = s.status == PomodoroStatus.RUNNING
        val left = if (running) (s.endAt - now()).coerceAtLeast(0) else s.remainingMs
        val status = if (running) "Идёт таймер" else "На паузе"
        // The time left on a line of its own, large: a countdown Chronometer ticks by itself while
        // running; paused, it stands still showing what is left.
        val body = RemoteViews(context.packageName, R.layout.notification_timer).apply {
            setTextViewText(R.id.timer_phase, s.phase.label)
            setChronometer(R.id.timer_clock, SystemClock.elapsedRealtime() + left, null, running)
            setChronometerCountDown(R.id.timer_clock, true)
            setTextViewText(R.id.timer_state, status)
        }
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ThemeManager.palette(context).accent)
            // Title and text stay for places that don't show custom views (lock screen, watches).
            .setContentTitle(s.phase.label)
            .setContentText(status + " · осталось %d:%02d".format(left / 60_000, left / 1000 % 60))
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(body)
            .setCustomBigContentView(body)
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
        if (running) {
            builder.addAction(android.R.drawable.ic_media_pause, "Стоп", action(ACTION_PAUSE, 1003))
        } else {
            builder.addAction(android.R.drawable.ic_media_play, "Продолжить", action(ACTION_RESUME, 1004))
        }
        val n = builder
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Сбросить", action(ACTION_RESET, 1005))
            .build()
        try {
            nm.notify(ONGOING_ID, n)
        } catch (_: SecurityException) {
        }
    }

    /** An alarm-clock channel: alarm sound (heard even when media is muted) and vibration. */
    private fun ensureAlarmChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(ALARM_CHANNEL) != null) return
        val channel = NotificationChannel(ALARM_CHANNEL, "Фокус: будильник", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Звонит, когда заканчивается фокус или перерыв"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 400, 600, 400, 600)
        }
        nm.createNotificationChannel(channel)
    }

    /**
     * The phase is over. With the alarm on, the sound repeats until the user turns it off (or for a
     * minute at most); the notification offers to start the next phase right away.
     */
    private fun notifyFinished(phase: PomodoroPhase) {
        if (!ReminderNotifier.canNotify(context)) return
        val alarm = _settings.value.alarm
        if (alarm) ensureAlarmChannel() else ensureChannel()
        val focusDone = phase == PomodoroPhase.FOCUS
        val text = if (focusDone) "Отличная работа! Время отдохнуть." else "Перерыв окончен — вернёмся к делу."
        val builder = NotificationCompat.Builder(context, if (alarm) ALARM_CHANNEL else CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ThemeManager.palette(context).accent)
            .setContentTitle("${phase.label} — время вышло")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .setCategory(if (alarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (alarm) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)
            .setDeleteIntent(action(ACTION_ALARM_OFF, 1006))
            .addAction(android.R.drawable.ic_lock_idle_alarm, "Выключить", action(ACTION_ALARM_OFF, 1007))
            .addAction(
                android.R.drawable.ic_media_play,
                if (focusDone) "Начать перерыв" else "Начать фокус",
                action(ACTION_START_NEXT, 1008),
            )
        if (alarm) builder.setTimeoutAfter(ALARM_RING_MS)
        val n = builder.build()
        if (alarm) n.flags = n.flags or Notification.FLAG_INSISTENT
        try {
            NotificationManagerCompat.from(context).notify(DONE_ID, n)
        } catch (_: SecurityException) {
        }
    }
}

class PomodoroReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val timer = context.container.pomodoro
        when (intent.action) {
            PomodoroTimer.ACTION_END -> launchAsync(context) { timer.completeIfDue() }
            PomodoroTimer.ACTION_PAUSE -> timer.pause()
            PomodoroTimer.ACTION_RESUME -> timer.resume()
            PomodoroTimer.ACTION_RESET -> timer.reset()
            PomodoroTimer.ACTION_ALARM_OFF -> timer.stopAlarm()
            PomodoroTimer.ACTION_START_NEXT -> {
                timer.stopAlarm()
                timer.start()
            }
        }
    }
}
