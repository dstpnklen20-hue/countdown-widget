package com.claudecode.countdown.pomodoro

import android.app.AlarmManager
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

data class PomodoroSettings(val focusMin: Int = 25, val shortMin: Int = 5, val longMin: Int = 15, val longEvery: Int = 4)

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
        private const val PREFS = "pomodoro"
        private const val CHANNEL = "focus"
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
            .putInt("longMin", s.longMin).putInt("longEvery", s.longEvery).apply()
        _settings.value = s
    }

    fun selectTask(taskId: String?) = mutate { it.copy(taskId = taskId) }

    fun selectPhase(phase: PomodoroPhase) = mutate { if (it.status == PomodoroStatus.IDLE) it.copy(phase = phase) else it }

    fun start() = mutate {
        if (it.status != PomodoroStatus.IDLE) return@mutate it
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

    /** Abandons the phase; a focus of at least a minute still counts towards statistics. */
    suspend fun reset() {
        val before = _state.value
        mutate { it.copy(status = PomodoroStatus.IDLE, endAt = 0, remainingMs = 0) }
        if (before.phase == PomodoroPhase.FOCUS && before.status != PomodoroStatus.IDLE) {
            val spent = durationMs(before.phase) - remainingMs(before)
            if (spent >= 60_000) record(before, spent)
        }
    }

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
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun showOngoing(s: PomodoroState) {
        val nm = NotificationManagerCompat.from(context)
        if (s.status != PomodoroStatus.RUNNING || !ReminderNotifier.canNotify(context)) {
            nm.cancel(ONGOING_ID)
            return
        }
        ensureChannel()
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ThemeManager.palette(context).accent)
            .setContentTitle(s.phase.label)
            .setContentText("Идёт таймер")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(s.endAt)
            .setShowWhen(true)
            .setContentIntent(openApp())
            .build()
        try {
            nm.notify(ONGOING_ID, n)
        } catch (_: SecurityException) {
        }
    }

    private fun notifyFinished(phase: PomodoroPhase) {
        if (!ReminderNotifier.canNotify(context)) return
        ensureChannel()
        val text = if (phase == PomodoroPhase.FOCUS) "Отличная работа! Время отдохнуть." else "Перерыв окончен — вернёмся к делу."
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ThemeManager.palette(context).accent)
            .setContentTitle("${phase.label} завершён")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        try {
            NotificationManagerCompat.from(context).notify(DONE_ID, n)
        } catch (_: SecurityException) {
        }
    }
}

class PomodoroReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PomodoroTimer.ACTION_END) return
        launchAsync(context) { context.container.pomodoro.completeIfDue() }
    }
}
