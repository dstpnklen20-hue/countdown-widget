package com.claudecode.countdown.ui.calendar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import kotlin.math.abs
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

private enum class CalendarMode(val label: String) {
    DAY("День"), THREE_DAYS("3 дня"), WEEK("Неделя"), MONTH("Месяц"), YEAR("Год")
}

/** What the calendar shows: a mode and the dates it covers. Moving within a page keeps it equal. */
private data class CalendarPage(val mode: CalendarMode, val start: LocalDate, val end: LocalDate) {
    companion object {
        fun of(mode: CalendarMode, selected: LocalDate): CalendarPage = when (mode) {
            CalendarMode.MONTH -> {
                val first = YearMonth.from(selected).atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                CalendarPage(mode, first, first.plusDays(41))
            }
            CalendarMode.WEEK -> {
                val monday = selected.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                CalendarPage(mode, monday, monday.plusDays(6))
            }
            CalendarMode.THREE_DAYS -> CalendarPage(mode, selected, selected.plusDays(2))
            CalendarMode.DAY -> CalendarPage(mode, selected, selected)
            CalendarMode.YEAR -> CalendarPage(mode, selected.withDayOfYear(1), selected.withDayOfYear(selected.lengthOfYear()))
        }
    }
}

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

    val page = CalendarPage.of(mode, selected)
    val rangeStart = page.start
    val rangeEnd = page.end
    // Which way the last move went, so the new period slides in from the matching side.
    var direction by remember { mutableStateOf(0) }

    fun shift(sign: Long) {
        direction = sign.toInt()
        selectedEpoch = when (mode) {
            CalendarMode.YEAR -> selected.plusYears(sign)
            CalendarMode.MONTH -> selected.plusMonths(sign)
            CalendarMode.WEEK -> selected.plusWeeks(sign)
            CalendarMode.THREE_DAYS -> selected.plusDays(3 * sign)
            CalendarMode.DAY -> selected.plusDays(sign)
        }.toEpochDay()
    }

    fun goTo(day: LocalDate) {
        direction = day.compareTo(selected).coerceIn(-1, 1)
        selectedEpoch = day.toEpochDay()
    }

    val dayMonth = DateTimeFormatter.ofPattern("d MMM", ru)
    val title = when (mode) {
        CalendarMode.YEAR -> "${selected.year}"
        CalendarMode.MONTH -> selected.format(DateTimeFormatter.ofPattern("LLLL yyyy", ru)).replaceFirstChar { it.uppercase() }
        CalendarMode.WEEK, CalendarMode.THREE_DAYS -> "${rangeStart.format(dayMonth)} – ${rangeEnd.format(dayMonth)}"
        CalendarMode.DAY -> selected.format(DateTimeFormatter.ofPattern(if (selected.year == today.year) "EE, d MMMM" else "EE, d MMM yyyy", ru))
            .replaceFirstChar { it.uppercase() }
    }

    Scaffold(
        snackbarHost = { AppSnackbarHost() },
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                actions = {
                    // Only shown away from today, so it reads as "go back" rather than a mode.
                    if (selected != today) {
                        FilledTonalButton(
                            onClick = { goTo(today) },
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            modifier = Modifier.height(36.dp),
                        ) {
                            Icon(Icons.Outlined.Today, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("К сегодня", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    IconButton(onClick = { shift(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Назад") }
                    IconButton(onClick = { shift(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Вперёд") }
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
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                CalendarMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { direction = 0; mode = m },
                        shape = SegmentedButtonDefaults.itemShape(i, CalendarMode.entries.size),
                        icon = {},
                    ) { Text(m.label, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
                }
            }
            val swipeThreshold = with(LocalDensity.current) { 72.dp.toPx() }
            val currentShift by rememberUpdatedState(::shift)
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    when {
                        direction > 0 -> slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                        direction < 0 -> slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                        else -> fadeIn() togetherWith fadeOut()
                    }
                },
                label = "calendar",
                modifier = Modifier
                    .fillMaxSize()
                    // Swipe left/right moves to the next/previous day, week, month or year.
                    .pointerInput(Unit) {
                        var dragged = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dragged = 0f },
                            onDragEnd = { if (abs(dragged) > swipeThreshold) currentShift(if (dragged < 0) 1L else -1L) },
                        ) { _, dx -> dragged += dx }
                    },
            ) { shown ->
                // The page sliding out keeps drawing its own period, not the one sliding in.
                val entries = remember(snapshot, shown) { calendarEntries(snapshot.tasks, shown.start, shown.end) }
                val rangeStart = shown.start
                val selected = if (selected in shown.start..shown.end) selected else shown.start
                Column(Modifier.fillMaxSize()) {
                    when (shown.mode) {
                        CalendarMode.YEAR -> YearGrid(shown.start.year, today, entries) { month ->
                            direction = 0
                            selectedEpoch = (if (YearMonth.from(today) == month) today else month.atDay(1)).toEpochDay()
                            mode = CalendarMode.MONTH
                        }
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
                        CalendarMode.THREE_DAYS -> ThreeDays(rangeStart, today, entries, vm, onOpenTask) { day ->
                            direction = 0
                            selectedEpoch = day.toEpochDay()
                            mode = CalendarMode.DAY
                        }
                        CalendarMode.DAY -> DayTimeline(selected, entries[selected].orEmpty(), vm, onOpenTask)
                    }
                }
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
    // The first cell is the Monday on or before the 1st, so a week later is always inside the month.
    val month = firstCell.plusDays(6).month
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
                    val inMonth = day.month == month
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

/** Three day columns on one hour grid; tapping a day header opens that day. */
@Composable
private fun ThreeDays(
    start: LocalDate,
    today: LocalDate,
    entries: Map<LocalDate, List<CalendarEntry>>,
    vm: TasksViewModel,
    onOpenTask: (String) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val days = (0L..2L).map { start.plusDays(it) }
    val allDay = days.map { d -> entries[d].orEmpty().filter { it.task.isAllDay } }
    val byHour = days.map { d -> entries[d].orEmpty().filter { !it.task.isAllDay }.groupBy { localTimeOf(it.task.dueAt!!).hour } }
    val listState = rememberLazyListState()
    LaunchedEffect(start) {
        val hour = byHour.flatMap { it.keys }.minOrNull() ?: if (today in days) LocalTime.now().hour else 8
        listState.scrollToItem((hour - 1).coerceAtLeast(0))
    }
    Column(Modifier.fillMaxSize().padding(end = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Spacer(Modifier.width(HOUR_LABEL_WIDTH))
            for (d in days) {
                val isToday = d == today
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { onOpenDay(d) }.padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(WEEK_DAYS[d.dayOfWeek.value - 1], style = MaterialTheme.typography.labelSmall, color = if (isToday) scheme.primary else scheme.onSurfaceVariant)
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(if (isToday) scheme.primary else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${d.dayOfMonth}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isToday) scheme.onPrimary else scheme.onSurface,
                        )
                    }
                }
            }
        }
        if (allDay.any { it.isNotEmpty() }) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Text(
                    "весь\nдень",
                    modifier = Modifier.width(HOUR_LABEL_WIDTH).padding(start = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
                for (list in allDay) {
                    Column(Modifier.weight(1f).padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (e in list) EntryChip(e, onOpenTask)
                    }
                }
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 88.dp)) {
            items((0..23).toList(), key = { "h$it" }) { hour ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(
                        "%02d:00".format(hour),
                        modifier = Modifier.width(HOUR_LABEL_WIDTH).padding(start = 8.dp, top = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                    for (hours in byHour) {
                        Column(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .border(0.5.dp, scheme.outlineVariant)
                                .padding(2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            for (e in hours[hour].orEmpty()) EntryChip(e, onOpenTask, showTime = true)
                        }
                    }
                }
            }
        }
    }
}

private val HOUR_LABEL_WIDTH = 44.dp

/** A compact coloured block for the multi-day grid. */
@Composable
private fun EntryChip(entry: CalendarEntry, onOpenTask: (String) -> Unit, showTime: Boolean = false) {
    val task = entry.task
    val scheme = MaterialTheme.colorScheme
    val color = priorityColor(task.priority, scheme.primary)
    val faded = entry.projected || task.isDone
    Text(
        (if (showTime && task.dueAt != null) formatTime(task.dueAt) + " " else "") + task.title,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = if (faded) 0.10f else 0.20f))
            .clickable { onOpenTask(task.id) }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        color = if (faded) scheme.onSurfaceVariant else scheme.onSurface,
        textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
    )
}

/** Twelve small months; days with open tasks are tinted. Tapping a month opens it. */
@Composable
private fun YearGrid(year: Int, today: LocalDate, entries: Map<LocalDate, List<CalendarEntry>>, onOpenMonth: (YearMonth) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp).padding(bottom = 88.dp),
    ) {
        for (row in (1..12).chunked(3)) {
            Row(Modifier.fillMaxWidth()) {
                for (m in row) {
                    val month = YearMonth.of(year, m)
                    val offset = month.atDay(1).dayOfWeek.value - 1
                    Column(
                        Modifier.weight(1f).padding(4.dp).clip(RoundedCornerShape(10.dp)).clickable { onOpenMonth(month) }.padding(4.dp),
                    ) {
                        Text(
                            month.format(DateTimeFormatter.ofPattern("LLLL", ru)).replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (YearMonth.from(today) == month) scheme.primary else scheme.onSurface,
                            modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
                        )
                        for (week in 0 until 6) {
                            Row {
                                for (d in 0 until 7) {
                                    val index = week * 7 + d - offset
                                    val day = if (index in 0 until month.lengthOfMonth()) month.atDay(index + 1) else null
                                    val busy = day != null && entries[day].orEmpty().any { !it.task.isDone }
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .aspectRatio(1f)
                                            .clip(CircleShape)
                                            .background(
                                                when {
                                                    day == today -> scheme.primary
                                                    busy -> scheme.primary.copy(alpha = 0.2f)
                                                    else -> Color.Transparent
                                                }
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (day != null) {
                                            Text(
                                                "${day.dayOfMonth}",
                                                fontSize = 9.sp,
                                                color = if (day == today) scheme.onPrimary else scheme.onSurface,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
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
