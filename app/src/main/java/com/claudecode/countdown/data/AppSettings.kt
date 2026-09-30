package com.claudecode.countdown.data

import android.content.Context
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
) {
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
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
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
}
