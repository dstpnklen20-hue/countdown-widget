package com.claudecode.countdown.ui.stats

import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import com.claudecode.countdown.domain.eventMinutesByCalendar
import com.claudecode.countdown.data.db.CalendarLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.claudecode.countdown.container
import com.claudecode.countdown.domain.countPerDay
import com.claudecode.countdown.domain.countPerMonth
import com.claudecode.countdown.domain.focusMinutesPerMonth
import com.claudecode.countdown.domain.monthRange
import com.claudecode.countdown.domain.dayRange
import com.claudecode.countdown.domain.focusMinutesPerDay
import com.claudecode.countdown.domain.formatDuration
import com.claudecode.countdown.pluralRu
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Completed tasks and focus in one place: headline numbers for the chosen period, one chart per
 * measure (never two scales on one chart); the whole history is shown by months.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(today: LocalDate) {
    val context = LocalContext.current
    val tasks = remember { context.container.tasks }
    val focus = remember { context.container.focus }
    // 7 or 30 days, or ALL_TIME for the whole history by months.
    var days by rememberSaveable { mutableIntStateOf(7) }
    val allTime = days == ALL_TIME
    val from = if (allTime) LocalDate.ofEpochDay(0) else today.minusDays(days - 1L)
    val since = remember(from) { if (allTime) 0L else from.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }

    val completed by remember(since) { tasks.observeCompletedSince(since) }.collectAsState(initial = emptyList())
    val sessions by remember(since) { focus.observeFocusSince(since) }.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Статистика") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val periods = listOf(7 to "Неделя", 30 to "Месяц", ALL_TIME to "За всё время")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                periods.forEachIndexed { i, (n, label) ->
                    SegmentedButton(
                        selected = days == n,
                        onClick = { days = n },
                        shape = SegmentedButtonDefaults.itemShape(i, periods.size),
                        icon = {},
                    ) { Text(label, maxLines = 1) }
                }
            }

            Card {
                Text(
                    when (days) {
                        7 -> "За неделю"
                        30 -> "За 30 дней"
                        else -> "За всё время"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    Tile("${completed.size}", "задач выполнено", Modifier.weight(1f))
                    Tile("${sessions.size}", "помидоров", Modifier.weight(1f))
                    Tile(formatDuration(sessions.sumOf { it.durationMs }), "фокуса", Modifier.weight(1f))
                }
                val todayStart = remember(today) { today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
                val doneToday = completed.count { it >= todayStart }
                val focusToday = sessions.filter { it.startedAt >= todayStart }.sumOf { it.durationMs } / 60_000
                Spacer(Modifier.height(10.dp))
                Text(
                    "Сегодня: ${tasksText(doneToday)} · $focusToday мин фокуса",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            CalendarTime(if (allTime) today.minusDays(364) else from, today)

            if (allTime) {
                // From the first month with anything in it, but never fewer than six bars.
                val months = remember(completed, sessions, today) {
                    val first = (completed + sessions.map { it.startedAt }).minOrNull()
                        ?.let { YearMonth.from(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) }
                    val current = YearMonth.from(today)
                    monthRange(minOf(first ?: current, current.minusMonths(5)), current)
                }
                val donePerMonth = remember(completed, months) { countPerMonth(completed, months) }
                val minutesPerMonth = remember(sessions, months) { focusMinutesPerMonth(sessions, months) }
                Card {
                    ChartTitle("Выполненные задачи по месяцам")
                    MonthBarChart(donePerMonth, months, YearMonth.from(today), ::tasksText)
                }
                Card {
                    ChartTitle("Фокус по месяцам")
                    MonthBarChart(minutesPerMonth, months, YearMonth.from(today), { formatDuration(it * 60_000L) })
                }
            } else {
                val range = remember(from, days) { dayRange(from, days) }
                val donePerDay = remember(completed, range) { countPerDay(completed, from, days) }
                val minutesPerDay = remember(sessions, range) { focusMinutesPerDay(sessions, from, days) }
                Card {
                    ChartTitle("Выполненные задачи")
                    DayBarChart(donePerDay, range, today, ::tasksText)
                }
                Card {
                    ChartTitle("Минуты фокуса")
                    DayBarChart(minutesPerDay, range, today, { "$it мин" })
                }
            }
        }
    }
}

/** Hours of events per calendar over the period, each a bar in the calendar's colour. */
@Composable
private fun CalendarTime(from: LocalDate, to: LocalDate) {
    val context = LocalContext.current
    val repo = remember { context.container.tasks }
    val all by remember { repo.observeTopLevel() }.collectAsState(initial = emptyList())
    val calendars by remember { repo.observeCalendars() }.collectAsState(initial = emptyList())
    val minutes = remember(all, from, to) { eventMinutesByCalendar(all, from, to) }
    if (minutes.isEmpty()) return
    val rows = calendars.map { c -> c to (minutes[c.id] ?: 0) + if (c.id == CalendarLayer.PERSONAL_ID) minutes[null] ?: 0 else 0 }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
    val top = rows.maxOfOrNull { it.second } ?: return
    Card {
        ChartTitle("Время по календарям")
        Text(
            "Часы событий за период, с повторами",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        for ((c, m) in rows) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(96.dp), maxLines = 1)
                Box(Modifier.weight(1f).height(14.dp)) {
                    Box(
                        Modifier.fillMaxWidth(m.toFloat() / top).height(14.dp).clip(RoundedCornerShape(4.dp))
                            .background(androidx.compose.ui.graphics.Color(c.color))
                    )
                }
                Text(
                    formatDuration(m * 60_000L),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 8.dp).width(64.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
    }
}

/** The period choice for the whole history. */
private const val ALL_TIME = 0

private fun tasksText(n: Int) = "$n ${pluralRu(n.toLong(), "задача", "задачи", "задач")}"

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun ChartTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
}

/** A headline number with its caption; the number in text colour, not the accent. */
@Composable
private fun Tile(value: String, caption: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
