package com.claudecode.countdown.domain

import com.claudecode.countdown.data.db.Reminder
import com.claudecode.countdown.data.db.Task
import com.claudecode.tiktak.core.RepeatRule
import java.time.Instant
import java.time.ZoneId

/** Default time of day (minutes after midnight) at which all-day tasks remind; the user can change it. */
const val ALL_DAY_REMINDER_MINUTES = 9 * 60

private const val MINUTE = 60_000L

/**
 * When [reminder] should fire for [task]; a later snooze wins over the base time. All-day tasks
 * remind at [allDayMinutes] after midnight of the due day (minus the offset). Events remind
 * before they start rather than end, and a repeating event, which is never ticked off and so
 * never moves on, reminds before its first occurrence that fires after [after].
 */
fun reminderTrigger(task: Task, reminder: Reminder, allDayMinutes: Int = ALL_DAY_REMINDER_MINUTES, after: Long = Long.MIN_VALUE): Long? {
    reminder.absoluteAt?.let { return withSnooze(it, reminder) }
    val anchor = (if (task.isEvent) task.startAt?.takeIf { s -> task.dueAt.let { it == null || s <= it } } else null) ?: task.dueAt ?: return null
    val offset = (reminder.offsetMinutes ?: 0) * MINUTE
    fun triggerAt(at: Long) = if (task.isAllDay) at + allDayMinutes * MINUTE - offset else at - offset
    var base = triggerAt(anchor)
    val skipped = if (task.isEvent) task.skippedDays() else emptySet()
    val first = Instant.ofEpochMilli(anchor).atZone(ZoneId.systemDefault())
    // Occurrences are told apart by their due day; the anchor may be on the day before (sleep).
    val firstDue = task.dueDay() ?: first.toLocalDate()
    if (task.isEvent && (base <= after || firstDue in skipped)) {
        var rule = RepeatRule.parse(task.repeatRule) ?: return if (firstDue in skipped) null else withSnooze(base, reminder)
        var day = firstDue
        var steps = 0
        while ((base <= after || day in skipped) && steps++ < 2_000) {
            day = rule.nextAfter(day) ?: return null
            rule = rule.advanced()
            base = triggerAt(first.plusDays(java.time.temporal.ChronoUnit.DAYS.between(firstDue, day)).toInstant().toEpochMilli())
        }
    }
    return withSnooze(base, reminder)
}

private fun withSnooze(base: Long, reminder: Reminder): Long {
    val snooze = reminder.snoozedUntil
    return if (snooze != null && snooze > base) snooze else base
}

data class ReminderPreset(val offsetMinutes: Int, val label: String)

val TIMED_REMINDER_PRESETS = listOf(
    ReminderPreset(0, "В момент"),
    ReminderPreset(5, "За 5 минут"),
    ReminderPreset(30, "За 30 минут"),
    ReminderPreset(60, "За 1 час"),
    ReminderPreset(24 * 60, "За 1 день"),
)

fun formatMinuteOfDay(minutes: Int): String = "%d:%02d".format(minutes / 60, minutes % 60)

fun allDayReminderPresets(allDayMinutes: Int = ALL_DAY_REMINDER_MINUTES): List<ReminderPreset> {
    val time = formatMinuteOfDay(allDayMinutes)
    return listOf(
        ReminderPreset(0, "В день ($time)"),
        ReminderPreset(24 * 60, "За день ($time)"),
        ReminderPreset(2 * 24 * 60, "За 2 дня ($time)"),
        ReminderPreset(7 * 24 * 60, "За неделю ($time)"),
    )
}

fun reminderLabel(offsetMinutes: Int, allDay: Boolean, allDayMinutes: Int = ALL_DAY_REMINDER_MINUTES): String {
    val presets = if (allDay) allDayReminderPresets(allDayMinutes) else TIMED_REMINDER_PRESETS
    presets.firstOrNull { it.offsetMinutes == offsetMinutes }?.let { return it.label }
    val days = offsetMinutes / (24 * 60)
    val hours = offsetMinutes % (24 * 60) / 60
    val minutes = offsetMinutes % 60
    val parts = buildList {
        if (days > 0) add("$days д")
        if (hours > 0) add("$hours ч")
        if (minutes > 0) add("$minutes мин")
    }
    return "За " + parts.joinToString(" ")
}

/** Picks the reminders whose trigger falls into (from, to]. */
fun dueReminders(
    tasks: Map<String, Task>,
    reminders: List<Reminder>,
    from: Long,
    to: Long,
    allDayMinutes: Int = ALL_DAY_REMINDER_MINUTES,
): List<Pair<Task, Reminder>> =
    reminders.mapNotNull { r ->
        val task = tasks[r.taskId] ?: return@mapNotNull null
        val at = reminderTrigger(task, r, allDayMinutes, after = from) ?: return@mapNotNull null
        if (at > from && at <= to) task to r else null
    }

fun nextTrigger(tasks: Map<String, Task>, reminders: List<Reminder>, after: Long, allDayMinutes: Int = ALL_DAY_REMINDER_MINUTES): Long? =
    reminders.mapNotNull { r -> tasks[r.taskId]?.let { reminderTrigger(it, r, allDayMinutes, after) } }.filter { it > after }.minOrNull()
