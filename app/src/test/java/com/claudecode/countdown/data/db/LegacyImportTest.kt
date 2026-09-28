package com.claudecode.countdown.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LegacyImportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val opened = mutableListOf<AppDatabase>()

    private fun open(): AppDatabase = AppDatabase.build(context, inMemory = true).also { opened += it }

    @After
    fun close() = opened.forEach { it.close() }

    private fun writeLegacy() {
        context.getSharedPreferences(DatabaseSeeder.LEGACY_DATA_PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet("ids", setOf("a", "b", "broken"))
            .putString("title_a", "Отпуск").putLong("target_a", 2_000L)
            .putString("title_b", "Новый год").putLong("target_b", 1_000L)
            .putString("title_broken", "Без даты")
            .commit()
        context.getSharedPreferences(DatabaseSeeder.LEGACY_WIDGET_PREFS, Context.MODE_PRIVATE).edit()
            .putString("widget_7", "a")
            .commit()
    }

    @Test
    fun importsCountdownsAsTasksKeepingIdsAndWidgets() = runBlocking {
        writeLegacy()
        val db = open()

        val tasks = db.taskDao().countdowns()
        assertEquals(listOf("b", "a"), tasks.map { it.id })
        val a = tasks.last()
        assertEquals("Отпуск", a.title)
        assertEquals(2_000L, a.dueAt)
        assertEquals(DisplayMode.COUNTDOWN, a.displayMode)
        assertEquals(TaskList.INBOX_ID, a.listId)

        assertEquals("a", db.widgetBindingDao().get(7)?.taskId)
        assertTrue(db.taskListDao().get(TaskList.INBOX_ID)!!.isInbox)

        // Old storage stays as a backup.
        val legacy = context.getSharedPreferences(DatabaseSeeder.LEGACY_DATA_PREFS, Context.MODE_PRIVATE)
        assertNotNull(legacy.getString("title_a", null))
    }

    @Test
    fun importRunsOnlyOnce() = runBlocking {
        writeLegacy()
        open().taskDao().countdowns()

        val second = open()
        assertEquals(0, second.taskDao().countdowns().size)
        assertNotNull(second.taskListDao().get(TaskList.INBOX_ID))
    }

    @Test
    fun emptyInstallGetsOnlyInbox() = runBlocking {
        val db = open()
        assertEquals(0, db.taskDao().countdowns().size)
        assertNotNull(db.taskListDao().get(TaskList.INBOX_ID))
    }
}
