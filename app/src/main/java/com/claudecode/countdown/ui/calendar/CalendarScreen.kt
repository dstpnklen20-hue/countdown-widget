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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.claudecode.countdown.ui.AddFab
import com.claudecode.countdown.data.db.Task
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import com.claudecode.countdown.domain.timedDue
import com.claudecode.countdown.domain.dayRange
import com.claudecode.countdown.domain.layoutBlocks
import com.claudecode.countdown.domain.blockMinutes
import com.claudecode.countdown.domain.TimeBlock
import com.claudecode.countdown.domain.MINUTES_PER_DAY
import com.claudecode.countdown.ui.rememberNow
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

/** Resolves a task's colour (own, else its list's); provided by [CalendarScreen] from the snapshot. */
private val LocalColorOf = staticCompositionLocalOf<(Task) -> Int?> { { it.color } }

/** Calendar marks use the task/list colour, falling back to the priority colour. */
@Composable
private fun entryColor(task: Task): Color =
    LocalColorOf.current(task)?.let { Color(it) } ?: priorityColor(task.priority, MaterialTheme.colorScheme.primary)

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
    var addingAt by remember { mutableStateOf<Pair<LocalDate, LocalTime>?>(null) }

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

    CompositionLocalProvider(LocalColorOf provides snapshot::colorOf) {
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
                AddFab("Добавить задачу на выбранный день") { adding = true }
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
                            CalendarMode.WEEK, CalendarMode.THREE_DAYS, CalendarMode.DAY -> {
                                val days = if (shown.mode == CalendarMode.DAY) listOf(selected)
                                else dayRange(shown.start, if (shown.mode == CalendarMode.WEEK) 7 else 3)
                                TimeGrid(
                                    days = days,
                                    today = today,
                                    entries = entries,
                                    vm = vm,
                                    onOpenTask = onOpenTask,
                                    onOpenDay = { day ->
                                        direction = 0
                                        selectedEpoch = day.toEpochDay()
                                        mode = CalendarMode.DAY
                                    },
                                    onAddAt = { day, time -> addingAt = day to time },
                                )
                            }
                        }
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
    // A tap on free space of the time scale: a task at that day and half hour.
    addingAt?.let { (day, time) ->
        TextInputDialog(
            title = "Задача на ${day.format(DateTimeFormatter.ofPattern("d MMMM", ru))}, ${"%02d:%02d".format(time.hour, time.minute)}",
            confirmLabel = "Добавить",
            onConfirm = { vm.addTask(it, TaskList.INBOX_ID, due = timedDue(day, time)); addingAt = null },
            onDismiss = { addingAt = null },
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
                                        entryColor(e.task).copy(alpha = if (e.projected) 0.45f else 1f)
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

/**
 * A time scale for one, three or seven days, like TickTick: hours down the side, each timed task
 * a block at its time (tasks at the same time share the width), all-day tasks in a strip above,
 * and a red line at the current time. Tapping free space adds a task at that half hour.
 */
@Composable
private fun TimeGrid(
    days: List<LocalDate>,
    today: LocalDate,
    entries: Map<LocalDate, List<CalendarEntry>>,
    vm: TasksViewModel,
    onOpenTask: (String) -> Unit,
    onOpenDay: ((LocalDate) -> Unit)?,
    onAddAt: (LocalDate, LocalTime) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val single = days.size == 1
    val allDay = days.map { d -> entries[d].orEmpty().filter { it.task.isAllDay || it.task.dueAt == null } }
    val timed = days.map { d -> entries[d].orEmpty().filter { !it.task.isAllDay && it.task.dueAt != null } }
    val scroll = rememberScrollState()
    val hourPx = with(LocalDensity.current) { HOUR_HEIGHT.toPx() }
    LaunchedEffect(days.first(), days.size) {
        // Open at the first timed task, the current hour today, or the morning, with an hour above.
        val firstMinute = timed.flatten().minOfOrNull { blockMinutes(it.task.dueAt!!, it.task.startAt).first }
        val hour = firstMinute?.div(60) ?: if (today in days) LocalTime.now().hour else 8
        scroll.scrollTo(((hour - 1).coerceAtLeast(0) * hourPx).toInt())
    }

    Column(Modifier.fillMaxSize().padding(end = 6.dp)) {
        if (!single) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Spacer(Modifier.width(HOUR_LABEL_WIDTH))
                for (d in days) {
                    val isToday = d == today
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .then(if (onOpenDay != null) Modifier.clickable { onOpenDay(d) } else Modifier)
                            .padding(vertical = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(WEEK_DAYS[d.dayOfWeek.value - 1], style = MaterialTheme.typography.labelSmall, color = if (isToday) scheme.primary else scheme.onSurfaceVariant)
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(if (isToday) scheme.primary else Color.Transparent),
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
        }
        // All-day tasks: full rows with a checkbox for one day, compact chips for several.
        if (allDay.any { it.isNotEmpty() }) {
            if (single) {
                Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                    for (e in allDay.first()) EntryRow(e, vm, onOpenTask)
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                    Text(
                        "весь\nдень",
                        modifier = Modifier.width(HOUR_LABEL_WIDTH).padding(start = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                    for (list in allDay) {
                        Column(Modifier.weight(1f).padding(horizontal = 1.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            for (e in list.take(3)) EntryChip(e, onOpenTask)
                            if (list.size > 3) Text("+${list.size - 3}", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = scheme.outlineVariant)
        val now by rememberNow(60_000)
        Row(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scroll)
                .height(HOUR_HEIGHT * 24)
                .padding(bottom = 0.dp),
        ) {
            // Hour captions sit on the lines they name.
            Box(Modifier.width(HOUR_LABEL_WIDTH).fillMaxHeight()) {
                for (h in 1..23) {
                    Text(
                        "%02d:00".format(h),
                        modifier = Modifier.offset(y = HOUR_HEIGHT * h - 7.dp).padding(start = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            days.forEachIndexed { i, day ->
                DayColumn(
                    day = day,
                    entries = timed[i],
                    isToday = day == today,
                    nowMillis = now,
                    onOpenTask = onOpenTask,
                    onAddAt = onAddAt,
                    compact = days.size > 3,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun DayColumn(
    day: LocalDate,
    entries: List<CalendarEntry>,
    isToday: Boolean,
    nowMillis: Long,
    onOpenTask: (String) -> Unit,
    onAddAt: (LocalDate, LocalTime) -> Unit,
    compact: Boolean,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val line = scheme.outlineVariant
    val halfLine = scheme.outlineVariant.copy(alpha = 0.4f)
    val placed = remember(entries) {
        layoutBlocks(entries.map { e -> blockMinutes(e.task.dueAt!!, e.task.startAt).let { (s, end) -> TimeBlock(e, s, end) } })
    }
    BoxWithConstraints(
        modifier
            .drawBehind {
                val hour = size.height / 24
                drawLine(line, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 0.5.dp.toPx())
                for (h in 1..23) {
                    drawLine(line, Offset(0f, hour * h), Offset(size.width, hour * h), strokeWidth = 0.5.dp.toPx())
                    drawLine(halfLine, Offset(0f, hour * (h - 0.5f)), Offset(size.width, hour * (h - 0.5f)), strokeWidth = 0.5.dp.toPx())
                }
            }
            // Free space: add a task at that half hour. Blocks take their own taps.
            .pointerInput(day) {
                detectTapGestures { offset ->
                    val minute = ((offset.y / (size.height / 24f)) * 60).toInt().coerceIn(0, MINUTES_PER_DAY - 1) / 30 * 30
                    onAddAt(day, LocalTime.of(minute / 60, minute % 60))
                }
            },
    ) {
        for (p in placed) {
            val top = HOUR_HEIGHT * (p.start / 60f)
            val height = (HOUR_HEIGHT * ((p.end - p.start) / 60f)).coerceAtLeast(MIN_BLOCK_HEIGHT)
            val width = maxWidth / p.lanes
            TimeBlockView(
                p.item,
                onOpenTask,
                compact,
                Modifier.offset(x = width * p.lane, y = top).width(width).height(height).padding(horizontal = 1.dp, vertical = 0.5.dp),
            )
        }
        if (isToday) {
            val t = localTimeOf(nowMillis)
            val y = HOUR_HEIGHT * ((t.hour * 60 + t.minute) / 60f)
            val red = Color(0xFFE53935)
            Box(Modifier.offset(y = y - 1.dp).fillMaxWidth().height(2.dp).background(red))
            Box(Modifier.offset(x = (-4).dp, y = y - 4.dp).size(8.dp).clip(CircleShape).background(red))
        }
    }
}

/** A task on the time scale: tinted block with a solid edge in the task's colour. */
@Composable
private fun TimeBlockView(entry: CalendarEntry, onOpenTask: (String) -> Unit, compact: Boolean, modifier: Modifier) {
    val task = entry.task
    val scheme = MaterialTheme.colorScheme
    val color = entryColor(task)
    val faded = entry.projected || task.isDone
    Column(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = if (faded) 0.12f else 0.24f))
            .drawBehind { drawRect(color.copy(alpha = if (faded) 0.5f else 1f), size = Size(3.dp.toPx(), size.height)) }
            .clickable { onOpenTask(task.id) }
            .padding(start = 5.dp, end = 2.dp, top = 1.dp),
    ) {
        Text(
            task.title,
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = if (faded) scheme.onSurfaceVariant else scheme.onSurface,
            textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
        )
    }
}

private val HOUR_HEIGHT = 56.dp
private val MIN_BLOCK_HEIGHT = 22.dp

private val HOUR_LABEL_WIDTH = 44.dp

/** A compact coloured block for the multi-day grid. */
@Composable
private fun EntryChip(entry: CalendarEntry, onOpenTask: (String) -> Unit, showTime: Boolean = false) {
    val task = entry.task
    val scheme = MaterialTheme.colorScheme
    val color = entryColor(task)
    val faded = entry.projected || task.isDone
    Text(
        (if (showTime && task.dueAt != null) formatTime(task.dueAt) + " " else "") + task.title,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = if (faded) 0.10f else 0.22f))
            // Solid edge keeps the colour readable on dark themes, where the tint is faint.
            .drawBehind { drawRect(color.copy(alpha = if (faded) 0.5f else 1f), size = Size(3.dp.toPx(), size.height)) }
            .padding(start = 3.dp)
            .clickable { onOpenTask(task.id) }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        // One line keeps the all-day strip short above the time scale.
        maxLines = 1,
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
        LocalColorOf.current(task)?.let { c ->
            Box(Modifier.padding(end = 8.dp).size(8.dp).clip(CircleShape).background(Color(c)))
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
