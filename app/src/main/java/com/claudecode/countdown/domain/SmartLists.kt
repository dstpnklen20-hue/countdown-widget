package com.claudecode.countdown.domain

import com.claudecode.countdown.data.db.DisplayMode
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.data.db.TaskList
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

sealed interface TaskFilter {
    val key: String

    data object Inbox : TaskFilter { override val key = "inbox" }
    data object Today : TaskFilter { override val key = "today" }
    data object Tomorrow : TaskFilter { override val key = "tomorrow" }
    data object Next7Days : TaskFilter { override val key = "week" }
    data object All : TaskFilter { override val key = "all" }
    data object Countdowns : TaskFilter { override val key = "countdowns" }
    data object Completed : TaskFilter { override val key = "completed" }
    data class ListFilter(val listId: String) : TaskFilter { override val key = "list:$listId" }
    data class TagFilter(val tagId: String) : TaskFilter { override val key = "tag:$tagId" }

    /** New tasks created from this view get these defaults. */
    val isDateBased: Boolean get() = this == Today || this == Tomorrow || this == Next7Days

    companion object {
        val SMART = listOf(Inbox, Today, Tomorrow, Next7Days, All, Countdowns, Completed)

        fun parse(key: String): TaskFilter = when {
            key.startsWith("list:") -> ListFilter(key.removePrefix("list:"))
            key.startsWith("tag:") -> TagFilter(key.removePrefix("tag:"))
            else -> SMART.firstOrNull { it.key == key } ?: Inbox
        }
    }
}

enum class GroupKind { OVERDUE, TODAY, TOMORROW, DAY, LATER, NO_DATE, DONE }

data class TaskGroup(val kind: GroupKind, val date: LocalDate?, val tasks: List<Task>)

/** Calendar day of a task's due date. All-day dates stay fixed in the zone they were set in. */
fun Task.dueDay(deviceZone: ZoneId = ZoneId.systemDefault()): LocalDate? {
    val due = dueAt ?: return null
    val zone = if (isAllDay && timeZone != null) runCatching { ZoneId.of(timeZone) }.getOrDefault(deviceZone) else deviceZone
    return Instant.ofEpochMilli(due).atZone(zone).toLocalDate()
}

fun Task.isOverdue(now: Long, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Boolean {
    if (isDone) return false
    val day = dueDay(zone) ?: return false
    return if (isAllDay) day < today else (dueAt ?: Long.MAX_VALUE) < now && day <= today
}

private val taskOrder = compareBy<Task>({ it.dueAt == null }, { it.dueAt }, { -it.priority }, { it.sortOrder }, { it.createdAt })

fun matches(
    filter: TaskFilter,
    task: Task,
    tagIds: Set<String>,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): Boolean {
    val day = task.dueDay(zone)
    return when (filter) {
        TaskFilter.Inbox -> task.listId == TaskList.INBOX_ID
        TaskFilter.All -> true
        TaskFilter.Countdowns -> task.displayMode == DisplayMode.COUNTDOWN
        TaskFilter.Completed -> task.isDone
        TaskFilter.Today -> day != null && (day == today || (!task.isDone && day < today))
        TaskFilter.Tomorrow -> day == today.plusDays(1)
        TaskFilter.Next7Days -> day != null && day < today.plusDays(7) && (day >= today || !task.isDone)
        is TaskFilter.ListFilter -> task.listId == filter.listId
        is TaskFilter.TagFilter -> filter.tagId in tagIds
    }
}

/**
 * Splits already-filtered tasks into TickTick-like sections: overdue, today, tomorrow,
 * the rest of the week day by day, later, no date, and completed at the bottom.
 */
fun groupTasks(
    filter: TaskFilter,
    tasks: List<Task>,
    now: Long,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): List<TaskGroup> {
    if (filter == TaskFilter.Completed) {
        val done = tasks.sortedByDescending { it.completedAt ?: it.updatedAt }
        return if (done.isEmpty()) emptyList() else listOf(TaskGroup(GroupKind.DONE, null, done))
    }
    val (done, open) = tasks.partition { it.isDone }
    val buckets = linkedMapOf<Pair<GroupKind, LocalDate?>, MutableList<Task>>()
    for (task in open.sortedWith(taskOrder)) {
        val day = task.dueDay(zone)
        val key = when {
            day == null -> GroupKind.NO_DATE to null
            task.isOverdue(now, today, zone) || day < today -> GroupKind.OVERDUE to null
            day == today -> GroupKind.TODAY to day
            day == today.plusDays(1) -> GroupKind.TOMORROW to day
            day < today.plusDays(7) -> GroupKind.DAY to day
            else -> GroupKind.LATER to null
        }
        buckets.getOrPut(key) { mutableListOf() } += task
    }
    val order = listOf(GroupKind.OVERDUE, GroupKind.TODAY, GroupKind.TOMORROW, GroupKind.DAY, GroupKind.LATER, GroupKind.NO_DATE)
    val groups = buckets.entries
        .sortedWith(compareBy({ order.indexOf(it.key.first) }, { it.key.second }))
        .map { TaskGroup(it.key.first, it.key.second, it.value) }
    val doneSorted = done.sortedByDescending { it.completedAt ?: it.updatedAt }
    return if (doneSorted.isEmpty()) groups else groups + TaskGroup(GroupKind.DONE, null, doneSorted)
}
