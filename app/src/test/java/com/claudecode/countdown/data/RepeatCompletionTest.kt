package com.claudecode.countdown.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.claudecode.countdown.data.db.AppDatabase
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.data.db.TaskStatus
import com.claudecode.countdown.domain.allDayDue
import com.claudecode.countdown.domain.today
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RepeatCompletionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = AppDatabase.build(context, inMemory = true)
    private val repo = TaskRepository(db) {}

    @After
    fun close() = db.close()

    @Test
    fun completingRepeatingTaskAdvancesItAndKeepsHistory() = runBlocking {
        val due = allDayDue(today())
        val task = repo.create(Task(title = "Зарядка", dueAt = due.at, isAllDay = true, timeZone = due.timeZone, repeatRule = "FREQ=DAILY;COUNT=2"))
        repo.addChecklistItem(task.id, "Отжимания")
        val item = repo.observeChecklist(task.id).first().single()
        repo.updateChecklistItem(item.copy(checked = true))

        repo.setDone(repo.get(task.id)!!, true)

        val moved = repo.get(task.id)!!
        assertEquals(TaskStatus.OPEN, moved.status)
        assertEquals(allDayDue(today().plusDays(1)).at, moved.dueAt)
        assertEquals("FREQ=DAILY;COUNT=1", moved.repeatRule)
        assertFalse(repo.observeChecklist(task.id).first().single().checked)

        val all = repo.observeTopLevel().first()
        val history = all.single { it.id != task.id }
        assertEquals(TaskStatus.DONE, history.status)
        assertNull(history.repeatRule)

        // Last occurrence of the series: completes for real.
        repo.setDone(moved, true)
        assertEquals(TaskStatus.DONE, repo.get(task.id)!!.status)
    }
}
