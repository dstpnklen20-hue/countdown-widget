package com.claudecode.countdown.domain

import com.claudecode.countdown.data.db.CalendarLayer
import com.claudecode.countdown.data.db.DisplayMode
import com.claudecode.countdown.data.db.Task
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

enum class SearchKind(val label: String) { ALL("Всё"), EVENTS("События"), TASKS("Задачи") }

enum class SearchPeriod(val label: String) { ANY("Любые даты"), FUTURE("Впереди"), PAST("Прошедшие"), MONTH("Этот месяц"), YEAR("Этот год") }

/**
 * A calendar search: every word of [text] in the title, notes or place; none of the words of
 * [without]; only [kind], only the [calendars] given (null: all) and the [period].
 */
data class CalendarQuery(
    val text: String,
    val without: String = "",
    val kind: SearchKind = SearchKind.ALL,
    val calendars: Set<String>? = null,
    val period: SearchPeriod = SearchPeriod.ANY,
)

private fun words(s: String) = s.lowercase().split(' ', '\t', '\n', ',').filter { it.isNotBlank() }

/** Dated entries matching [q], in date order (dateless tasks last). */
fun searchCalendar(tasks: List<Task>, q: CalendarQuery, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<Task> {
    val want = words(q.text)
    val not = words(q.without)
    if (want.isEmpty()) return emptyList()
    return tasks.filter { t ->
        val text = listOf(t.title, t.content, t.location.orEmpty()).joinToString("\n").lowercase()
        val event = t.isEvent || t.displayMode == DisplayMode.COUNTDOWN
        val day = t.dueDay(zone)
        want.all { it in text } && not.none { it in text } &&
            when (q.kind) {
                SearchKind.ALL -> true
                SearchKind.EVENTS -> event
                SearchKind.TASKS -> !event
            } &&
            (q.calendars == null || !t.isEvent || (t.calendarId ?: CalendarLayer.PERSONAL_ID) in q.calendars) &&
            when (q.period) {
                SearchPeriod.ANY -> true
                // A repeating entry still has days ahead even when its first one is past.
                SearchPeriod.FUTURE -> day != null && (day >= today || t.repeatRule != null)
                SearchPeriod.PAST -> day != null && day < today
                SearchPeriod.MONTH -> day != null && YearMonth.from(day) == YearMonth.from(today)
                SearchPeriod.YEAR -> day != null && day.year == today.year
            }
    }.sortedWith(compareBy<Task>({ it.dueAt == null }, { it.dueAt }))
}

/**
 * Tasks whose title or notes contain every word of [query], ignoring case. Title matches come
 * before notes-only matches, open tasks before completed ones.
 */
fun searchTasks(tasks: List<Task>, query: String): List<Task> {
    val words = query.lowercase().split(' ', '\t', '\n').filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()
    return tasks
        .filter { t ->
            val text = (t.title + "\n" + t.content).lowercase()
            words.all { it in text }
        }
        .sortedWith(
            compareBy<Task>({ it.isDone }, { t -> !words.all { it in t.title.lowercase() } }, { -it.updatedAt })
        )
}
