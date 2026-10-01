package com.claudecode.countdown.ui.calendar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CalendarViewDay
import androidx.compose.material.icons.outlined.CalendarViewWeek
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claudecode.countdown.container
import com.claudecode.countdown.data.db.DisplayMode
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.domain.CalendarEntry
import com.claudecode.countdown.domain.calendarEntries
import com.claudecode.countdown.domain.dayRange
import com.claudecode.countdown.ui.AddFab
import com.claudecode.countdown.ui.AppSnackbarHost
import com.claudecode.countdown.ui.CalendarDayIcon
import com.claudecode.countdown.ui.Motion
import com.claudecode.countdown.ui.NewEntryDialog
import com.claudecode.countdown.ui.priorityColor
import com.claudecode.countdown.ui.tasks.Snapshot
import com.claudecode.countdown.ui.tasks.TasksViewModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs

internal enum class CalendarMode(val label: String, val icon: ImageVector) {
    SCHEDULE("Расписание", Icons.Outlined.ViewAgenda),
    DAY("День", Icons.Outlined.CalendarViewDay),
    THREE_DAYS("3 дня", Icons.Outlined.ViewColumn),
    WEEK("Неделя", Icons.Outlined.CalendarViewWeek),
    MONTH("Месяц", Icons.Outlined.CalendarMonth),
    YEAR("Год", Icons.Outlined.DateRange),
}

/** What the calendar shows: a mode and the dates it covers. Moving within a page keeps it equal. */
private data class CalendarPage(val mode: CalendarMode, val start: LocalDate, val end: LocalDate) {
    companion object {
        fun of(mode: CalendarMode, selected: LocalDate, today: LocalDate): CalendarPage = when (mode) {
            // The schedule is one long list around today; the selected day only sets where it scrolls.
            CalendarMode.SCHEDULE -> CalendarPage(mode, today.minusDays(SCHEDULE_DAYS_BACK), today.plusDays(SCHEDULE_DAYS_AHEAD))
            CalendarMode.MONTH -> {
                val month = YearMonth.from(selected)
                CalendarPage(mode, month.atDay(1), month.atEndOfMonth())
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

internal const val SCHEDULE_DAYS_BACK = 60L
internal const val SCHEDULE_DAYS_AHEAD = 400L

/** Resolves a task's colour (own, else its list's); provided by [CalendarScreen] from the snapshot. */
private val LocalColorOf = staticCompositionLocalOf<(Task) -> Int?> { { it.color } }

/** Events and countdowns are things that happen; the rest are tasks to do. */
internal val Task.happens: Boolean get() = isEvent || displayMode == DisplayMode.COUNTDOWN

/** The task/list colour; events without one take the accent, tasks their priority's colour. */
@Composable
internal fun entryColor(task: Task): Color {
    val scheme = MaterialTheme.colorScheme
    return LocalColorOf.current(task)?.let { Color(it) }
        ?: if (task.happens) scheme.primary else priorityColor(task.priority, scheme.primary)
}

/** Dark text on light fills (yellow), white on dark ones (violet), as on the Google Calendar blocks. */
internal fun textOn(fill: Color): Color = if (fill.luminance() > 0.36f) Color(0xFF1C1B1F) else Color.White

internal val ru = Locale("ru")
internal val WEEK_DAYS = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")

/** What a tap asked to create: a new entry on a day, at a time for a tap on the time scale. */
private data class Draft(val date: LocalDate, val time: LocalTime?, val event: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(vm: TasksViewModel, snapshot: Snapshot, onOpenTask: (String) -> Unit) {
    val today = snapshot.today
    val appSettings = LocalContext.current.container.settings
    val settings by appSettings.state.collectAsStateWithLifecycle()
    val mode = CalendarMode.entries.firstOrNull { it.name == settings.calendarMode } ?: CalendarMode.MONTH
    var selectedEpoch by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    val selected = LocalDate.ofEpochDay(selectedEpoch)
    var monthPanel by rememberSaveable { mutableStateOf(false) }
    var viewMenu by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<Draft?>(null) }
    // Which way the last move went, so the new period slides in from the matching side.
    var direction by remember { mutableStateOf(0) }
    // Counts "go to" requests, so the schedule scrolls back even when the day stays the same.
    var jumps by remember { mutableStateOf(0) }

    // The view menu can hide events or tasks.
    val tasks = remember(snapshot.tasks, settings.calendarEvents, settings.calendarTasks) {
        snapshot.tasks.filter { if (it.happens) settings.calendarEvents else settings.calendarTasks }
    }
    val page = CalendarPage.of(mode, selected, today)

    fun setMode(m: CalendarMode) {
        direction = 0
        appSettings.setCalendarMode(m.name)
    }

    fun shift(sign: Long) {
        direction = sign.toInt()
        selectedEpoch = when (mode) {
            CalendarMode.YEAR -> selected.plusYears(sign)
            CalendarMode.MONTH -> selected.plusMonths(sign)
            CalendarMode.WEEK -> selected.plusWeeks(sign)
            CalendarMode.THREE_DAYS -> selected.plusDays(3 * sign)
            CalendarMode.DAY, CalendarMode.SCHEDULE -> selected.plusDays(sign)
        }.toEpochDay()
    }

    fun goTo(day: LocalDate) {
        direction = day.compareTo(selected).coerceIn(-1, 1)
        jumps++
        selectedEpoch = day.toEpochDay()
    }

    // The month in the title follows the page (the first day of a week that spans two months).
    val shownMonth = YearMonth.from(if (mode == CalendarMode.WEEK || mode == CalendarMode.THREE_DAYS) page.start else selected)
    val title = when (mode) {
        CalendarMode.YEAR -> "${selected.year}"
        else -> shownMonth.format(DateTimeFormatter.ofPattern("LLLL", ru)).replaceFirstChar { it.uppercase() } +
            if (shownMonth.year != today.year) " ${shownMonth.year}" else ""
    }

    CompositionLocalProvider(LocalColorOf provides snapshot::colorOf) {
        Scaffold(
            snackbarHost = { AppSnackbarHost() },
            topBar = {
                TopAppBar(
                    title = {
                        // Like Google Calendar: the month name opens a small month to jump around in.
                        Row(
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable { monthPanel = !monthPanel }.padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                            val turn by animateFloatAsState(if (monthPanel) 180f else 0f, Motion.soft(), label = "panel")
                            Icon(Icons.Filled.ArrowDropDown, if (monthPanel) "Скрыть месяц" else "Показать месяц", Modifier.rotate(turn))
                        }
                    },
                    actions = {
                        IconButton(onClick = { goTo(today); monthPanel = false }) { CalendarDayIcon(today.dayOfMonth, "К сегодня") }
                        Box {
                            IconButton(onClick = { viewMenu = true }) { Icon(mode.icon, "Вид: ${mode.label}") }
                            ViewMenu(viewMenu, mode, settings.calendarEvents, settings.calendarTasks,
                                onMode = { setMode(it); viewMenu = false },
                                onFilter = appSettings::setCalendarFilter,
                                onDismiss = { viewMenu = false },
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            floatingActionButton = {
                AddFab("Добавить на выбранный день") { draft = Draft(selected, null, event = false) }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                AnimatedVisibility(
                    visible = monthPanel && mode != CalendarMode.YEAR,
                    enter = expandVertically(Motion.soft()) + fadeIn(Motion.soft()),
                    exit = shrinkVertically(Motion.softOut()) + fadeOut(Motion.softOut()),
                ) {
                    MonthPanel(selected, today, tasks) { day ->
                        goTo(day)
                        monthPanel = false
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
                            else -> fadeIn(Motion.soft()) togetherWith fadeOut(Motion.softOut())
                        }
                    },
                    label = "calendar",
                    modifier = Modifier
                        .fillMaxSize()
                        // Swipe left/right moves to the next/previous day, week, month or year.
                        .then(
                            if (mode == CalendarMode.SCHEDULE) Modifier else Modifier.pointerInput(Unit) {
                                var dragged = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { dragged = 0f },
                                    onDragEnd = { if (abs(dragged) > swipeThreshold) currentShift(if (dragged < 0) 1L else -1L) },
                                ) { _, dx -> dragged += dx }
                            }
                        ),
                ) { shown ->
                    val selectedHere = if (selected in shown.start..shown.end) selected else shown.start
                    when (shown.mode) {
                        CalendarMode.SCHEDULE -> ScheduleView(
                            tasks = tasks,
                            today = today,
                            target = selected,
                            jump = jumps,
                            range = shown.start..shown.end,
                            vm = vm,
                            onOpenTask = onOpenTask,
                            onOpenDay = { goTo(it); setMode(CalendarMode.DAY) },
                        )
                        CalendarMode.YEAR -> {
                            val entries = remember(tasks, shown) { calendarEntries(tasks, shown.start, shown.end) }
                            YearGrid(shown.start.year, today, entries) { month ->
                                selectedEpoch = (if (YearMonth.from(today) == month) today else month.atDay(1)).toEpochDay()
                                setMode(CalendarMode.MONTH)
                            }
                        }
                        CalendarMode.MONTH -> {
                            val entries = remember(tasks, shown) { calendarEntries(tasks, shown.start, shown.end) }
                            Column(Modifier.fillMaxSize()) {
                                MonthGrid(YearMonth.from(shown.start), selectedHere, today, entries, big = true) { selectedEpoch = it.toEpochDay() }
                                HorizontalDivider(Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                DayAgenda(selectedHere, today, entries[selectedHere].orEmpty(), vm, onOpenTask)
                            }
                        }
                        CalendarMode.WEEK, CalendarMode.THREE_DAYS, CalendarMode.DAY -> {
                            val entries = remember(tasks, shown) { calendarEntries(tasks, shown.start, shown.end) }
                            val days = if (shown.mode == CalendarMode.DAY) listOf(selectedHere)
                            else dayRange(shown.start, if (shown.mode == CalendarMode.WEEK) 7 else 3)
                            TimeGrid(
                                days = days,
                                today = today,
                                entries = entries,
                                vm = vm,
                                onOpenTask = onOpenTask,
                                onOpenDay = if (shown.mode == CalendarMode.DAY) null else ({ day ->
                                    goTo(day)
                                    setMode(CalendarMode.DAY)
                                }),
                                onAddAt = { day, time -> draft = Draft(day, time, event = true) },
                            )
                        }
                    }
                }
            }
        }
    }

    draft?.let { d ->
        NewEntryDialog(
            date = d.date,
            time = d.time,
            event = d.event,
            onCreate = { task, remind ->
                vm.createEntry(task, remind)
                draft = null
            },
            onDismiss = { draft = null },
        )
    }
}

@Composable
private fun ViewMenu(
    expanded: Boolean,
    mode: CalendarMode,
    events: Boolean,
    tasks: Boolean,
    onMode: (CalendarMode) -> Unit,
    onFilter: (Boolean, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded, onDismiss) {
        for (m in CalendarMode.entries) {
            DropdownMenuItem(
                text = { Text(m.label, fontWeight = if (m == mode) FontWeight.SemiBold else FontWeight.Normal) },
                leadingIcon = { Icon(m.icon, null) },
                trailingIcon = { if (m == mode) Icon(Icons.Filled.Check, "Выбрано", tint = MaterialTheme.colorScheme.primary) },
                onClick = { onMode(m) },
            )
        }
        HorizontalDivider()
        Text(
            "Показывать",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        // At least one of the two stays on (see AppSettings.setCalendarFilter).
        DropdownMenuItem(
            text = { Text("События") },
            leadingIcon = { Checkbox(checked = events, onCheckedChange = null) },
            onClick = { onFilter(!events, tasks) },
        )
        DropdownMenuItem(
            text = { Text("Задачи") },
            leadingIcon = { Checkbox(checked = tasks, onCheckedChange = null) },
            onClick = { onFilter(events, !tasks) },
        )
    }
}

/**
 * The panel under the title: a small month to pick a day from and a strip of months (with the
 * year between December and January) to page through, as in Google Calendar.
 */
@Composable
private fun MonthPanel(selected: LocalDate, today: LocalDate, tasks: List<Task>, onPick: (LocalDate) -> Unit) {
    var month by remember(selected) { mutableStateOf(YearMonth.from(selected)) }
    val entries = remember(tasks, month) { calendarEntries(tasks, month.atDay(1), month.atEndOfMonth()) }
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(scheme.surfaceContainerLow).padding(bottom = 8.dp)) {
        MonthGrid(month, selected, today, entries, big = false, onSelect = onPick)
        val months = remember(today) { (-12L..36L).map { YearMonth.from(today).plusMonths(it) } }
        val strip = rememberLazyListState()
        LaunchedEffect(Unit) { strip.scrollToItem((months.indexOf(month) - 1).coerceAtLeast(0)) }
        LazyRow(
            state = strip,
            contentPadding = PaddingValues(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(months, key = { _, m -> m.toString() }) { _, m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (m.monthValue == 1) {
                        Text("${m.year}", style = MaterialTheme.typography.titleSmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
                    }
                    val active = m == month
                    Text(
                        m.format(DateTimeFormatter.ofPattern("LLL", ru)),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) scheme.onSecondaryContainer else scheme.onSurface,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (active) scheme.secondaryContainer else scheme.surfaceContainerHigh)
                            .clickable { month = m }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * A month as a grid of day numbers with coloured dots for what is on each day (two dots and a
 * "+" for more). Today is a filled circle, the selected day a lighter one. [big] for the Month
 * view, smaller for the panel under the title.
 */
@Composable
internal fun MonthGrid(
    month: YearMonth,
    selected: LocalDate,
    today: LocalDate,
    entries: Map<LocalDate, List<CalendarEntry>>,
    big: Boolean,
    onSelect: (LocalDate) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val offset = month.atDay(1).dayOfWeek.value - 1
    val weeks = (offset + month.lengthOfMonth() + 6) / 7
    Column(Modifier.padding(horizontal = 8.dp)) {
        Row(Modifier.padding(vertical = 6.dp)) {
            for ((i, d) in WEEK_DAYS.withIndex()) {
                Text(
                    d.take(1).uppercase(),
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (month == YearMonth.from(today) && today.dayOfWeek.value - 1 == i) scheme.primary else scheme.onSurfaceVariant,
                )
            }
        }
        for (week in 0 until weeks) {
            Row {
                for (d in 0 until 7) {
                    val index = week * 7 + d - offset
                    if (index !in 0 until month.lengthOfMonth()) {
                        Spacer(Modifier.weight(1f))
                        continue
                    }
                    val day = month.atDay(index + 1)
                    val items = entries[day].orEmpty().filter { !it.task.isDone || it.task.isEvent }
                    Column(
                        Modifier
                            .weight(1f)
                            .then(if (big) Modifier.aspectRatio(1f) else Modifier.height(44.dp))
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(day) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            Modifier
                                .size(if (big) 34.dp else 30.dp)
                                .clip(CircleShape)
                                .background(
                                    when (day) {
                                        today -> scheme.primary
                                        selected -> scheme.primary.copy(alpha = 0.22f)
                                        else -> Color.Transparent
                                    }
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${day.dayOfMonth}",
                                style = if (big) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                                fontWeight = if (day == today || day == selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (day == today) scheme.onPrimary else scheme.onSurface,
                            )
                        }
                        Row(Modifier.height(8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                            for (e in items.distinctBy { it.task.id }.take(2)) {
                                Box(Modifier.size(5.dp).clip(CircleShape).background(entryColor(e.task)))
                            }
                            if (items.distinctBy { it.task.id }.size > 2) {
                                Text("+", fontSize = 9.sp, lineHeight = 9.sp, color = scheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Twelve small months; days with something on them are tinted. Tapping a month opens it. */
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
