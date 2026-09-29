package com.claudecode.countdown.domain

import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.data.db.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {

    private val tasks = listOf(
        Task(title = "Купить молоко", updatedAt = 1),
        Task(title = "Позвонить", content = "спросить про молоко", updatedAt = 2),
        Task(title = "Молоко и хлеб", status = TaskStatus.DONE, updatedAt = 3),
        Task(title = "Отчёт", updatedAt = 4),
    )

    private fun titles(query: String) = searchTasks(tasks, query).map { it.title }

    @Test
    fun matchesTitleAndNotesIgnoringCaseOpenAndTitleFirst() {
        assertEquals(listOf("Купить молоко", "Позвонить", "Молоко и хлеб"), titles("МОЛОКО"))
    }

    @Test
    fun everyWordMustMatch() {
        assertEquals(listOf("Молоко и хлеб"), titles("хлеб молоко"))
    }

    @Test
    fun blankQueryFindsNothing() {
        assertTrue(titles("  ").isEmpty())
    }
}
