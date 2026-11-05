package com.claudecode.countdown.domain

import com.claudecode.countdown.data.db.DisplayMode
import com.claudecode.countdown.data.db.RepeatFrom
import com.claudecode.countdown.data.db.Task
import com.claudecode.tiktak.core.RepeatRule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * An entry as it goes to a calendar of the phone (and from there to Google Calendar and the
 * watch): the columns of android.provider.CalendarContract.Events, worked out without Android so
 * they can be tested. Times are epoch ms; an all-day entry runs from UTC midnight of its first day
 * to UTC midnight after its last, as the calendar provider wants.
 */
data class ExportedEvent(
    /** Id of the task it comes from. */
    val key: String,
    val title: String,
    val description: String,
    val location: String?,
    val start: Long,
    /** End for a single entry; null for a repeating one, which has [duration] instead. */
    val end: Long?,
    /** RFC 5545 duration of each occurrence ("P3600S", "P2D"), for repeating entries. */
    val duration: String?,
    val allDay: Boolean,
    val timeZone: String,
    val rrule: String?,
    /** Skipped occurrences, as the provider's EXDATE value. */
    val exDate: String?,
) {
    /** Changes when anything that goes to the calendar changes, to skip writing what is already there. */
    val fingerprint: String get() = listOf(title, description, location, start, end, duration, allDay, timeZone, rrule, exDate).joinToString("\u0001").hashCode().toString(16)
}

private const val MINUTE = 60_000L
private val UTC_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
/** Entries over before this long ago are not sent: the watch and Google need the days ahead. */
private const val KEEP_PAST_DAYS = 31L

/**
 * Entries to put into the phone's calendar: events (all of them) and open tasks with a date
 * (subtasks too; countdowns as all-day events). Repeats go as RRULE with their skipped days, so the
 * calendar shows every occurrence; holidays and birthdays are not stored and stay out.
 */
fun exportedEvents(tasks: List<Task>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<ExportedEvent> {
    val since = now - KEEP_PAST_DAYS * 86_400_000L
    return tasks.mapNotNull { t ->
        if (t.deleted || t.dueAt == null) return@mapNotNull null
        if (!t.isEvent && t.isDone) return@mapNotNull null
        exported(t, zone)?.takeIf { e -> e.rrule != null && !ruleEnded(t, since, zone) || (e.end ?: e.start) >= since }
    }
}

private fun ruleEnded(t: Task, since: Long, zone: ZoneId): Boolean {
    val until = RepeatRule.parse(t.repeatRule)?.until ?: return false
    return until.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() < since
}

private fun exported(t: Task, zone: ZoneId): ExportedEvent? {
    val due = t.dueAt ?: return null
    val countdown = t.displayMode == DisplayMode.COUNTDOWN
    val allDay = t.isAllDay || (countdown && !t.isEvent)
    // Tasks repeating from completion have no dates ahead to show.
    val rule = RepeatRule.parse(t.repeatRule)?.takeIf { t.isEvent || t.repeatFrom == RepeatFrom.DUE }
    val description = t.content.trim()
    val title = t.title.ifBlank { "(Без названия)" }

    if (allDay) {
        val lastDay = t.dueDay(zone) ?: return null
        val firstDay = t.startAt?.takeIf { t.isEvent && it <= due }?.let { start ->
            Instant.ofEpochMilli(start).atZone(t.allDayZone(zone)).toLocalDate()
        }?.takeIf { !it.isAfter(lastDay) } ?: lastDay
        val days = ChronoUnit.DAYS.between(firstDay, lastDay) + 1
        val start = firstDay.utcMidnight()
        return ExportedEvent(
            key = t.id, title = title, description = description, location = t.location,
            start = start,
            end = if (rule == null) lastDay.plusDays(1).utcMidnight() else null,
            duration = if (rule != null) "P${days}D" else null,
            allDay = true, timeZone = "UTC",
            rrule = rule?.let { providerRule(it, null, zone) },
            exDate = t.exDates(firstDay, lastDay, null, zone),
        )
    }

    // A task with only a time takes a block on the grid, as in Tik Tak's own calendar.
    val start = if (t.isEvent) t.startAt?.takeIf { it <= due } ?: due else t.timeStart ?: due
    val end = when {
        t.isEvent -> if (due > start) due else start + DEFAULT_BLOCK_MINUTES * MINUTE
        t.durationMinutes != null -> due
        else -> due + DEFAULT_BLOCK_MINUTES * MINUTE
    }
    val tz = t.timeZone?.takeIf { runCatching { ZoneId.of(it) }.isSuccess } ?: zone.id
    val startDay = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
    return ExportedEvent(
        key = t.id, title = title, description = description, location = t.location,
        start = start,
        end = if (rule == null) end else null,
        duration = if (rule != null) "P${(end - start) / 1000}S" else null,
        allDay = false, timeZone = tz,
        rrule = rule?.let { providerRule(it, start, zone) },
        exDate = t.exDates(startDay, t.dueDay(zone) ?: startDay, start, zone),
    )
}

private fun Task.allDayZone(zone: ZoneId): ZoneId =
    timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: zone

private fun LocalDate.utcMidnight(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/**
 * The rule in the form calendars take: UNTIL of a timed entry must be a UTC moment (the end of
 * that day here), while Tik Tak keeps a date.
 */
private fun providerRule(rule: RepeatRule, start: Long?, zone: ZoneId): String {
    val until = rule.until ?: return rule.toRRule()
    val text = rule.copy(until = null).toRRule()
    val value = if (start == null) until.format(DateTimeFormatter.BASIC_ISO_DATE)
    else until.plusDays(1).atStartOfDay(zone).minusSeconds(1).withZoneSameInstant(ZoneOffset.UTC).format(UTC_STAMP)
    return "$text;UNTIL=$value"
}

/**
 * Skipped occurrences (kept as due days) as the start moments of those occurrences: an entry
 * that starts the day before it ends (sleep) skips the day before its due day.
 */
private fun Task.exDates(firstDay: LocalDate, lastDay: LocalDate, start: Long?, zone: ZoneId): String? {
    val skipped = skippedDays().sorted()
    if (skipped.isEmpty() || repeatRule == null) return null
    val lead = ChronoUnit.DAYS.between(firstDay, lastDay)
    return skipped.joinToString(",") { day ->
        val first = day.minusDays(lead)
        if (start == null) first.atStartOfDay(ZoneOffset.UTC).format(UTC_STAMP)
        else first.atTime(Instant.ofEpochMilli(start).atZone(zone).toLocalTime()).atZone(zone).withZoneSameInstant(ZoneOffset.UTC).format(UTC_STAMP)
    }
}
