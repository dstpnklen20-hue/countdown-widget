package com.claudecode.countdown.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    version = 1,
    exportSchema = true,
    entities = [
        Folder::class, TaskList::class, Section::class, Task::class, ChecklistItem::class,
        Tag::class, TaskTag::class, Reminder::class, WidgetBinding::class,
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun taskListDao(): TaskListDao
    abstract fun folderDao(): FolderDao
    abstract fun sectionDao(): SectionDao
    abstract fun tagDao(): TagDao
    abstract fun checklistDao(): ChecklistDao
    abstract fun reminderDao(): ReminderDao
    abstract fun widgetBindingDao(): WidgetBindingDao

    companion object {
        const val NAME = "tiktak.db"

        // Schema changes must ship an explicit Migration: never fall back to a destructive rebuild.
        fun build(context: Context, inMemory: Boolean = false): AppDatabase {
            val app = context.applicationContext
            val builder = if (inMemory) {
                Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            } else {
                Room.databaseBuilder(app, AppDatabase::class.java, NAME)
            }
            return builder.addCallback(DatabaseSeeder(app)).build()
        }
    }
}
