package com.claudecode.countdown.data

import android.content.Context
import com.claudecode.countdown.data.db.CalendarLayer
import java.time.DayOfWeek
import com.claudecode.countdown.domain.ALL_DAY_REMINDER_MINUTES
import com.claudecode.countdown.domain.TaskFilter
import com.claudecode.countdown.domain.TaskSort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Top-level sections ("tools", as TickTick calls them) that can be pinned to the navigation panel. */
enum class Tool(val label: String) {
    TASKS("Задачи"),
    CALENDAR("Календарь"),
    MATRIX("Матрица"),
    FOCUS("Фокус"),
    HABITS("Привычки"),
    STATS("Статистика"),
    COUNTDOWNS("Отсчёты"),
    SEARCH("Поиск"),
    SETTINGS("Настройки");

    /** Tasks and Settings always stay pinned: without them there would be no way back. */
    val fixed: Boolean get() = this == TASKS || this == SETTINGS
}

/** What the panel shows: [visible] tools, and the rest behind a "More" button when they don't fit. */
data class BarLayout(val visible: List<Tool>, val more: List<Tool>)

/** With more tools than [slots], the last slot becomes "More" and holds the overflow. */
fun barLayout(tools: List<Tool>, slots: Int): BarLayout {
    val n = slots.coerceAtLeast(2)
    return if (tools.size <= n) BarLayout(tools, emptyList()) else BarLayout(tools.take(n - 1), tools.drop(n - 1))
}

/** Tasks first, Settings always present, no duplicates. */
fun normalizeTools(tools: List<Tool>): List<Tool> {
    val rest = tools.distinct().filter { it != Tool.TASKS }
    return listOf(Tool.TASKS) + rest + if (Tool.SETTINGS in rest) emptyList() else listOf(Tool.SETTINGS)
}

/** Which list the Tasks tab opens with. */
enum class StartList(val label: String) { TODAY("Сегодня"), INBOX("Входящие"), LAST("Последний открытый") }

data class Settings(
    /** Pinned tools in panel order. */
    val tools: List<Tool> = AppSettings.DEFAULT_TOOLS,
    /** How many slots the phone's bottom bar has, the "More" button included. */
    val barLimit: Int = AppSettings.DEFAULT_BAR_LIMIT,
    val startList: StartList = StartList.TODAY,
    val lastFilterKey: String = TaskFilter.Today.key,
    val allDayReminderMinutes: Int = ALL_DAY_REMINDER_MINUTES,
    val showCompleted: Boolean = true,
    // Keys of the smart lists whose explanatory hint the user has closed.
    val dismissedHints: Set<String> = emptySet(),
    /** Sort order per list, by TaskFilter.key; lists not in the map are sorted by date. */
    val sorts: Map<String, TaskSort> = emptyMap(),
    /** Picture for empty lists: an index into the gallery, or [AppSettings.EMPTY_ART_DAILY]. */
    val emptyArt: Int = AppSettings.EMPTY_ART_DAILY,
    /** The calendar view the user last chose (a CalendarMode name), and what it shows. */
    val calendarMode: String = "MONTH",
    val calendarEvents: Boolean = true,
    val calendarTasks: Boolean = true,
    // --- Calendar ---
    /** Calendars (layers) hidden on this device; their events stay in the database. */
    val hiddenCalendars: Set<String> = emptySet(),
    /** Where new events go. */
    val defaultCalendarId: String = CalendarLayer.PERSONAL_ID,
    /** First day of the week: 1 = Monday, 6 = Saturday, 7 = Sunday (ISO numbers). */
    val firstDayOfWeek: Int = 1,
    val weekNumbers: Boolean = false,
    /** Length of a new event, in minutes. */
    val eventMinutes: Int = 60,
    /** Past events are drawn faded. */
    val dimPast: Boolean = true,
    /** Saturday and Sunday in the week view. */
    val showWeekends: Boolean = true,
    /** Completed tasks stay in the calendar, crossed out. */
    val calendarDone: Boolean = true,
    /** Working hours (minutes of the day); outside them the time grid is shaded. Null: no shading. */
    val workStart: Int? = null,
    val workEnd: Int? = null,
    /** The zone the whole app works in; null follows the phone (see AppZone). */
    val appZone: String? = null,
    /** A second time zone shown as an extra hour column; null for none. */
    val secondZone: String? = null,
    /** Height of an hour on the time grid, in dp (pinch to change). */
    val hourHeight: Int = AppSettings.DEFAULT_HOUR_HEIGHT,
    /** Quiet hours (minutes of the day): reminders come without sound. Null: off. */
    val quietStart: Int? = null,
    val quietEnd: Int? = null,
    val showHolidays: Boolean = false,
    val showBirthdays: Boolean = false,
    /** Recent searches, newest first. */
    val recentSearches: List<String> = emptyList(),
) {
    val firstDay: DayOfWeek get() = DayOfWeek.of(firstDayOfWeek.coerceIn(1, 7))

    /** Whether [minuteOfDay] falls into the quiet hours (which may run over midnight). */
    fun isQuiet(minuteOfDay: Int): Boolean {
        val from = quietStart ?: return false
        val to = quietEnd ?: return false
        return if (from <= to) minuteOfDay in from until to else minuteOfDay >= from || minuteOfDay < to
    }

    val startFilterKey: String
        get() = when (startList) {
            StartList.TODAY -> TaskFilter.Today.key
            StartList.INBOX -> TaskFilter.Inbox.key
            StartList.LAST -> lastFilterKey
        }

    fun sortOf(filter: TaskFilter): TaskSort = sorts[filter.key] ?: TaskSort.DATE
}

/** App preferences (the theme lives separately in ThemeManager, which old View screens also read). */
class AppSettings(context: Context) {
    companion object {
        private const val PREFS = "app_settings"
        /** Like TickTick: four sections on the bar, the rest under "More". */
        val DEFAULT_TOOLS = listOf(Tool.TASKS, Tool.CALENDAR, Tool.MATRIX, Tool.FOCUS, Tool.HABITS, Tool.COUNTDOWNS, Tool.SETTINGS)
        const val DEFAULT_BAR_LIMIT = 5
        val BAR_LIMITS = 3..6
        const val EMPTY_ART_DAILY = -1
        const val DEFAULT_HOUR_HEIGHT = 52
        /** Pinch zoom range of an hour on the time grid, in dp. */
        val HOUR_HEIGHTS = 30..150
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())

    init {
        // Whoever reads the settings first (app, widget, alarm) puts the app in its time zone.
        AppZone.apply(_state.value.appZone)
    }
    val state: StateFlow<Settings> = _state.asStateFlow()
    val current: Settings get() = _state.value

    private fun load() = Settings(
        tools = loadTools(),
        barLimit = prefs.getInt("barLimit", DEFAULT_BAR_LIMIT).coerceIn(BAR_LIMITS),
        startList = StartList.entries.firstOrNull { it.name == prefs.getString("startList", null) } ?: StartList.TODAY,
        lastFilterKey = prefs.getString("lastFilter", null) ?: TaskFilter.Today.key,
        allDayReminderMinutes = prefs.getInt("allDayReminderMinutes", ALL_DAY_REMINDER_MINUTES),
        showCompleted = prefs.getBoolean("showCompleted", true),
        dismissedHints = prefs.getStringSet("dismissedHints", emptySet()).orEmpty().toSet(),
        sorts = prefs.getStringSet("sorts", emptySet()).orEmpty().mapNotNull { entry ->
            val key = entry.substringBeforeLast('|')
            TaskSort.entries.firstOrNull { it.name == entry.substringAfterLast('|') }?.let { key to it }
        }.toMap(),
        emptyArt = prefs.getInt("emptyArt", EMPTY_ART_DAILY),
        calendarMode = prefs.getString("calendarMode", null) ?: "MONTH",
        calendarEvents = prefs.getBoolean("calendarEvents", true),
        calendarTasks = prefs.getBoolean("calendarTasks", true),
        hiddenCalendars = prefs.getStringSet("hiddenCalendars", emptySet()).orEmpty().toSet(),
        defaultCalendarId = prefs.getString("defaultCalendarId", null) ?: CalendarLayer.PERSONAL_ID,
        firstDayOfWeek = prefs.getInt("firstDayOfWeek", 1),
        weekNumbers = prefs.getBoolean("weekNumbers", false),
        eventMinutes = prefs.getInt("eventMinutes", 60),
        dimPast = prefs.getBoolean("dimPast", true),
        showWeekends = prefs.getBoolean("showWeekends", true),
        calendarDone = prefs.getBoolean("calendarDone", true),
        workStart = prefs.getInt("workStart", -1).takeIf { it >= 0 },
        workEnd = prefs.getInt("workEnd", -1).takeIf { it >= 0 },
        secondZone = prefs.getString("secondZone", null),
        appZone = prefs.getString("appZone", null),
        hourHeight = prefs.getInt("hourHeight", DEFAULT_HOUR_HEIGHT).coerceIn(HOUR_HEIGHTS),
        quietStart = prefs.getInt("quietStart", -1).takeIf { it >= 0 },
        quietEnd = prefs.getInt("quietEnd", -1).takeIf { it >= 0 },
        showHolidays = prefs.getBoolean("showHolidays", false),
        showBirthdays = prefs.getBoolean("showBirthdays", false),
        recentSearches = prefs.getString("recentSearches", null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty(),
    )

    private fun loadTools(): List<Tool> {
        val saved = prefs.getString("tools", null)
        if (saved != null) {
            return normalizeTools(saved.split(',').mapNotNull { name -> Tool.entries.firstOrNull { it.name == name } })
        }
        // Up to 2.1 the bar was Tasks, Calendar, up to two optional sections and Settings: keep what the user chose.
        val old = prefs.getStringSet("tabs", null) ?: return DEFAULT_TOOLS
        val optional = listOf(Tool.MATRIX, Tool.FOCUS, Tool.HABITS).filter { it.name in old }
        return listOf(Tool.TASKS, Tool.CALENDAR) + optional + Tool.SETTINGS
    }

    private fun update(change: (Settings) -> Settings) {
        val s = change(_state.value)
        prefs.edit()
            .putString("tools", s.tools.joinToString(",") { it.name })
            .putInt("barLimit", s.barLimit)
            .putString("startList", s.startList.name)
            .putString("lastFilter", s.lastFilterKey)
            .putInt("allDayReminderMinutes", s.allDayReminderMinutes)
            .putBoolean("showCompleted", s.showCompleted)
            .putStringSet("dismissedHints", s.dismissedHints)
            .putStringSet("sorts", s.sorts.mapTo(HashSet()) { (key, sort) -> "$key|${sort.name}" })
            .putInt("emptyArt", s.emptyArt)
            .putString("calendarMode", s.calendarMode)
            .putBoolean("calendarEvents", s.calendarEvents)
            .putBoolean("calendarTasks", s.calendarTasks)
            .putStringSet("hiddenCalendars", s.hiddenCalendars)
            .putString("defaultCalendarId", s.defaultCalendarId)
            .putInt("firstDayOfWeek", s.firstDayOfWeek)
            .putBoolean("weekNumbers", s.weekNumbers)
            .putInt("eventMinutes", s.eventMinutes)
            .putBoolean("dimPast", s.dimPast)
            .putBoolean("showWeekends", s.showWeekends)
            .putBoolean("calendarDone", s.calendarDone)
            .putInt("workStart", s.workStart ?: -1)
            .putInt("workEnd", s.workEnd ?: -1)
            .putString("secondZone", s.secondZone)
            .putString("appZone", s.appZone)
            .putInt("hourHeight", s.hourHeight)
            .putInt("quietStart", s.quietStart ?: -1)
            .putInt("quietEnd", s.quietEnd ?: -1)
            .putBoolean("showHolidays", s.showHolidays)
            .putBoolean("showBirthdays", s.showBirthdays)
            .putString("recentSearches", s.recentSearches.joinToString("\n"))
            .apply()
        _state.value = s
    }

    fun setPinned(tool: Tool, pinned: Boolean) {
        if (tool.fixed) return
        update { it.copy(tools = normalizeTools(if (pinned) it.tools + tool else it.tools - tool)) }
    }

    /** Moves a pinned tool one place up ([delta] = -1) or down (+1); Tasks stays first. */
    fun moveTool(tool: Tool, delta: Int) = update {
        val list = it.tools.toMutableList()
        val from = list.indexOf(tool)
        val to = from + delta
        if (from <= 0 || to <= 0 || to >= list.size) return@update it
        list.add(to, list.removeAt(from))
        it.copy(tools = list)
    }

    fun setBarLimit(value: Int) = update { it.copy(barLimit = value.coerceIn(BAR_LIMITS)) }
    fun setStartList(value: StartList) = update { it.copy(startList = value) }
    fun rememberFilter(key: String) {
        if (key != current.lastFilterKey) update { it.copy(lastFilterKey = key) }
    }
    fun setAllDayReminderMinutes(value: Int) = update { it.copy(allDayReminderMinutes = value) }
    fun setShowCompleted(value: Boolean) = update { it.copy(showCompleted = value) }
    fun dismissHint(key: String) = update { it.copy(dismissedHints = it.dismissedHints + key) }
    fun setSort(filter: TaskFilter, sort: TaskSort) = update {
        it.copy(sorts = if (sort == TaskSort.DATE) it.sorts - filter.key else it.sorts + (filter.key to sort))
    }
    fun setEmptyArt(value: Int) = update { it.copy(emptyArt = value) }
    fun setCalendarMode(name: String) = update { it.copy(calendarMode = name) }
    /** Events and tasks can be hidden one at a time, never both. */
    fun setCalendarFilter(events: Boolean, tasks: Boolean) {
        if (events || tasks) update { it.copy(calendarEvents = events, calendarTasks = tasks) }
    }

    fun setCalendarShown(id: String, shown: Boolean) = update {
        it.copy(hiddenCalendars = if (shown) it.hiddenCalendars - id else it.hiddenCalendars + id)
    }
    fun setDefaultCalendar(id: String) = update { it.copy(defaultCalendarId = id) }
    fun setFirstDayOfWeek(value: Int) = update { it.copy(firstDayOfWeek = value.coerceIn(1, 7)) }
    fun setWeekNumbers(value: Boolean) = update { it.copy(weekNumbers = value) }
    fun setEventMinutes(value: Int) = update { it.copy(eventMinutes = value) }
    fun setDimPast(value: Boolean) = update { it.copy(dimPast = value) }
    fun setShowWeekends(value: Boolean) = update { it.copy(showWeekends = value) }
    fun setCalendarDone(value: Boolean) = update { it.copy(calendarDone = value) }
    fun setWorkHours(start: Int?, end: Int?) = update { it.copy(workStart = start, workEnd = end) }
    fun setSecondZone(id: String?) = update { it.copy(secondZone = id) }
    fun setAppZone(id: String?) {
        // First the zone, then the setting: whoever reacts to the setting must already see the new zone.
        AppZone.apply(id)
        update { it.copy(appZone = id) }
    }
    fun setHourHeight(value: Int) {
        val v = value.coerceIn(HOUR_HEIGHTS)
        if (v != current.hourHeight) update { it.copy(hourHeight = v) }
    }
    fun setQuietHours(start: Int?, end: Int?) = update { it.copy(quietStart = start, quietEnd = end) }
    fun setShowHolidays(value: Boolean) = update { it.copy(showHolidays = value) }
    fun setShowBirthdays(value: Boolean) = update { it.copy(showBirthdays = value) }
    fun addRecentSearch(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        update { s -> s.copy(recentSearches = (listOf(q) + s.recentSearches.filter { !it.equals(q, ignoreCase = true) }).take(8)) }
    }
    fun clearRecentSearches() = update { it.copy(recentSearches = emptyList()) }
}
