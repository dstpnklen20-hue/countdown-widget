package com.claudecode.countdown.data

import android.content.Context
import com.claudecode.countdown.domain.ALL_DAY_REMINDER_MINUTES
import com.claudecode.countdown.domain.TaskFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Sections the user can add to the bottom bar next to the fixed Tasks, Calendar and Settings. */
enum class OptionalTab(val label: String) { MATRIX("Матрица"), FOCUS("Фокус"), HABITS("Привычки") }

/** Which list the Tasks tab opens with. */
enum class StartList(val label: String) { TODAY("Сегодня"), INBOX("Входящие"), LAST("Последний открытый") }

data class Settings(
    val tabs: Set<OptionalTab> = emptySet(),
    val startList: StartList = StartList.TODAY,
    val lastFilterKey: String = TaskFilter.Today.key,
    val allDayReminderMinutes: Int = ALL_DAY_REMINDER_MINUTES,
    val showCompleted: Boolean = true,
    // Keys of the smart lists whose explanatory hint the user has closed.
    val dismissedHints: Set<String> = emptySet(),
) {
    val startFilterKey: String
        get() = when (startList) {
            StartList.TODAY -> TaskFilter.Today.key
            StartList.INBOX -> TaskFilter.Inbox.key
            StartList.LAST -> lastFilterKey
        }
}

/** App preferences (the theme lives separately in ThemeManager, which old View screens also read). */
class AppSettings(context: Context) {
    companion object {
        private const val PREFS = "app_settings"
        /** Bottom bar room: three fixed sections plus at most this many optional ones. */
        const val MAX_OPTIONAL_TABS = 2
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> = _state.asStateFlow()
    val current: Settings get() = _state.value

    private fun load() = Settings(
        tabs = prefs.getStringSet("tabs", emptySet()).orEmpty()
            .mapNotNull { name -> OptionalTab.entries.firstOrNull { it.name == name } }.toSet(),
        startList = StartList.entries.firstOrNull { it.name == prefs.getString("startList", null) } ?: StartList.TODAY,
        lastFilterKey = prefs.getString("lastFilter", null) ?: TaskFilter.Today.key,
        allDayReminderMinutes = prefs.getInt("allDayReminderMinutes", ALL_DAY_REMINDER_MINUTES),
        showCompleted = prefs.getBoolean("showCompleted", true),
        dismissedHints = prefs.getStringSet("dismissedHints", emptySet()).orEmpty().toSet(),
    )

    private fun update(change: (Settings) -> Settings) {
        val s = change(_state.value)
        prefs.edit()
            .putStringSet("tabs", s.tabs.mapTo(HashSet()) { it.name })
            .putString("startList", s.startList.name)
            .putString("lastFilter", s.lastFilterKey)
            .putInt("allDayReminderMinutes", s.allDayReminderMinutes)
            .putBoolean("showCompleted", s.showCompleted)
            .putStringSet("dismissedHints", s.dismissedHints)
            .apply()
        _state.value = s
    }

    /** Returns false when the bar is already full. */
    fun setTab(tab: OptionalTab, shown: Boolean): Boolean {
        if (shown && tab !in current.tabs && current.tabs.size >= MAX_OPTIONAL_TABS) return false
        update { it.copy(tabs = if (shown) it.tabs + tab else it.tabs - tab) }
        return true
    }

    fun setStartList(value: StartList) = update { it.copy(startList = value) }
    fun rememberFilter(key: String) {
        if (key != current.lastFilterKey) update { it.copy(lastFilterKey = key) }
    }
    fun setAllDayReminderMinutes(value: Int) = update { it.copy(allDayReminderMinutes = value) }
    fun setShowCompleted(value: Boolean) = update { it.copy(showCompleted = value) }
    fun dismissHint(key: String) = update { it.copy(dismissedHints = it.dismissedHints + key) }
}
