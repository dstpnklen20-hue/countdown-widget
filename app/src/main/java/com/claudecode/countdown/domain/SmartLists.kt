package com.claudecode.countdown.domain

import com.claudecode.countdown.data.db.DisplayMode
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.data.db.TaskList
import java.text.Collator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

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

enum class GroupKind { OVERDUE, TODAY, TOMORROW, DAY, LATER, NO_DATE, PRIORITY, CATEGORY, LIST, ALL, DONE }

data class TaskGroup(
    val kind: GroupKind,
    val date: LocalDate?,
    val tasks: List<Task>,
    val priority: Int? = null,
    val listId: String? = null,
) {
    /** Stable id of the section, e.g. for remembering which ones are collapsed. */
    val key: String get() = "$kind:${date ?: priority ?: listId ?: ""}"
}

/** How a task list is ordered; the choice is remembered per list (see AppSettings). */
enum class TaskSort(val label: String) {
    DATE("По дате"),
    PRIORITY("По приоритету"),
    LIST("По спискам"),
    TITLE("По названию"),
    CREATED("Сначала новые"),
}

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
    // Countdowns are events to wait for, not work: they live in their own list (and the calendar).
    if (task.displayMode == DisplayMode.COUNTDOWN) return filter == TaskFilter.Countdowns
    // Events (sleep, lunch) only take time in the calendar; there is nothing to do about them.
    if (task.isEvent) return false
    val day = task.dueDay(zone)
    return when (filter) {
        TaskFilter.Inbox -> task.listId == TaskList.INBOX_ID
        TaskFilter.All -> true
        TaskFilter.Countdowns -> false
        TaskFilter.Completed -> task.isDone
        TaskFilter.Today -> day != null && (day == today || (!task.isDone && day < today))
        TaskFilter.Tomorrow -> day == today.plusDays(1)
        TaskFilter.Next7Days -> day != null && day < today.plusDays(7) && (day >= today || !task.isDone)
        is TaskFilter.ListFilter -> task.listId == filter.listId
        is TaskFilter.TagFilter -> filter.tagId in tagIds
    }
}

private val titleCollator = Collator.getInstance(Locale("ru")).apply { strength = Collator.PRIMARY }

/**
 * Splits already-filtered tasks into TickTick-like sections, completed ones always at the bottom.
 * By date: overdue, today, tomorrow, the rest of the week day by day, later, no date.
 * By priority: one section per priority. By list: one per list, in [listOrder].
 * By title or creation: a single section. Completed tasks: see [groupCompleted].
 */
fun groupTasks(
    filter: TaskFilter,
    tasks: List<Task>,
    now: Long,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
    sort: TaskSort = TaskSort.DATE,
    listOrder: Map<String, Int> = emptyMap(),
): List<TaskGroup> {
    if (filter == TaskFilter.Completed) return groupCompleted(tasks, sort, listOrder, zone)
    val (done, open) = tasks.partition { it.isDone }
    val groups = when (sort) {
        TaskSort.DATE -> groupByDate(open, now, today, zone)
        TaskSort.PRIORITY -> open.sortedWith(taskOrder).groupBy { it.priority }.entries
            .sortedByDescending { it.key }
            .map { TaskGroup(GroupKind.PRIORITY, null, it.value, priority = it.key) }
        TaskSort.LIST -> byList(open.sortedWith(taskOrder), listOrder)
        TaskSort.TITLE -> listOfNotNull(
            open.sortedWith(compareBy(titleCollator) { it.title.trim() }).takeIf { it.isNotEmpty() }
                ?.let { TaskGroup(GroupKind.ALL, null, it) }
        )
        TaskSort.CREATED -> listOfNotNull(
            open.sortedByDescending { it.createdAt }.takeIf { it.isNotEmpty() }?.let { TaskGroup(GroupKind.ALL, null, it) }
        )
    }
    val doneSorted = done.sortedByDescending { it.completedAt ?: it.updatedAt }
    return if (doneSorted.isEmpty()) groups else groups + TaskGroup(GroupKind.DONE, null, doneSorted)
}

/**
 * The completed tasks, newest first, in sections: by the day they were done (date), by matrix
 * category (priority), by list, or all in one section.
 */
private fun groupCompleted(tasks: List<Task>, sort: TaskSort, listOrder: Map<String, Int>, zone: ZoneId): List<TaskGroup> {
    val done = tasks.sortedByDescending { it.doneAt }
    if (done.isEmpty()) return emptyList()
    return when (sort) {
        TaskSort.DATE -> done.groupBy { Instant.ofEpochMilli(it.doneAt).atZone(zone).toLocalDate() }
            .map { (day, list) -> TaskGroup(GroupKind.DAY, day, list) }
        TaskSort.PRIORITY -> done.groupBy { it.priority }.entries
            .sortedByDescending { it.key }
            .map { TaskGroup(GroupKind.CATEGORY, null, it.value, priority = it.key) }
        TaskSort.LIST -> byList(done, listOrder)
        TaskSort.TITLE -> listOf(TaskGroup(GroupKind.DONE, null, done.sortedWith(compareBy(titleCollator) { it.title.trim() })))
        TaskSort.CREATED -> listOf(TaskGroup(GroupKind.DONE, null, done))
    }
}

private val Task.doneAt: Long get() = completedAt ?: updatedAt

private fun byList(tasks: List<Task>, listOrder: Map<String, Int>): List<TaskGroup> =
    tasks.groupBy { it.listId }.entries
        .sortedBy { listOrder[it.key] ?: Int.MAX_VALUE }
        .map { TaskGroup(GroupKind.LIST, null, it.value, listId = it.key) }

private fun groupByDate(open: List<Task>, now: Long, today: LocalDate, zone: ZoneId): List<TaskGroup> {
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
    return buckets.entries
        .sortedWith(compareBy({ order.indexOf(it.key.first) }, { it.key.second }))
        .map { TaskGroup(it.key.first, it.key.second, it.value) }
}
