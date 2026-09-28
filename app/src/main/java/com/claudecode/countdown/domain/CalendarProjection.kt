package com.claudecode.countdown.domain

import com.claudecode.countdown.data.db.RepeatFrom
import com.claudecode.countdown.data.db.Task
import com.claudecode.tiktak.core.RepeatRule
import java.time.LocalDate

/** A task shown on a calendar day; [projected] marks a future repeat that does not exist yet. */
data class CalendarEntry(val task: Task, val date: LocalDate, val projected: Boolean)

/**
 * Entries for [start]..[end] inclusive. Repeating open tasks are expanded into their future
 * occurrences (not for "repeat from completion", whose dates are unknown in advance).
 */
fun calendarEntries(tasks: List<Task>, start: LocalDate, end: LocalDate, maxPerTask: Int = 400): Map<LocalDate, List<CalendarEntry>> {
    val out = mutableListOf<CalendarEntry>()
    for (task in tasks) {
        val day = task.dueDay() ?: continue
        if (day in start..end) out += CalendarEntry(task, day, projected = false)
        if (task.isDone || task.repeatFrom == RepeatFrom.COMPLETION) continue
        var rule = RepeatRule.parse(task.repeatRule) ?: continue
        var current = day
        var steps = 0
        while (steps++ < maxPerTask) {
            val next = rule.nextAfter(current) ?: break
            if (next > end) break
            rule = rule.advanced()
            current = next
            if (next >= start) out += CalendarEntry(task, next, projected = true)
        }
    }
    return out.groupBy { it.date }.mapValues { (_, list) ->
        list.sortedWith(compareBy({ it.task.isDone }, { !it.task.isAllDay && it.task.dueAt != null }, { it.task.dueAt?.let { localTimeOf(it) } }, { -it.task.priority }))
    }
}
