package com.claudecode.countdown.domain

import com.claudecode.countdown.data.db.RepeatFrom
import com.claudecode.countdown.data.db.Task
import com.claudecode.tiktak.core.RepeatRule
import com.claudecode.tiktak.core.describe
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private fun Task.zone(): ZoneId = timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

/**
 * The same task moved to its next occurrence, or null when the series is over.
 * Keeps the time of day in the task's own zone; startAt shifts by the same amount as dueAt.
 */
fun Task.nextOccurrence(completedAt: Long): Task? {
    val rule = RepeatRule.parse(repeatRule) ?: return null
    val due = dueAt ?: return null
    val zone = zone()
    val dueDateTime = Instant.ofEpochMilli(due).atZone(zone)
    val base = if (repeatFrom == RepeatFrom.COMPLETION) Instant.ofEpochMilli(completedAt).atZone(zone).toLocalDate()
    else dueDateTime.toLocalDate()

    var nextDate = rule.nextAfter(base) ?: return null
    var nextRule = rule.advanced()
    // Catch up an overdue series so completing it lands in the future, like TickTick.
    val today = LocalDate.now(zone)
    while (repeatFrom == RepeatFrom.DUE && nextDate.isBefore(today)) {
        nextDate = nextRule.nextAfter(nextDate) ?: return null
        nextRule = nextRule.advanced()
    }
    val nextDue = nextDate.atTime(dueDateTime.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
    val shift = nextDue - due
    return copy(
        dueAt = nextDue,
        startAt = startAt?.plus(shift),
        repeatRule = nextRule.toRRule(),
    )
}

fun Task.repeatDescription(): String? =
    RepeatRule.parse(repeatRule)?.describe()

