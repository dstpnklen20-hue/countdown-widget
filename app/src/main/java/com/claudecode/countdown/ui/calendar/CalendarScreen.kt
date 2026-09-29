package com.claudecode.countdown.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.lazy.rememberLazyListState
import java.time.LocalTime
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.claudecode.countdown.data.db.TaskList
import com.claudecode.countdown.domain.CalendarEntry
import com.claudecode.countdown.domain.allDayDue
import com.claudecode.countdown.domain.calendarEntries
import com.claudecode.countdown.domain.localTimeOf
import com.claudecode.countdown.ui.AppSnackbarHost
import com.claudecode.countdown.ui.PriorityCheckbox
import com.claudecode.countdown.ui.TextInputDialog
import com.claudecode.countdown.ui.formatFullDate
import com.claudecode.countdown.ui.formatTime
import com.claudecode.countdown.ui.priorityColor
import com.claudecode.countdown.ui.tasks.Snapshot
import com.claudecode.countdown.ui.tasks.TasksViewModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private enum class CalendarMode(val label: String) { MONTH("Месяц"), WEEK("Неделя"), DAY("День") }

private val ru = Locale("ru")
private val WEEK_DAYS = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(vm: TasksViewModel, snapshot: Snapshot, onOpenTask: (String) -> Unit) {
    val today = snapshot.today
    var mode by rememberSaveable { mutableStateOf(CalendarMode.MONTH) }
    var selectedEpoch by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    val selected = LocalDate.ofEpochDay(selectedEpoch)
    var adding by remember { mutableStateOf(false) }

    val (rangeStart, rangeEnd) = when (mode) {
        CalendarMode.MONTH -> {
            val first = YearMonth.from(selected).atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            first to first.plusDays(41)
        }
        CalendarMode.WEEK -> {
            val monday = selected.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            monday to monday.plusDays(6)
        }
        CalendarMode.DAY -> selected to selected
    }
    val entries = remember(snapshot, rangeStart, rangeEnd) { calendarEntries(snapshot.tasks, rangeStart, rangeEnd) }

    fun shift(sign: Long) {
        selectedEpoch = when (mode) {
            CalendarMode.MONTH -> selected.plusMonths(sign)
            CalendarMode.WEEK -> selected.plusWeeks(sign)
            CalendarMode.DAY -> selected.plusDays(sign)
        }.toEpochDay()
    }

    val title = when (mode) {
        CalendarMode.MONTH -> selected.format(DateTimeFormatter.ofPattern("LLLL yyyy", ru)).replaceFirstChar { it.uppercase() }
        CalendarMode.WEEK -> "${rangeStart.format(DateTimeFormatter.ofPattern("d MMM", ru))} – ${rangeEnd.format(DateTimeFormatter.ofPattern("d MMM", ru))}"
        CalendarMode.DAY -> formatFullDate(selected)
    }

    Scaffold(
        snackbarHost = { AppSnackbarHost() },
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = { shift(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Назад") }
                },
                actions = {
                    IconButton(onClick = { shift(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Вперёд") }
                    TextButton(onClick = { selectedEpoch = today.toEpochDay() }) { Text("Сегодня") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { adding = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Icon(Icons.Filled.Add, "Добавить задачу на выбранный день") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                CalendarMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { mode = m },
                        shape = SegmentedButtonDefaults.itemShape(i, CalendarMode.entries.size),
                    ) { Text(m.label) }
                }
            }
            when (mode) {
                CalendarMode.MONTH -> {
                    MonthGrid(rangeStart, selected, today, entries) { selectedEpoch = it.toEpochDay() }
                    HorizontalDivider(Modifier.padding(top = 4.dp))
                    DayAgenda(selected, today, entries[selected].orEmpty(), vm, onOpenTask, header = true)
                }
                CalendarMode.WEEK -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                    for (i in 0L..6L) {
                        val day = rangeStart.plusDays(i)
                        item(key = "h$i") { DayHeader(day, today, selected == day) { selectedEpoch = day.toEpochDay() } }
                        val list = entries[day].orEmpty()
                        if (list.isEmpty()) {
                            item(key = "e$i") {
                                Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 56.dp, bottom = 4.dp))
                            }
                        }
                        items(list, key = { "t$i:${it.task.id}:${it.projected}" }) { EntryRow(it, vm, onOpenTask) }
                    }
                }
                CalendarMode.DAY -> DayTimeline(selected, entries[selected].orEmpty(), vm, onOpenTask)
            }
        }
    }

    if (adding) {
        TextInputDialog(
            title = "Задача на ${selected.format(DateTimeFormatter.ofPattern("d MMMM", ru))}",
            confirmLabel = "Добавить",
            onConfirm = { vm.addTask(it, TaskList.INBOX_ID, due = allDayDue(selected)); adding = false },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun MonthGrid(
    firstCell: LocalDate,
    selected: LocalDate,
    today: LocalDate,
    entries: Map<LocalDate, List<CalendarEntry>>,
    onSelect: (LocalDate) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 8.dp)) {
        Row {
            for (d in WEEK_DAYS) {
                Text(
                    d, Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
                )
            }
        }
        for (week in 0 until 6) {
            Row {
                for (d in 0 until 7) {
                    val day = firstCell.plusDays((week * 7 + d).toLong())
                    val inMonth = day.month == selected.month
                    val dayEntries = entries[day].orEmpty().filter { !it.task.isDone }
                    Column(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .padding(2.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (day == selected) scheme.primaryContainer else Color.Transparent)
                            .clickable { onSelect(day) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            Modifier
                                .size(26.dp)
                                .then(if (day == today) Modifier.border(1.5.dp, scheme.primary, CircleShape) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${day.dayOfMonth}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
                                color = when {
                                    !inMonth -> scheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    day == today -> scheme.primary
                                    else -> scheme.onSurface
                                },
                            )
                        }
                        Row(Modifier.height(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            for (e in dayEntries.take(3)) {
                                Box(
                                    Modifier.size(5.dp).clip(CircleShape).background(
                                        priorityColor(e.task.priority, scheme.primary).copy(alpha = if (e.projected) 0.45f else 1f)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(day: LocalDate, today: LocalDate, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            WEEK_DAYS[day.dayOfWeek.value - 1],
            modifier = Modifier.width(32.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (day == today) scheme.primary else scheme.onSurfaceVariant,
        )
        Text(
            day.format(DateTimeFormatter.ofPattern("d MMMM", ru)),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (day == today || selected) FontWeight.Bold else FontWeight.Normal,
            color = if (day == today) scheme.primary else scheme.onSurface,
        )
    }
}

@Composable
private fun DayAgenda(
    day: LocalDate,
    today: LocalDate,
    entries: List<CalendarEntry>,
    vm: TasksViewModel,
    onOpenTask: (String) -> Unit,
    header: Boolean,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
        if (header) item { DayHeader(day, today, true) {} }
        if (entries.isEmpty()) {
            item {
                Text(
                    "Нет задач на этот день",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        items(entries, key = { "${it.task.id}:${it.projected}" }) { EntryRow(it, vm, onOpenTask) }
    }
}

/** Hour-by-hour day view; all-day tasks sit above the timeline. */
@Composable
private fun DayTimeline(day: LocalDate, entries: List<CalendarEntry>, vm: TasksViewModel, onOpenTask: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val allDay = entries.filter { it.task.isAllDay }
    val byHour = entries.filter { !it.task.isAllDay }.groupBy { localTimeOf(it.task.dueAt!!).hour }
    val listState = rememberLazyListState()
    LaunchedEffect(day) {
        // Start at the first timed task, the current hour today, or the morning.
        val hour = byHour.keys.minOrNull() ?: if (day == LocalDate.now()) LocalTime.now().hour else 8
        val allDayItems = if (allDay.isEmpty()) 0 else allDay.size + 2
        listState.scrollToItem(allDayItems + (hour - 1).coerceAtLeast(0))
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 88.dp)) {
        if (allDay.isNotEmpty()) {
            item { Text("Весь день", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant, modifier = Modifier.padding(16.dp, 8.dp)) }
            items(allDay, key = { "a:${it.task.id}:${it.projected}" }) { EntryRow(it, vm, onOpenTask) }
            item { HorizontalDivider() }
        }
        items((0..23).toList(), key = { "h$it" }) { hour ->
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(
                    "%02d:00".format(hour),
                    modifier = Modifier.width(56.dp).padding(start = 12.dp, top = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
                Column(Modifier.weight(1f)) {
                    HorizontalDivider(color = scheme.outlineVariant)
                    for (e in byHour[hour].orEmpty()) EntryRow(e, vm, onOpenTask, showTime = true)
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: CalendarEntry, vm: TasksViewModel, onOpenTask: (String) -> Unit, showTime: Boolean = true) {
    val task = entry.task
    val scheme = MaterialTheme.colorScheme
    val faded = entry.projected || task.isDone
    Row(
        Modifier.fillMaxWidth().clickable { onOpenTask(task.id) }.padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.projected) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Repeat, "Будущий повтор", tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        } else {
            PriorityCheckbox(task.isDone, task.priority, { vm.toggleDone(task) })
        }
        Text(
            task.title,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (faded) scheme.onSurfaceVariant else scheme.onSurface,
            textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
        )
        if (showTime && !task.isAllDay && task.dueAt != null) {
            Text(formatTime(task.dueAt), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        }
    }
}
