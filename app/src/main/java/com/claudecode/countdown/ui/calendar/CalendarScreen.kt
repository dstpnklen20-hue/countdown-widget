package com.claudecode.countdown.ui.calendar

import androidx.compose.material.icons.outlined.Inbox
import com.claudecode.countdown.domain.dueDay
import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CalendarViewDay
import androidx.compose.material.icons.outlined.CalendarViewWeek
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.claudecode.countdown.data.ContactBirthdays
import com.claudecode.countdown.data.db.CalendarLayer
import com.claudecode.countdown.data.db.DisplayMode
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.data.db.TaskList
import com.claudecode.countdown.data.Ics
import com.claudecode.countdown.domain.Birthday
import com.claudecode.countdown.domain.CalendarEntry
import com.claudecode.countdown.domain.SeriesScope
import com.claudecode.countdown.domain.allDayDue
import com.claudecode.countdown.domain.atOccurrence
import com.claudecode.countdown.domain.birthdayEvents
import com.claudecode.countdown.domain.calendarEntries
import com.claudecode.countdown.domain.dayRange
import com.claudecode.countdown.domain.isVirtual
import com.claudecode.countdown.domain.russianHolidays
import com.claudecode.countdown.domain.shifted
import com.claudecode.countdown.domain.timedDue
import com.claudecode.countdown.ui.AddFab
import com.claudecode.countdown.ui.AppSnackbarHost
import com.claudecode.countdown.ui.CalendarDayIcon
import com.claudecode.countdown.ui.Motion
import com.claudecode.countdown.ui.formatEventSpan
import com.claudecode.countdown.ui.priorityColor
import com.claudecode.countdown.ui.rememberNow
import com.claudecode.countdown.ui.tasks.Snapshot
import com.claudecode.countdown.ui.tasks.TasksViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
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
private data class CalendarPage(val mode: CalendarMode, val start: LocalDate, val end: LocalDate, val month: YearMonth? = null) {
    companion object {
        fun of(mode: CalendarMode, selected: LocalDate, today: LocalDate, first: DayOfWeek): CalendarPage = when (mode) {
            // The schedule is one long list around today; the selected day only sets where it scrolls.
            CalendarMode.SCHEDULE -> CalendarPage(mode, today.minusDays(SCHEDULE_DAYS_BACK), today.plusDays(SCHEDULE_DAYS_AHEAD))
            CalendarMode.MONTH -> {
                // The grid shows whole weeks, so it reaches into the months around.
                val month = YearMonth.from(selected)
                val weeks = monthWeeks(month, first)
                CalendarPage(mode, weeks.first(), weeks.last().plusDays(6), month)
            }
            CalendarMode.WEEK -> {
                val start = weekStart(selected, first)
                CalendarPage(mode, start, start.plusDays(6))
            }
            CalendarMode.THREE_DAYS -> CalendarPage(mode, selected, selected.plusDays(2))
            CalendarMode.DAY -> CalendarPage(mode, selected, selected)
            CalendarMode.YEAR -> CalendarPage(mode, selected.withDayOfYear(1), selected.withDayOfYear(selected.lengthOfYear()))
        }
    }
}

internal const val SCHEDULE_DAYS_BACK = 366L
internal const val SCHEDULE_DAYS_AHEAD = 400L

/** Resolves a task's colour (own, else its calendar's or list's); provided by [CalendarScreen]. */
private val LocalColorOf = staticCompositionLocalOf<(Task) -> Int?> { { it.color } }

/** Ids of overdue tasks shown on today (their own day has passed); provided by [CalendarScreen]. */
internal val LocalOverdue = staticCompositionLocalOf<Set<String>> { emptySet() }

/** Whether past events are drawn faded (a setting); provided by [CalendarScreen]. */
internal val LocalDimPast = staticCompositionLocalOf { true }

/** Events and countdowns are things that happen; the rest are tasks to do. */
internal val Task.happens: Boolean get() = isEvent || displayMode == DisplayMode.COUNTDOWN

/** The task/calendar/list colour; events without one take the accent, tasks their priority's colour. */
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

/** A block dragged to a new time, waiting for the user to say which occurrences it moves. */
private data class PendingShift(val entry: CalendarEntry, val moved: Task)

/** What the calendar asks of the screens around it. */
class CalendarNav(
    val openTask: (String) -> Unit,
    val openEditor: (EventDraft) -> Unit,
    val openCalendars: () -> Unit,
    val openSettings: () -> Unit,
    val openSearch: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(vm: TasksViewModel, snapshot: Snapshot, nav: CalendarNav) {
    val today = snapshot.today
    val context = LocalContext.current
    val container = context.container
    val repo = container.tasks
    val appSettings = container.settings
    val scope = rememberCoroutineScope()
    val settings by appSettings.state.collectAsStateWithLifecycle()
    val calendars by repo.observeCalendars().collectAsStateWithLifecycle(emptyList())
    val first = settings.firstDay
    val mode = CalendarMode.entries.firstOrNull { it.name == settings.calendarMode } ?: CalendarMode.MONTH
    var selectedEpoch by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    val selected = LocalDate.ofEpochDay(selectedEpoch)
    var monthPanel by rememberSaveable { mutableStateOf(false) }
    var undatedPanel by rememberSaveable { mutableStateOf(false) }
    var viewMenu by remember { mutableStateOf(false) }
    var quick by remember { mutableStateOf<QuickDraft?>(null) }
    var sheet by remember { mutableStateOf<CalendarEntry?>(null) }
    var pendingShift by remember { mutableStateOf<PendingShift?>(null) }
    var virtualInfo by remember { mutableStateOf<CalendarEntry?>(null) }
    // Which way the last move went, so the new period slides in from the matching side.
    var direction by remember { mutableStateOf(0) }
    // Counts "go to" requests, so the schedule scrolls back even when the day stays the same.
    var jumps by remember { mutableStateOf(0) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val now by rememberNow(60_000)

    // Holidays and birthdays are shown, not stored.
    val birthdays by produceState(emptyList<Birthday>(), settings.showBirthdays) {
        value = if (settings.showBirthdays) withContext(Dispatchers.IO) { ContactBirthdays.load(context) } else emptyList()
    }
    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        appSettings.setShowBirthdays(granted)
    }

    // Which calendars and kinds are shown (the side menu).
    val calendarColors = remember(calendars) { calendars.associate { it.id to it.color } }
    // Open tasks whose day has passed: shown on today, marked overdue.
    val overdueIds = remember(snapshot.tasks, today) {
        snapshot.tasks.filter { !it.happens && !it.isDone && it.dueDay()?.let { d -> d < today } == true }.mapTo(HashSet()) { it.id }
    }
    val tasks = remember(snapshot.tasks, settings, birthdays, today.year, overdueIds) {
        val stored = snapshot.tasks.filter { t ->
            when {
                t.isEvent -> settings.calendarEvents && (t.calendarId ?: CalendarLayer.PERSONAL_ID) !in settings.hiddenCalendars
                t.happens -> settings.calendarEvents
                else -> settings.calendarTasks && (settings.calendarDone || !t.isDone)
            }
        }.map { t ->
            // Open tasks left behind move to today (on top of it, marked overdue), as in Google Calendar.
            if (t.id in overdueIds) {
                val due = allDayDue(today)
                t.copy(dueAt = due.at, startAt = null, isAllDay = true, timeZone = due.timeZone, repeatRule = null)
            } else t
        }
        val year = today.year - 2
        stored +
            (if (settings.showHolidays) russianHolidays(year) else emptyList()) +
            (if (settings.showBirthdays) birthdayEvents(birthdays, year) else emptyList())
    }
    val page = CalendarPage.of(mode, selected, today, first)

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

    fun openEntry(e: CalendarEntry) {
        val t = e.task
        when {
            t.isVirtual -> virtualInfo = e
            t.isEvent -> sheet = e
            else -> nav.openTask(t.id)
        }
    }

    /** A new entry where the user tapped: the draft moves there if one is open already. */
    fun addAt(day: LocalDate, time: LocalTime?) {
        val q = quick
        quick = if (q != null && time != null) q.copy(picked = q.time.withStart(day, time), pickedTimed = true)
        else QuickDraft.at(day, time, settings.eventMinutes, event = true)
    }

    fun newEventCalendar(): String? = settings.defaultCalendarId.takeIf { id -> id != CalendarLayer.PERSONAL_ID && calendars.any { it.id == id } }

    fun saveQuick() {
        val q = quick ?: return
        if (q.title.isBlank()) return
        quick = null
        scope.launch {
            if (q.event) {
                val calendarId = newEventCalendar()
                val task = q.time.applyTo(Task(title = q.cleanTitle, isEvent = true, calendarId = calendarId, repeatRule = q.repeat))
                repo.saveEvent(task, repo.defaultReminders(calendarId, q.time.allDay))
            } else {
                val due = if (q.timed) timedDue(q.time.startDate, q.time.start) else allDayDue(q.time.startDate)
                val created = repo.create(Task(title = q.cleanTitle, listId = TaskList.INBOX_ID, repeatRule = q.repeat, dueAt = due.at, isAllDay = due.isAllDay, timeZone = due.timeZone))
                if (q.timed) repo.addReminder(created.id, 0)
            }
        }
    }

    fun moreOptions() {
        val q = quick ?: return
        quick = null
        scope.launch {
            if (q.event) {
                val calendarId = newEventCalendar()
                val task = q.time.applyTo(Task(title = q.cleanTitle, isEvent = true, calendarId = calendarId, repeatRule = q.repeat))
                nav.openEditor(EventDraft(task, repo.defaultReminders(calendarId, q.time.allDay)))
            } else {
                val due = if (q.timed) timedDue(q.time.startDate, q.time.start) else allDayDue(q.time.startDate)
                val created = repo.create(Task(title = q.cleanTitle.ifEmpty { "Новая задача" }, repeatRule = q.repeat, dueAt = due.at, isAllDay = due.isAllDay, timeZone = due.timeZone))
                nav.openTask(created.id)
            }
        }
    }

    /** Open tasks without a date, for the strip above the time grid. */
    val undated = remember(snapshot.tasks) { snapshot.tasks.filter { !it.happens && !it.isDone && it.dueAt == null } }

    /** A task dropped on the time grid gets that time and the default length, with a reminder. */
    fun scheduleTask(id: String, day: LocalDate, minute: Int) {
        scope.launch {
            val before = repo.get(id) ?: return@launch
            val start = timedDue(day, LocalTime.of(minute / 60, minute % 60))
            repo.update(before.copy(startAt = start.at, dueAt = start.at + settings.eventMinutes * 60_000L, isAllDay = false, timeZone = start.timeZone))
            if (repo.remindersOf(id).isEmpty()) repo.addReminder(id, 0)
            container.undo.offer("«${before.title}» — %02d:%02d".format(minute / 60, minute % 60)) { repo.update(before) }
        }
    }

    fun applyShift(entry: CalendarEntry, moved: Task, scopeChoice: SeriesScope) {
        val before = entry.task
        scope.launch {
            if (before.isEvent && before.repeatRule != null) {
                repo.saveEvent(moved, repo.remindersOf(before.id), before, entry.occurrence, scopeChoice)
            } else {
                repo.update(moved)
                container.undo.offer(if (before.isEvent) "Событие перенесено" else "Задача перенесена") { repo.update(before) }
            }
        }
    }

    val shownMonth = page.month ?: YearMonth.from(if (mode == CalendarMode.WEEK || mode == CalendarMode.THREE_DAYS) page.start else selected)
    val title = when (mode) {
        CalendarMode.YEAR -> "${selected.year}"
        else -> shownMonth.format(DateTimeFormatter.ofPattern("LLLL", ru)).replaceFirstChar { it.uppercase() } +
            if (shownMonth.year != today.year) " ${shownMonth.year}" else ""
    }

    // A draft moved to another day (typed "завтра", picked a date): follow it, so its block stays in view.
    val draftDay = quick?.time?.startDate
    LaunchedEffect(draftDay) {
        val day = draftDay ?: return@LaunchedEffect
        if (mode in setOf(CalendarMode.DAY, CalendarMode.THREE_DAYS, CalendarMode.WEEK) && day !in page.start..page.end) goTo(day)
    }

    // A widget asked for a day: show it in the Day view.
    val jump by container.calendarJump.collectAsStateWithLifecycle()
    LaunchedEffect(jump) {
        val day = jump ?: return@LaunchedEffect
        goTo(day)
        setMode(CalendarMode.DAY)
        container.calendarJump.value = null
    }

    BackHandler(enabled = quick != null) { quick = null }
    BackHandler(enabled = monthPanel && quick == null) { monthPanel = false }
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val count = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                    Ics.import(repo, text, newEventCalendar())
                }.getOrElse { -1 }
            }
            android.widget.Toast.makeText(
                context,
                if (count >= 0) "Импортировано событий: $count" else "Не удалось прочитать файл .ics",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val text = Ics.export(repo, snapshot.tasks.filter { it.dueAt != null })
                    context.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
                }.isSuccess
            }
            android.widget.Toast.makeText(context, if (ok) "Календарь сохранён" else "Не удалось сохранить файл", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    val colorOf: (Task) -> Int? = remember(calendarColors, snapshot) {
        { t -> t.color ?: if (t.isEvent) calendarColors[t.calendarId ?: CalendarLayer.PERSONAL_ID] else snapshot.colorOf(t) }
    }

    CompositionLocalProvider(LocalColorOf provides colorOf, LocalOverdue provides overdueIds, LocalDimPast provides settings.dimPast) {
        ModalNavigationDrawer(
            drawerState = drawer,
            // Swipes belong to the pages; the menu opens with its button and closes with a swipe.
            gesturesEnabled = drawer.isOpen,
            drawerContent = {
                CalendarDrawer(
                    mode, settings, calendars,
                    DrawerActions(
                        onMode = { setMode(it); scope.launch { drawer.close() } },
                        onCalendarShown = appSettings::setCalendarShown,
                        onTasksShown = { appSettings.setCalendarFilter(settings.calendarEvents || !it, it) },
                        onHolidays = appSettings::setShowHolidays,
                        onBirthdays = { on ->
                            if (on && !ContactBirthdays.allowed(context)) contactsPermission.launch(Manifest.permission.READ_CONTACTS)
                            else appSettings.setShowBirthdays(on)
                        },
                        onSearch = { scope.launch { drawer.close() }; nav.openSearch() },
                        onManage = { scope.launch { drawer.close() }; nav.openCalendars() },
                        onImport = { importer.launch(arrayOf("text/calendar", "text/x-vcalendar", "application/octet-stream", "*/*")) },
                        onExport = { exporter.launch("tiktak-$today.ics") },
                        onSettings = { scope.launch { drawer.close() }; nav.openSettings() },
                    ),
                )
            },
        ) {
            Scaffold(
                snackbarHost = { AppSnackbarHost() },
                topBar = {
                    TopAppBar(
                        navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "Меню календаря") } },
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
                            IconButton(onClick = nav.openSearch) { Icon(Icons.Outlined.Search, "Поиск") }
                            if (mode == CalendarMode.DAY || mode == CalendarMode.THREE_DAYS || mode == CalendarMode.WEEK) {
                                IconButton(onClick = { undatedPanel = !undatedPanel }) {
                                    Icon(Icons.Outlined.Inbox, "Задачи без даты", tint = if (undatedPanel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            IconButton(onClick = { goTo(today); monthPanel = false }) { CalendarDayIcon(today.dayOfMonth, "К сегодня") }
                            Box {
                                IconButton(onClick = { viewMenu = true }) { Icon(mode.icon, "Вид: ${mode.label}") }
                                ViewMenu(viewMenu, mode, onMode = { setMode(it); viewMenu = false }, onDismiss = { viewMenu = false })
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    )
                },
                floatingActionButton = {
                    if (quick == null) AddFab("Добавить") {
                        val time = if (selected == today) LocalTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0) else LocalTime.of(9, 0)
                        addAt(selected, time)
                    }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    Column(Modifier.fillMaxSize()) {
                        AnimatedVisibility(
                            visible = monthPanel && mode != CalendarMode.YEAR,
                            enter = expandVertically(Motion.soft()) + fadeIn(Motion.soft()),
                            exit = shrinkVertically(Motion.softOut()) + fadeOut(Motion.softOut()),
                        ) {
                            MonthPanel(selected, today, tasks, first) { day ->
                                goTo(day)
                                monthPanel = false
                            }
                        }
                        val gridMode = mode == CalendarMode.DAY || mode == CalendarMode.THREE_DAYS || mode == CalendarMode.WEEK
                        AnimatedVisibility(visible = undatedPanel && gridMode) {
                            UndatedTasks(undated, nav.openTask)
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
                                    if (mode == CalendarMode.SCHEDULE) Modifier else Modifier.pointerInput(mode) {
                                        var dragged = 0f
                                        detectHorizontalDragGestures(
                                            onDragStart = { dragged = 0f },
                                            onDragEnd = { if (abs(dragged) > swipeThreshold) currentShift(if (dragged < 0) 1L else -1L) },
                                        ) { _, dx -> dragged += dx }
                                    }
                                ),
                        ) { shownPage ->
                            val selectedHere = if (selected in shownPage.start..shownPage.end) selected else shownPage.start
                            when (shownPage.mode) {
                                CalendarMode.SCHEDULE -> ScheduleView(
                                    tasks = tasks,
                                    today = today,
                                    target = selected,
                                    jump = jumps,
                                    range = shownPage.start..shownPage.end,
                                    vm = vm,
                                    onOpen = ::openEntry,
                                    onOpenDay = { goTo(it); setMode(CalendarMode.DAY) },
                                )
                                CalendarMode.YEAR -> {
                                    val entries = remember(tasks, shownPage) { calendarEntries(tasks, shownPage.start, shownPage.end) }
                                    YearGrid(shownPage.start.year, today, entries, first) { month ->
                                        selectedEpoch = (if (YearMonth.from(today) == month) today else month.atDay(1)).toEpochDay()
                                        setMode(CalendarMode.MONTH)
                                    }
                                }
                                CalendarMode.MONTH -> {
                                    val entries = remember(tasks, shownPage) { calendarEntries(tasks, shownPage.start, shownPage.end) }
                                    MonthChips(
                                        month = shownPage.month ?: YearMonth.from(selectedHere),
                                        today = today,
                                        entries = entries,
                                        first = first,
                                        weekNumbers = settings.weekNumbers,
                                        dimPast = settings.dimPast,
                                        onOpenDay = { day -> goTo(day); setMode(CalendarMode.DAY) },
                                    )
                                }
                                CalendarMode.WEEK, CalendarMode.THREE_DAYS, CalendarMode.DAY -> {
                                    val entries = remember(tasks, shownPage) { calendarEntries(tasks, shownPage.start, shownPage.end) }
                                    val days = when (shownPage.mode) {
                                        CalendarMode.DAY -> listOf(selectedHere)
                                        CalendarMode.WEEK -> dayRange(shownPage.start, 7).filter {
                                            settings.showWeekends || (it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY)
                                        }
                                        else -> dayRange(shownPage.start, 3)
                                    }
                                    TimeGrid(
                                        days = days,
                                        today = today,
                                        entries = entries,
                                        draft = quick,
                                        settings = settings,
                                        bottomInset = if (quick != null) 300.dp else 0.dp,
                                        actions = GridActions(
                                            onOpen = ::openEntry,
                                            onOpenDay = if (shownPage.mode == CalendarMode.DAY) null else ({ day ->
                                                goTo(day)
                                                setMode(CalendarMode.DAY)
                                            }),
                                            onAddAt = { day, time -> addAt(day, time) },
                                            onShift = { entry, s, e ->
                                                val moved = entry.task.shifted(entry.occurrence, s, e)
                                                if (entry.task.isEvent && entry.task.repeatRule != null) pendingShift = PendingShift(entry, moved)
                                                else applyShift(entry, moved, SeriesScope.ALL)
                                            },
                                            onToggleDone = vm::toggleDone,
                                            onZoom = appSettings::setHourHeight,
                                            onDropTask = ::scheduleTask,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                    // The quick card covers only the bottom: the new block stays in sight above it.
                    AnimatedVisibility(
                        visible = quick != null,
                        enter = slideInVertically(Motion.soft()) { it } + fadeIn(Motion.soft()),
                        exit = slideOutVertically(Motion.softOut()) { it } + fadeOut(Motion.softOut()),
                        modifier = Modifier.align(Alignment.BottomCenter),
                    ) {
                        val q = quick
                        if (q != null) QuickCreateCard(q, { quick = it }, ::saveQuick, ::moreOptions, { quick = null })
                    }
                }
            }
        }
    }

    sheet?.let { e ->
        val t = snapshot.tasks.firstOrNull { it.id == e.task.id } ?: e.task
        EventDetailsSheet(
            task = t,
            occurrence = e.occurrence,
            calendars = calendars,
            allDayMinutes = settings.allDayReminderMinutes,
            onEdit = {
                sheet = null
                scope.launch { nav.openEditor(EventDraft(t.atOccurrence(e.occurrence), repo.remindersOf(t.id), t, e.occurrence)) }
            },
            onDuplicate = {
                sheet = null
                scope.launch {
                    val copy = repo.duplicate(t, e.occurrence)
                    container.undo.offer("Создана копия события") { repo.delete(copy.id) }
                }
            },
            onColor = { c -> scope.launch { repo.update(t.copy(color = c)) } },
            onDelete = { s ->
                sheet = null
                scope.launch {
                    val undo = repo.deleteEvent(t, e.occurrence, s)
                    container.undo.offer("Событие удалено") { undo() }
                }
            },
            onDismiss = { sheet = null },
        )
    }
    pendingShift?.let { p ->
        ScopeDialog(
            title = "Перенести повторяющееся событие",
            scopes = SeriesScope.entries,
            onPick = { s -> pendingShift = null; applyShift(p.entry, p.moved, s) },
            onDismiss = { pendingShift = null },
        )
    }
    virtualInfo?.let { e ->
        AlertDialog(
            onDismissRequest = { virtualInfo = null },
            title = { Text(e.task.title) },
            text = {
                Text(
                    listOfNotNull(
                        formatEventSpan(e.task.atOccurrence(e.occurrence), today)?.replaceFirstChar { it.uppercase() },
                        e.task.content.takeIf { it.isNotBlank() },
                    ).joinToString("\n")
                )
            },
            confirmButton = { TextButton(onClick = { virtualInfo = null }) { Text("Закрыть") } },
        )
    }
}

@Composable
private fun ViewMenu(expanded: Boolean, mode: CalendarMode, onMode: (CalendarMode) -> Unit, onDismiss: () -> Unit) {
    DropdownMenu(expanded, onDismiss) {
        for (m in CalendarMode.entries) {
            DropdownMenuItem(
                text = { Text(m.label, fontWeight = if (m == mode) FontWeight.SemiBold else FontWeight.Normal) },
                leadingIcon = { Icon(m.icon, null) },
                trailingIcon = { if (m == mode) Icon(Icons.Filled.Check, "Выбрано", tint = MaterialTheme.colorScheme.primary) },
                onClick = { onMode(m) },
            )
        }
    }
}

/**
 * The panel under the title: a small month to pick a day from and a strip of months (with the
 * year between December and January) to page through, as in Google Calendar.
 */
@Composable
private fun MonthPanel(selected: LocalDate, today: LocalDate, tasks: List<Task>, first: DayOfWeek, onPick: (LocalDate) -> Unit) {
    var month by remember(selected) { mutableStateOf(YearMonth.from(selected)) }
    val entries = remember(tasks, month) { calendarEntries(tasks, month.atDay(1), month.atEndOfMonth()) }
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(scheme.surfaceContainerLow).padding(bottom = 8.dp)) {
        MonthGrid(month, selected, today, entries, first, onSelect = onPick)
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
 * A small month as a grid of day numbers with coloured dots for what is on each day (two dots
 * and a "+" for more). Today is a filled circle, the selected day a lighter one.
 */
@Composable
internal fun MonthGrid(
    month: YearMonth,
    selected: LocalDate,
    today: LocalDate,
    entries: Map<LocalDate, List<CalendarEntry>>,
    first: DayOfWeek,
    onSelect: (LocalDate) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val offset = ((month.atDay(1).dayOfWeek.value - first.value) + 7) % 7
    val weeks = (offset + month.lengthOfMonth() + 6) / 7
    Column(Modifier.padding(horizontal = 8.dp)) {
        Row(Modifier.padding(vertical = 6.dp)) {
            for (d in weekDays(first)) {
                Text(
                    WEEK_DAYS[d.value - 1].take(1).uppercase(),
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (month == YearMonth.from(today) && today.dayOfWeek == d) scheme.primary else scheme.onSurfaceVariant,
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
                        Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).clickable { onSelect(day) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            Modifier
                                .size(30.dp)
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
                                style = MaterialTheme.typography.bodyMedium,
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
private fun YearGrid(year: Int, today: LocalDate, entries: Map<LocalDate, List<CalendarEntry>>, first: DayOfWeek, onOpenMonth: (YearMonth) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp).padding(bottom = 88.dp),
    ) {
        for (row in (1..12).chunked(3)) {
            Row(Modifier.fillMaxWidth()) {
                for (m in row) {
                    val month = YearMonth.of(year, m)
                    val offset = ((month.atDay(1).dayOfWeek.value - first.value) + 7) % 7
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
