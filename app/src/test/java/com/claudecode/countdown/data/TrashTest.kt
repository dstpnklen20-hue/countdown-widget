package com.claudecode.countdown.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.claudecode.countdown.data.db.AppDatabase
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.data.db.TaskList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrashTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = AppDatabase.build(context, inMemory = true)
    private val closed = mutableListOf<String>()
    private val repo = TaskRepository(db, onClosed = { closed += it }) {}

    @After
    fun close() = db.close()

    /** Deletions are told apart by their millisecond stamp. */
    private fun tick() = Thread.sleep(3)

    @Test
    fun trashKeepsThingsForThirtyDays() = runBlocking {
        val longAgo = System.currentTimeMillis() - 31 * 86_400_000L
        val old = repo.create(Task(title = "Старое", createdAt = longAgo - 1000))
        val recent = repo.create(Task(title = "Недавнее"))
        db.taskDao().softDelete(old.id, at = longAgo)
        repo.delete(recent.id)
        repo.purgeExpired()
        assertNull(repo.get(old.id))
        assertTrue(repo.get(recent.id)!!.deleted)
    }

    @Test
    fun undoBringsBackTaskWithSubtasksDeletedTogether() = runBlocking {
        val parent = repo.create(Task(title = "Переезд"))
        val kept = repo.create(Task(title = "Коробки", parentId = parent.id))
        val earlier = repo.create(Task(title = "Грузчики", parentId = parent.id))
        repo.delete(earlier.id)
        tick()

        val at = repo.delete(parent.id)
        assertTrue(repo.get(parent.id)!!.deleted)
        assertTrue(repo.get(kept.id)!!.deleted)
        assertEquals(listOf(earlier.id, parent.id), closed)

        repo.restore(parent.id, at)
        assertFalse(repo.get(parent.id)!!.deleted)
        assertFalse(repo.get(kept.id)!!.deleted)
        // Deleted on its own before: stays in the trash.
        assertTrue(repo.get(earlier.id)!!.deleted)
        assertEquals(listOf(earlier.id), repo.observeTrash().first().map { it.id })
    }

    @Test
    fun trashShowsSubtasksOnlyWhileParentIsAlive() = runBlocking {
        val parent = repo.create(Task(title = "Отпуск"))
        val sub = repo.create(Task(title = "Билеты", parentId = parent.id))
        repo.delete(parent.id)
        assertEquals(listOf(parent.id), repo.observeTrash().first().map { it.id })

        repo.restoreFromTrash(repo.get(parent.id)!!)
        assertTrue(repo.observeTrash().first().isEmpty())
        assertFalse(repo.get(sub.id)!!.deleted)
    }

    @Test
    fun deletedListComesBackWithItsTasks() = runBlocking {
        val list = repo.createList("Дом", null, null)
        val task = repo.create(Task(title = "Полить цветы", listId = list.id))
        val gone = repo.create(Task(title = "Старое", listId = list.id))
        repo.delete(gone.id)
        tick()

        val at = repo.deleteList(list)!!
        assertTrue(repo.observeLists().first().none { it.id == list.id })
        assertTrue(repo.get(task.id)!!.deleted)

        repo.restoreList(list, at)
        assertTrue(repo.observeLists().first().any { it.id == list.id })
        assertFalse(repo.get(task.id)!!.deleted)
        assertTrue(repo.get(gone.id)!!.deleted)
    }

    @Test
    fun restoringTaskOfDeletedListMovesItToInbox() = runBlocking {
        val list = repo.createList("Работа", null, null)
        val task = repo.create(Task(title = "Отчёт", listId = list.id))
        val sub = repo.create(Task(title = "Цифры", listId = list.id, parentId = task.id))
        repo.deleteList(list)

        repo.restoreFromTrash(repo.get(task.id)!!)
        val restored = repo.get(task.id)!!
        assertFalse(restored.deleted)
        assertEquals(TaskList.INBOX_ID, restored.listId)
        assertEquals(TaskList.INBOX_ID, repo.get(sub.id)!!.listId)
    }

    @Test
    fun purgeRemovesTaskAndEverythingAttached() = runBlocking {
        val task = repo.create(Task(title = "Черновик"), listOf("идеи"))
        val sub = repo.create(Task(title = "Пункт", parentId = task.id))
        repo.addChecklistItem(task.id, "шаг")
        repo.addReminder(task.id, 0)
        val other = repo.create(Task(title = "Другая"))
        repo.delete(task.id)
        repo.delete(other.id)

        repo.purge(repo.get(task.id)!!)
        assertNull(repo.get(task.id))
        assertNull(repo.get(sub.id))
        assertTrue(repo.observeChecklist(task.id).first().isEmpty())
        assertTrue(repo.observeReminders(task.id).first().isEmpty())
        assertTrue(repo.observeTaskTags().first().none { it.taskId == task.id })
        assertEquals(listOf(other.id), repo.observeTrash().first().map { it.id })

        repo.emptyTrash()
        assertNull(repo.get(other.id))
    }

    @Test
    fun completingTaskTakesDownItsNotification() = runBlocking {
        val task = repo.create(Task(title = "Позвонить"))
        repo.setDone(task, true)
        assertEquals(listOf(task.id), closed)
        repo.setDone(repo.get(task.id)!!, false)
        assertEquals(listOf(task.id), closed)
    }
}
