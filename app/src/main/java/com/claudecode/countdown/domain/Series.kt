package com.claudecode.countdown.domain

import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.data.db.newId
import com.claudecode.tiktak.core.RepeatRule
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Which occurrences of a repeating event a change or a deletion applies to, as Google Calendar asks. */
enum class SeriesScope(val label: String) {
    ONE("Только это событие"),
    FOLLOWING("Это и последующие"),
    ALL("Все события"),
}

/** Days a repeating entry skips (see [Task.exDates]). */
fun Task.skippedDays(): Set<LocalDate> =
    exDates?.split(',')?.mapNotNull { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }?.toSet().orEmpty()

private fun Set<LocalDate>.toExDates(): String? = if (isEmpty()) null else sorted().joinToString(",")

fun Task.withSkipped(day: LocalDate): Task = copy(exDates = (skippedDays() + day).toExDates())

/**
 * Due days of a repeating entry from its first one up to [until], skipped ones included (they
 * still count for COUNT, as in RFC 5545). A single day for an entry that does not repeat.
 */
fun Task.occurrenceDays(until: LocalDate, zone: ZoneId = ZoneId.systemDefault(), max: Int = 5_000): List<LocalDate> {
    val first = dueDay(zone) ?: return emptyList()
    val out = mutableListOf(first)
    var rule = RepeatRule.parse(repeatRule) ?: return out
    var day = first
    while (out.size < max) {
        day = rule.nextAfter(day) ?: break
        if (day > until) break
        rule = rule.advanced()
        out += day
    }
    return out
}

/** Where the occurrence due on [day] starts: the entry's own start moved to that day. */
fun Task.occurrenceStart(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime? {
    val first = dueDay(zone) ?: return null
    val start = Instant.ofEpochMilli(startAt?.takeIf { s -> dueAt.let { it == null || s <= it } } ?: dueAt!!).atZone(zone).toLocalDateTime()
    return start.plusDays(ChronoUnit.DAYS.between(first, day))
}

/** The entry as it is on the occurrence due on [day]: the same length, moved to that day. */
fun Task.atOccurrence(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Task {
    val first = dueDay(zone) ?: return this
    val days = ChronoUnit.DAYS.between(first, day)
    if (days == 0L) return this
    fun move(at: Long?) = at?.let { Instant.ofEpochMilli(it).atZone(zone).plusDays(days).toInstant().toEpochMilli() }
    return copy(startAt = move(startAt), dueAt = move(dueAt))
}

/**
 * The occurrence due on [occurrence] with its start moved by [startMinutes] and its end by
 * [endMinutes] (a block dragged or stretched on the time grid). A task with only a due time is a
 * block of [DEFAULT_BLOCK_MINUTES] from it: stretching it gives it a start.
 */
fun Task.shifted(occurrence: LocalDate, startMinutes: Long, endMinutes: Long, zone: ZoneId = ZoneId.systemDefault()): Task {
    val occ = atOccurrence(occurrence, zone)
    val due = occ.dueAt ?: return occ
    fun move(at: Long, minutes: Long) = Instant.ofEpochMilli(at).atZone(zone).plusMinutes(minutes).toInstant().toEpochMilli()
    val start = occ.startAt?.takeIf { it < due }
    return when {
        start != null -> occ.copy(startAt = move(start, startMinutes), dueAt = move(due, endMinutes).coerceAtLeast(move(start, startMinutes) + 15 * 60_000L))
        startMinutes == endMinutes -> occ.copy(dueAt = move(due, startMinutes))
        else -> {
            val end = move(due, DEFAULT_BLOCK_MINUTES + endMinutes).coerceAtLeast(due + 15 * 60_000L)
            occ.copy(startAt = due, dueAt = end)
        }
    }
}

/** What one change of a series does: rows to store as they are now, and rows that go to the trash. */
data class SeriesChange(val save: List<Task>, val delete: List<String> = emptyList())

/**
 * Saves [edited], the occurrence of [master] due on [occurrence] as the user changed it.
 * ONE takes it out of the series as an entry of its own; FOLLOWING ends the series the day before
 * and starts a new one from it; ALL changes the series, moving it by as many days as this
 * occurrence moved and giving it the new times.
 */
fun editSeries(master: Task, occurrence: LocalDate, edited: Task, scope: SeriesScope, now: Long, zone: ZoneId = ZoneId.systemDefault()): SeriesChange {
    val first = master.dueDay(zone)
    val repeats = master.repeatRule != null && first != null
    val effective = when {
        !repeats -> SeriesScope.ALL
        scope == SeriesScope.FOLLOWING && occurrence <= first!! -> SeriesScope.ALL
        else -> scope
    }
    return when (effective) {
        SeriesScope.ONE -> SeriesChange(
            listOf(
                master.withSkipped(occurrence),
                edited.copy(
                    id = newId(), repeatRule = null, exDates = null, seriesId = master.id,
                    createdAt = now, updatedAt = now,
                ),
            )
        )
        SeriesScope.ALL -> {
            if (!repeats) return SeriesChange(listOf(edited.copy(id = master.id)))
            // The series keeps its first day, moved by as many days as this occurrence moved.
            val editedDay = edited.dueDay(zone) ?: return SeriesChange(listOf(edited.copy(id = master.id)))
            val moved = edited.atOccurrence(editedDay.minusDays(ChronoUnit.DAYS.between(first!!, occurrence)), zone)
            SeriesChange(
                listOf(
                    moved.copy(
                        id = master.id, exDates = master.exDates, seriesId = master.seriesId,
                        createdAt = master.createdAt, updatedAt = master.updatedAt,
                    )
                )
            )
        }
        SeriesScope.FOLLOWING -> {
            val (ended, rest) = splitSeries(master, occurrence, zone)
            val sameRule = edited.repeatRule == master.repeatRule
            val skipped = master.skippedDays().filter { it >= occurrence }.toSet()
            SeriesChange(
                listOf(
                    ended,
                    edited.copy(
                        id = newId(),
                        repeatRule = if (sameRule) rest else edited.repeatRule,
                        exDates = skipped.toExDates(),
                        seriesId = null,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            )
        }
    }
}

/** Deletes the occurrence of [master] due on [occurrence], the ones after it too, or the whole series. */
fun deleteFromSeries(master: Task, occurrence: LocalDate, scope: SeriesScope, zone: ZoneId = ZoneId.systemDefault()): SeriesChange {
    val first = master.dueDay(zone)
    val repeats = master.repeatRule != null && first != null
    return when {
        !repeats || scope == SeriesScope.ALL -> SeriesChange(emptyList(), listOf(master.id))
        scope == SeriesScope.ONE -> SeriesChange(listOf(master.withSkipped(occurrence)))
        occurrence <= first!! -> SeriesChange(emptyList(), listOf(master.id))
        else -> SeriesChange(listOf(splitSeries(master, occurrence, zone).first))
    }
}

/**
 * Ends [master] before [occurrence]: with UNTIL the day before, or for a rule counted with COUNT,
 * with as many occurrences as there were before. Returns the ended series and the rule (RRULE
 * text) for the rest of it: the same rule with the remaining COUNT.
 */
private fun splitSeries(master: Task, occurrence: LocalDate, zone: ZoneId): Pair<Task, String?> {
    val rule = RepeatRule.parse(master.repeatRule) ?: return master to null
    val before = master.occurrenceDays(occurrence.minusDays(1), zone).size
    val ended = if (rule.count != null) rule.copy(count = before) else rule.copy(until = occurrence.minusDays(1))
    val rest = if (rule.count != null) rule.copy(count = (rule.count!! - before).coerceAtLeast(1)) else rule
    return master.copy(
        repeatRule = ended.toRRule(),
        exDates = master.skippedDays().filter { it < occurrence }.toSet().toExDates(),
    ) to rest.toRRule()
}
