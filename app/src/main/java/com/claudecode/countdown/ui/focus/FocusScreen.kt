package com.claudecode.countdown.ui.focus

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.claudecode.countdown.container
import com.claudecode.countdown.data.db.FocusSession
import com.claudecode.countdown.domain.GroupKind
import com.claudecode.countdown.domain.TaskFilter
import com.claudecode.countdown.pomodoro.PomodoroPhase
import com.claudecode.countdown.pomodoro.PomodoroSettings
import com.claudecode.countdown.pomodoro.PomodoroStatus
import com.claudecode.countdown.reminders.ReminderNotifier
import com.claudecode.countdown.ui.AppSnackbarHost
import com.claudecode.countdown.ui.stats.DayBarChart
import com.claudecode.countdown.domain.dayRange
import com.claudecode.countdown.domain.focusMinutesPerDay
import com.claudecode.countdown.ui.LocalSnackbarHost
import com.claudecode.countdown.pomodoro.MIN_FOCUS_MS
import androidx.compose.material3.Switch
import com.claudecode.countdown.ui.rememberNow
import com.claudecode.countdown.ui.tasks.Snapshot
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val WEEK_DAYS = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusScreen(snapshot: Snapshot, onOpenStats: () -> Unit) {
    val context = LocalContext.current
    val timer = remember { context.container.pomodoro }
    val focus = remember { context.container.focus }
    val scope = rememberCoroutineScope()
    val state by timer.state.collectAsState()
    val settings by timer.settings.collectAsState()
    val now by rememberNow(1_000, enabled = state.status == PomodoroStatus.RUNNING)

    val zone = ZoneId.systemDefault()
    val weekStart = remember(snapshot.today) { snapshot.today.minusDays(6) }
    val since = remember(weekStart) { weekStart.atStartOfDay(zone).toInstant().toEpochMilli() }
    val sessions by remember(since) { focus.observeFocusSince(since) }.collectAsState(initial = emptyList())
    val totalMs by remember { focus.observeTotalFocusMs() }.collectAsState(initial = 0L)

    var editSettings by remember { mutableStateOf(false) }
    var taskMenu by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    val remaining = timer.remainingMs(state, now)
    val total = timer.durationMs(state.phase, settings)
    val snackbar = LocalSnackbarHost.current
    suspend fun reset() {
        val wasFocus = state.phase == PomodoroPhase.FOCUS
        val counted = timer.reset()
        if (wasFocus) {
            snackbar.showSnackbar(
                if (counted > 0) "Засчитано: ${counted / 60_000} мин фокуса" else "Меньше минуты — помидор не засчитан"
            )
        }
    }
    LaunchedEffect(remaining, state.status) {
        if (state.status == PomodoroStatus.RUNNING && remaining <= 0) timer.completeIfDue()
    }

    Scaffold(
        snackbarHost = { AppSnackbarHost() },
        topBar = {
            TopAppBar(
                title = { Text("Фокус") },
                actions = { IconButton(onClick = { editSettings = true }) { Icon(Icons.Outlined.Settings, "Настройки таймера") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in PomodoroPhase.entries) {
                    FilterChip(
                        selected = state.phase == p,
                        onClick = { timer.selectPhase(p) },
                        enabled = state.status == PomodoroStatus.IDLE || state.phase == p,
                        label = { Text(p.label) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { if (total == 0L) 0f else remaining.toFloat() / total },
                    modifier = Modifier.size(260.dp),
                    strokeWidth = 12.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val secs = (remaining + 999) / 1000
                    Text(
                        "%02d:%02d".format(secs / 60, secs % 60),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        when (state.status) {
                            PomodoroStatus.RUNNING -> state.phase.label
                            PomodoroStatus.PAUSED -> "Пауза"
                            PomodoroStatus.IDLE -> "Готов к старту"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            val openTasks = remember(snapshot) {
                snapshot.groups(TaskFilter.All).filter { it.kind != GroupKind.DONE }.flatMap { it.tasks }.take(40)
            }
            val linked = openTasks.firstOrNull { it.id == state.taskId } ?: snapshot.tasks.firstOrNull { it.id == state.taskId }
            Box {
                TextButton(onClick = { taskMenu = true }) {
                    Text(
                        "Задача: " + (linked?.title ?: "не выбрана"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DropdownMenu(taskMenu, { taskMenu = false }) {
                    DropdownMenuItem(text = { Text("Без задачи") }, onClick = { timer.selectTask(null); taskMenu = false })
                    for (t in openTasks) {
                        DropdownMenuItem(text = { Text(t.title, maxLines = 1) }, onClick = { timer.selectTask(t.id); taskMenu = false })
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                when (state.status) {
                    PomodoroStatus.IDLE -> {
                        Button(onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !ReminderNotifier.canNotify(context)) {
                                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            timer.start()
                        }) { Text("Старт") }
                        OutlinedButton(onClick = { timer.skip() }) { Text("Пропустить") }
                    }
                    // "Стоп" only pauses; "Сбросить" abandons the phase (a focus of a minute or more still counts).
                    PomodoroStatus.RUNNING -> {
                        Button(onClick = { timer.pause() }) { Text("Стоп") }
                        OutlinedButton(onClick = { scope.launch { reset() } }) { Text("Сбросить") }
                    }
                    PomodoroStatus.PAUSED -> {
                        Button(onClick = { timer.resume() }) { Text("Продолжить") }
                        OutlinedButton(onClick = { scope.launch { reset() } }) { Text("Сбросить") }
                    }
                }
            }
            if (state.phase == PomodoroPhase.FOCUS && state.status != PomodoroStatus.IDLE && total - remaining < MIN_FOCUS_MS) {
                Text(
                    "Помидор засчитается, когда пройдёт хотя бы минута",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Spacer(Modifier.height(24.dp))
            FocusStats(sessions, totalMs, snapshot.today, weekStart, onOpenStats)
        }
    }

    if (editSettings) SettingsDialog(settings, onSave = { timer.updateSettings(it); editSettings = false }, onDismiss = { editSettings = false })
}

@Composable
private fun FocusStats(sessions: List<FocusSession>, totalMs: Long, today: LocalDate, weekStart: LocalDate, onOpenStats: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val zone = ZoneId.systemDefault()
    val todaySessions = sessions.filter { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() == today }
    val minutesPerDay = focusMinutesPerDay(sessions, weekStart, 7)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surfaceContainerLow)
            .padding(16.dp)
    ) {
        Text("Статистика", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row {
            StatCell("Сегодня", "${todaySessions.size} 🍅", Modifier.weight(1f))
            StatCell("Минут сегодня", "${todaySessions.sumOf { it.durationMs } / 60_000}", Modifier.weight(1f))
            StatCell("Всего", formatHours(totalMs), Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        Text("Минуты фокуса за 7 дней", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        DayBarChart(minutesPerDay, dayRange(weekStart, 7), today, { "$it мин" })
        TextButton(onClick = onOpenStats, modifier = Modifier.align(Alignment.End)) { Text("Вся статистика") }
    }
}

private fun formatHours(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes < 60) "$minutes мин" else "${minutes / 60} ч ${minutes % 60} мин"
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsDialog(initial: PomodoroSettings, onSave: (PomodoroSettings) -> Unit, onDismiss: () -> Unit) {
    var s by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Таймер") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Stepper("Фокус, мин", s.focusMin, 5..120, 5) { s = s.copy(focusMin = it) }
                Stepper("Перерыв, мин", s.shortMin, 1..30, 1) { s = s.copy(shortMin = it) }
                Stepper("Длинный перерыв, мин", s.longMin, 5..60, 5) { s = s.copy(longMin = it) }
                Stepper("Длинный после каждых", s.longEvery, 2..8, 1) { s = s.copy(longEvery = it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Будильник в конце")
                        Text(
                            "Звук будильника, пока не выключите",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = s.alarm, onCheckedChange = { s = s.copy(alarm = it) })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(s) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, step: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        TextButton(onClick = { onChange((value - step).coerceIn(range)) }) { Text("−") }
        Text("$value", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { onChange((value + step).coerceIn(range)) }) { Text("+") }
    }
}
