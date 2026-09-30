package com.claudecode.countdown.ui.stats

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
import com.claudecode.countdown.domain.dayRange
import com.claudecode.countdown.domain.focusMinutesPerDay
import com.claudecode.countdown.domain.formatDuration
import com.claudecode.countdown.pluralRu
import java.time.LocalDate
import java.time.ZoneId

/**
 * Completed tasks and focus in one place: headline numbers for the chosen period, one chart per
 * measure (never two scales on one chart), and all-time totals.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(today: LocalDate) {
    val context = LocalContext.current
    val tasks = remember { context.container.tasks }
    val focus = remember { context.container.focus }
    var days by rememberSaveable { mutableIntStateOf(7) }
    val from = today.minusDays(days - 1L)
    val since = remember(from) { from.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }

    val completed by remember(since) { tasks.observeCompletedSince(since) }.collectAsState(initial = emptyList())
    val sessions by remember(since) { focus.observeFocusSince(since) }.collectAsState(initial = emptyList())
    val completedTotal by remember { tasks.observeCompletedCount() }.collectAsState(initial = 0)
    val pomodoroTotal by remember { focus.observeFocusCount() }.collectAsState(initial = 0)
    val focusTotalMs by remember { focus.observeTotalFocusMs() }.collectAsState(initial = 0L)

    val range = remember(from, days) { dayRange(from, days) }
    val donePerDay = remember(completed, range) { countPerDay(completed, from, days) }
    val minutesPerDay = remember(sessions, range) { focusMinutesPerDay(sessions, from, days) }
    val todayIndex = range.indexOf(today)

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
            val periods = listOf(7 to "Неделя", 30 to "Месяц")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                periods.forEachIndexed { i, (n, label) ->
                    SegmentedButton(
                        selected = days == n,
                        onClick = { days = n },
                        shape = SegmentedButtonDefaults.itemShape(i, periods.size),
                        icon = {},
                    ) { Text(label) }
                }
            }

            Card {
                Text(
                    if (days == 7) "За неделю" else "За 30 дней",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    Tile("${donePerDay.sum()}", "задач выполнено", Modifier.weight(1f))
                    Tile("${sessions.size}", "помидоров", Modifier.weight(1f))
                    Tile(formatDuration(sessions.sumOf { it.durationMs }), "фокуса", Modifier.weight(1f))
                }
                if (todayIndex >= 0) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Сегодня: ${tasksText(donePerDay[todayIndex])} · ${minutesPerDay[todayIndex]} мин фокуса",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card {
                ChartTitle("Выполненные задачи")
                DayBarChart(donePerDay, range, today, ::tasksText)
            }

            Card {
                ChartTitle("Минуты фокуса")
                DayBarChart(minutesPerDay, range, today, { "$it мин" })
            }

            Card {
                ChartTitle("За всё время")
                Row {
                    Tile("$completedTotal", "задач выполнено", Modifier.weight(1f))
                    Tile("$pomodoroTotal", "помидоров", Modifier.weight(1f))
                    Tile(formatDuration(focusTotalMs), "фокуса", Modifier.weight(1f))
                }
            }
        }
    }
}

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
