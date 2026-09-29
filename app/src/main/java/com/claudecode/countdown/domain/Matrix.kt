package com.claudecode.countdown.domain

import com.claudecode.countdown.data.db.DisplayMode
import com.claudecode.countdown.data.db.Task
import java.time.LocalDate

/** Time horizon of the Eisenhower matrix; [days] is how many days after today it reaches. */
enum class MatrixPeriod(val label: String, val days: Long?) {
    ALL("Все", null),
    TODAY("Сегодня", 0),
    THREE_DAYS("3 дня", 2),
    WEEK("Неделя", 6),
    MONTH("Месяц", 29),
}

/**
 * Open tasks the matrix shows. Countdowns are events to wait for, not work to prioritise, so they
 * stay out. A period keeps tasks due by its last day, overdue ones included.
 */
fun matrixTasks(tasks: List<Task>, period: MatrixPeriod, today: LocalDate): List<Task> {
    val last = period.days?.let { today.plusDays(it) }
    return tasks.filter { t ->
        !t.isDone && t.displayMode != DisplayMode.COUNTDOWN &&
            (last == null || t.dueDay()?.let { it <= last } == true)
    }
}
