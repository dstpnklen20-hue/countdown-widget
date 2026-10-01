package com.claudecode.countdown.domain

import com.claudecode.countdown.data.db.RepeatFrom
import com.claudecode.countdown.data.db.Task
import com.claudecode.tiktak.core.RepeatRule
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * A task shown on a calendar day; [projected] marks a future repeat that does not exist yet.
 * A timed entry covers minutes [start]..[end] (end exclusive) of that day, null for all-day ones.
 * Something running past midnight (sleep from 23:00 to 6:00) shows on every day it touches, as
 * [part] of [parts].
 */
data class CalendarEntry(
    val task: Task,
    val date: LocalDate,
    val projected: Boolean,
    val start: Int? = null,
    val end: Int? = null,
    val part: Int = 1,
    val parts: Int = 1,
) {
    val timed: Boolean get() = start != null
}

/** Entries longer than this are not spread over the days between (likely a typo in the dates). */
private const val MAX_SPAN_DAYS = 62L

/**
 * Entries for [start]..[end] inclusive. Repeating open tasks and events are expanded into their
 * future occurrences (not for "repeat from completion", whose dates are unknown in advance).
 */
fun calendarEntries(
    tasks: List<Task>,
    start: LocalDate,
    end: LocalDate,
    maxPerTask: Int = 400,
    zone: ZoneId = ZoneId.systemDefault(),
): Map<LocalDate, List<CalendarEntry>> {
    val out = mutableListOf<CalendarEntry>()
    for (task in tasks) {
        val day = task.dueDay(zone) ?: continue
        val span = spanDays(task, zone)
        val last = end.plusDays(span)
        fun add(occurrence: LocalDate, projected: Boolean) {
            for (e in occurrenceEntries(task, occurrence, projected, zone)) if (e.date in start..end) out += e
        }
        if (day <= last) add(day, projected = false)
        if ((task.isDone && !task.isEvent) || task.repeatFrom == RepeatFrom.COMPLETION) continue
        var rule = RepeatRule.parse(task.repeatRule) ?: continue
        var current = day
        var steps = 0
        while (steps++ < maxPerTask) {
            val next = rule.nextAfter(current) ?: break
            if (next > last) break
            rule = rule.advanced()
            current = next
            if (next >= start) add(next, projected = true)
        }
    }
    return out.groupBy { it.date }.mapValues { (_, list) ->
        list.sortedWith(compareBy({ it.task.isDone && !it.task.isEvent }, { it.timed }, { it.start }, { -it.task.priority }))
    }
}

/** How many days before its due day an entry begins: its start day, for something with a start. */
private fun spanDays(task: Task, zone: ZoneId): Long {
    val (from, to) = startAndEnd(task, task.dueDay(zone) ?: return 0, zone) ?: return 0
    return ChronoUnit.DAYS.between(from.toLocalDate(), to.toLocalDate()).coerceIn(0, MAX_SPAN_DAYS)
}

/**
 * Start and end of the occurrence due on [day]: from the start time when there is one (keeping
 * the original length), else a default block from the due time. Null for all-day entries
 * without a start day of their own.
 */
private fun startAndEnd(task: Task, day: LocalDate, zone: ZoneId): Pair<LocalDateTime, LocalDateTime>? {
    val dueAt = task.dueAt ?: return null
    val startAt = task.startAt?.takeIf { it <= dueAt && Duration.ofMillis(dueAt - it).toDays() <= MAX_SPAN_DAYS }
    if (task.isAllDay) {
        val first = task.copy(dueAt = startAt ?: return null).dueDay(zone) ?: return null
        val days = ChronoUnit.DAYS.between(first, task.dueDay(zone))
        return day.minusDays(days).atStartOfDay() to day.atStartOfDay()
    }
    val due = day.atTime(localTimeOf(dueAt, zone))
    if (startAt == null || startAt == dueAt) {
        // No length: a default block from the due time, cut at midnight.
        val endOfDay = day.plusDays(1).atStartOfDay()
        return due to minOf(due.plusMinutes(DEFAULT_BLOCK_MINUTES.toLong()), endOfDay)
    }
    return due.minus(Duration.ofMillis(dueAt - startAt)) to due
}

private fun occurrenceEntries(task: Task, day: LocalDate, projected: Boolean, zone: ZoneId): List<CalendarEntry> {
    val range = startAndEnd(task, day, zone)
    if (task.isAllDay || task.dueAt == null) {
        if (range == null) return listOf(CalendarEntry(task, day, projected))
        val first = range.first.toLocalDate()
        val parts = ChronoUnit.DAYS.between(first, day).toInt() + 1
        return List(parts) { i -> CalendarEntry(task, first.plusDays(i.toLong()), projected, part = i + 1, parts = parts) }
    }
    val (from, to) = range!!
    // A block that ends exactly at midnight does not touch the next day.
    val lastDay = if (to.toLocalTime() == java.time.LocalTime.MIDNIGHT && to > from) to.toLocalDate().minusDays(1) else to.toLocalDate()
    val firstDay = from.toLocalDate()
    val parts = ChronoUnit.DAYS.between(firstDay, lastDay).toInt() + 1
    return List(parts) { i ->
        val d = firstDay.plusDays(i.toLong())
        val s = if (d == firstDay) from.hour * 60 + from.minute else 0
        val e = if (d == to.toLocalDate()) to.hour * 60 + to.minute else MINUTES_PER_DAY
        CalendarEntry(task, d, projected, s, maxOf(e, s), part = i + 1, parts = parts)
    }
}
