package com.claudecode.countdown.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.claudecode.countdown.data.TaskRepository
import com.claudecode.countdown.data.db.AppDatabase
import com.claudecode.countdown.data.db.Habit
import com.claudecode.countdown.data.db.HabitCheckIn
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.data.db.TaskList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** Behaves like supabase/schema.sql: older versions never overwrite newer ones, pulls in server order. */
private class FakeServer : SyncApi {
    val records = LinkedHashMap<Pair<String, String>, RemoteRecord>()
    private var clock = Instant.parse("2026-01-01T00:00:00Z")

    override suspend fun upsert(records: List<RemoteRecord>) {
        for (r in records) {
            val old = this.records[r.kind to r.id]
            if (old != null && r.updatedAt <= old.updatedAt) continue
            clock = clock.plusMillis(1)
            this.records[r.kind to r.id] = r.copy(data = org.json.JSONObject(r.data.toString()), serverAt = clock)
        }
    }

    override suspend fun delete(kind: String, ids: List<String>) {
        ids.forEach { records.remove(kind to it) }
    }

    override suspend fun pull(since: Instant?, limit: Int): List<RemoteRecord> =
        records.values.filter { since == null || it.serverAt!! > since }.sortedBy { it.serverAt }.take(limit)
}

private class MemoryMarks : SyncMarks {
    override var pushedUpTo = 0L
    override var pulledUpTo: Instant? = null
    override var pendingDeletes: Set<String> = emptySet()
}

private class Device(context: Context, server: SyncApi, clock: () -> Long) {
    val db = AppDatabase.build(context, inMemory = true)
    val store = RowStore(db)
    val repo = TaskRepository(db) {}
    val engine = SyncEngine(store, server, MemoryMarks(), clock)
    suspend fun sync(dedupeAll: Boolean = false) = withContext(Dispatchers.IO) { engine.run(dedupeAll) }
}

@RunWith(RobolectricTestRunner::class)
class SyncTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = FakeServer()
    // Device clocks move forward in whole seconds so every edit has a distinct stamp.
    private var time = System.currentTimeMillis()
    private val clock = { time }
    private val a = Device(context, server, clock)
    private val b = Device(context, server, clock)

    @After
    fun close() {
        a.db.close()
        b.db.close()
    }

    private fun tick() { time += 1_000 }

    @Test
    fun taskCreatedEditedAndDeletedTravelsBothWays() = runBlocking {
        val task = a.repo.create(Task(title = "Купить молоко", createdAt = time))
        tick()
        a.sync(); b.sync()
        assertEquals("Купить молоко", b.repo.get(task.id)!!.title)

        tick()
        b.db.taskDao().upsert(b.repo.get(task.id)!!.copy(title = "Купить кефир", updatedAt = time))
        tick()
        b.sync(); a.sync()
        assertEquals("Купить кефир", a.repo.get(task.id)!!.title)

        tick()
        a.db.taskDao().softDelete(task.id, at = time)
        tick()
        a.sync(); b.sync()
        assertTrue(b.repo.get(task.id)!!.deleted)
    }

    @Test
    fun newerEditWinsWhicheverDeviceSyncsFirst() = runBlocking {
        val task = a.repo.create(Task(title = "v1", createdAt = time))
        tick(); a.sync(); b.sync()

        tick()
        a.db.taskDao().upsert(a.repo.get(task.id)!!.copy(title = "from A (older)", updatedAt = time))
        tick()
        b.db.taskDao().upsert(b.repo.get(task.id)!!.copy(title = "from B (newer)", updatedAt = time))
        tick()
        // B syncs first, then A pushes its older edit: the server keeps B's.
        b.sync(); a.sync(); b.sync()
        assertEquals("from B (newer)", a.repo.get(task.id)!!.title)
        assertEquals("from B (newer)", b.repo.get(task.id)!!.title)
    }

    @Test
    fun completionReachesADeviceWhoseClockIsAhead() = runBlocking {
        // B's clock is five minutes fast: the task it made carries a stamp from A's future.
        val ahead = System.currentTimeMillis() + 5 * 60_000L
        val task = b.repo.create(Task(title = "Сдать отчёт", createdAt = ahead))
        tick(); b.sync(); a.sync()

        a.repo.setDone(a.repo.get(task.id)!!, true)
        tick(); a.sync(); b.sync()
        assertTrue(b.repo.get(task.id)!!.isDone)

        // And back: B reopens it, A sees that too.
        b.repo.setDone(b.repo.get(task.id)!!, false)
        tick(); b.sync(); a.sync()
        assertFalse(a.repo.get(task.id)!!.isDone)

        a.repo.delete(task.id)
        tick(); a.sync(); b.sync()
        assertTrue(b.repo.get(task.id)!!.deleted)
    }

    @Test
    fun removedTagDisappearsOnOtherDevice() = runBlocking {
        val task = a.repo.create(Task(title = "t", createdAt = time), tagNames = listOf("работа", "дом"))
        tick(); a.sync(); b.sync()
        assertEquals(setOf("работа", "дом"), b.db.tagDao().tagNamesFor(task.id).toSet())

        tick()
        a.db.tagDao().setTaskTags(task.id, a.db.tagDao().linksFor(task.id).filter { !it.deleted }.map { it.tagId }.take(1), at = time)
        tick(); a.sync(); b.sync()
        assertEquals(1, b.db.tagDao().tagNamesFor(task.id).size)
    }

    @Test
    fun sameHabitDayOnTwoDevicesEndsWithOneCheckIn() = runBlocking {
        val habit = Habit(name = "Вода", createdAt = time)
        a.db.habitDao().upsert(habit)
        tick(); a.sync(); b.sync()

        tick()
        a.db.habitDao().upsertCheckIn(HabitCheckIn(habitId = habit.id, day = 100, count = 1, createdAt = time))
        tick()
        b.db.habitDao().upsertCheckIn(HabitCheckIn(habitId = habit.id, day = 100, count = 3, createdAt = time))
        tick()
        a.sync(); b.sync(); a.sync(); b.sync()

        // B's check-in is newer: both devices keep it, and the server holds only one for that day.
        assertEquals(3, a.db.habitDao().checkIn(habit.id, 100)!!.count)
        assertEquals(3, b.db.habitDao().checkIn(habit.id, 100)!!.count)
        assertEquals(1, server.records.keys.count { it.first == "habit_checkins" })
    }

    @Test
    fun purgedTaskIsRemovedFromTheAccount() = runBlocking {
        val task = a.repo.create(Task(title = "в корзину", createdAt = time))
        tick(); a.sync()
        a.engine.forget(SyncTable.TASKS, listOf(task.id))
        a.db.taskDao().purge(listOf(task.id))
        a.sync(); b.sync()
        assertNull(b.repo.get(task.id))
        assertFalse(server.records.containsKey("tasks" to task.id))
    }

    @Test
    fun editStampedJustBeforeAPushIsNotLost() = runBlocking {
        a.sync()
        // Stamped a moment before that push's mark, but written to the database after it read the rows.
        val late = Task(title = "успела в последний момент", createdAt = time - 500)
        a.db.taskDao().upsert(late)
        tick(); a.sync(); b.sync()
        assertEquals("успела в последний момент", b.repo.get(late.id)?.title)
    }

    @Test
    fun inboxOfBothDevicesIsOneList() = runBlocking {
        a.sync(); b.sync(); a.sync()
        assertEquals(1, server.records.keys.count { it.first == "task_lists" && it.second == TaskList.INBOX_ID })
        assertTrue(a.db.taskListDao().all().any { it.id == TaskList.INBOX_ID })
    }

    @Test
    fun backupRoundTripAndRepeatedImportChangesNothing() = runBlocking {
        val task = a.repo.create(Task(title = "из копии", createdAt = time), tagNames = listOf("тег"))
        val file = withContext(Dispatchers.IO) { Backup.export(a.store, "test") }
        val restored = withContext(Dispatchers.IO) { Backup.import(b.store, file) }
        assertTrue(restored > 0)
        assertEquals("из копии", b.repo.get(task.id)!!.title)
        assertEquals(listOf("тег"), b.db.tagDao().tagNamesFor(task.id))
        assertEquals(0, withContext(Dispatchers.IO) { Backup.import(b.store, file) })
    }

    @Test(expected = Backup.NotABackup::class)
    fun randomJsonIsNotABackup() {
        Backup.import(a.store, """{"hello": 1}""")
    }

    private data class Live(val id: String, val title: String, val listId: String)

    private fun liveTasks(d: Device): List<Live> =
        d.store.all(SyncTable.TASKS).filter { it.getInt("deleted") == 0 }.map { Live(it.getString("id"), it.getString("title"), it.getString("listId")) }

    @Test
    fun sameTaskMadeOnTwoDevicesEndsUpOnce() = runBlocking {
        val due = time + 86_400_000
        val first = a.repo.create(Task(title = "Отпуск", dueAt = due, createdAt = time), tagNames = listOf("отдых"))
        tick()
        val second = b.repo.create(Task(title = " отпуск ", dueAt = due, createdAt = time), tagNames = listOf("отдых"))
        tick()
        a.sync(); b.sync(); a.sync(); b.sync()

        for (d in listOf(a, b)) {
            assertEquals(listOf(first.id), liveTasks(d).map { it.id })
            assertTrue(d.repo.get(second.id)!!.deleted)
            // The tag the copy had is still on the task, once.
            assertEquals(listOf("отдых"), d.db.tagDao().tagNamesFor(first.id))
        }
    }

    @Test
    fun identicalTasksMadeOnOneDeviceStay() = runBlocking {
        a.repo.create(Task(title = "Позвонить", createdAt = time))
        tick()
        a.repo.create(Task(title = "Позвонить", createdAt = time))
        tick()
        // Not the first run any more: only copies from different devices are merged.
        a.sync(); b.sync()
        assertEquals(2, liveTasks(a).size)
        assertEquals(2, liveTasks(b).size)
    }

    @Test
    fun sameListOnTwoDevicesBecomesOneWithAllTasks() = runBlocking {
        val workA = a.repo.createList("Работа", null, null)
        a.repo.create(Task(title = "Отчёт", listId = workA.id, createdAt = time))
        tick()
        val workB = b.repo.createList("Работа", null, null)
        b.repo.create(Task(title = "Письмо", listId = workB.id, createdAt = time))
        tick()
        a.sync(); b.sync(); a.sync(); b.sync()

        val keep = minOf(workA, workB, compareBy({ it.createdAt }, { it.id }))
        for (d in listOf(a, b)) {
            assertEquals(1, d.db.taskListDao().all().count { !it.deleted && it.name == "Работа" })
            assertEquals(setOf("Отчёт", "Письмо"), liveTasks(d).filter { it.listId == keep.id }.map { it.title }.toSet())
        }
    }

    @Test
    fun duplicatesThatSyncedEarlierAreClearedOnce() = runBlocking {
        a.repo.create(Task(title = "Сон", createdAt = time))
        tick()
        a.repo.create(Task(title = "Сон", createdAt = time))
        tick()
        assertEquals(2, liveTasks(a).size)
        a.sync()
        a.sync(dedupeAll = true)
        assertEquals(1, liveTasks(a).size)
        b.sync()
        assertEquals(1, liveTasks(b).size)
    }
}
