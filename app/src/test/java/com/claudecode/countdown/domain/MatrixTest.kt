package com.claudecode.countdown.domain

import com.claudecode.countdown.data.db.DisplayMode
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.data.db.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MatrixTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val today = LocalDate.of(2026, 9, 29)

    // All-day tasks carry their own zone, so the result does not depend on the machine running the test.
    private fun task(title: String, inDays: Long?, done: Boolean = false, countdown: Boolean = false): Task {
        val due = inDays?.let { allDayDue(today.plusDays(it), zone) }
        return Task(
            title = title,
            dueAt = due?.at,
            isAllDay = due != null,
            timeZone = due?.timeZone,
            status = if (done) TaskStatus.DONE else TaskStatus.OPEN,
            displayMode = if (countdown) DisplayMode.COUNTDOWN else DisplayMode.NORMAL,
        )
    }

    private val tasks = listOf(
        task("overdue", -2),
        task("today", 0),
        task("in 2 days", 2),
        task("in 5 days", 5),
        task("in 20 days", 20),
        task("in 40 days", 40),
        task("no date", null),
        task("done", 0, done = true),
        task("birthday", 1, countdown = true),
    )

    private fun titles(period: MatrixPeriod) = matrixTasks(tasks, period, today).map { it.title }

    @Test
    fun allShowsOpenTasksWithoutCountdowns() {
        assertEquals(listOf("overdue", "today", "in 2 days", "in 5 days", "in 20 days", "in 40 days", "no date"), titles(MatrixPeriod.ALL))
    }

    @Test
    fun periodKeepsTasksDueByItsLastDayIncludingOverdue() {
        assertEquals(listOf("overdue", "today"), titles(MatrixPeriod.TODAY))
        assertEquals(listOf("overdue", "today", "in 2 days"), titles(MatrixPeriod.THREE_DAYS))
        assertEquals(listOf("overdue", "today", "in 2 days", "in 5 days"), titles(MatrixPeriod.WEEK))
        assertEquals(listOf("overdue", "today", "in 2 days", "in 5 days", "in 20 days"), titles(MatrixPeriod.MONTH))
    }
}
