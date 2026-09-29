package com.claudecode.countdown.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Builds a real v1 database from the exported schemas/1.json, fills it, and opens it with the
 * current Room database. Room refuses to open it if the migrated schema differs from the entities.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun createV1(file: File) {
        val schema = JSONObject(File("schemas/com.claudecode.countdown.data.db.AppDatabase/1.json").readText())
            .getJSONObject("database")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val table = e.getString("tableName")
            db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = e.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        db.execSQL(
            "INSERT INTO task_lists (id, name, color, folderId, sortOrder, viewMode, isInbox, createdAt, updatedAt, deleted) " +
                "VALUES ('inbox', 'Входящие', NULL, NULL, 0, 'LIST', 1, 0, 0, 0)"
        )
        db.execSQL(
            "INSERT INTO tasks (id, listId, sectionId, parentId, title, content, priority, status, startAt, dueAt, isAllDay, " +
                "timeZone, repeatRule, repeatFrom, completedAt, sortOrder, displayMode, createdAt, updatedAt, deleted) " +
                "VALUES ('t1', 'inbox', NULL, NULL, 'Отпуск', '', 0, 'OPEN', NULL, 1000, 0, 'UTC', NULL, 'DUE', NULL, 0, 'COUNTDOWN', 0, 0, 0)"
        )
        db.version = 1
        db.close()
    }

    @Test
    fun migrateFromV1ToLatestKeepsDataAndMatchesEntities() = runBlocking {
        val file = context.getDatabasePath("migration-test.db").apply { parentFile?.mkdirs(); delete() }
        createV1(file)

        val db = Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        try {
            val task = db.taskDao().get("t1")
            assertNotNull(task)
            assertEquals("Отпуск", task!!.title)
            assertEquals(DisplayMode.COUNTDOWN, task.displayMode)

            // v2: new tables are usable.
            db.habitDao().upsert(Habit(name = "Вода", goal = 8))
            assertEquals(1, db.habitDao().active().size)
            // v3: old tasks get no colour of their own, and one can be stored.
            assertNull(task.color)
            db.taskDao().upsert(task.copy(color = 0xFF43A047.toInt()))
            assertEquals(0xFF43A047.toInt(), db.taskDao().get("t1")!!.color)
            assertEquals(3, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }
}
