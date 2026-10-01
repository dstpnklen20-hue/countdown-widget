package com.claudecode.countdown.data

import com.claudecode.countdown.data.db.AppDatabase
import com.claudecode.countdown.data.db.FocusSession
import com.claudecode.countdown.data.db.Habit
import com.claudecode.countdown.data.db.HabitCheckIn
import com.claudecode.countdown.data.db.now
import java.time.LocalDate

/** [onClosed] takes down the habit's reminder notification once today's goal no longer needs it. */
class HabitRepository(
    db: AppDatabase,
    private val onClosed: (habitId: String) -> Unit = {},
    private val onChanged: suspend () -> Unit,
) {
    private val dao = db.habitDao()

    fun observeHabits() = dao.observeActive()
    fun observeArchived() = dao.observeArchived()
    fun observeCheckIns() = dao.observeCheckIns()
    suspend fun active() = dao.active()

    suspend fun save(habit: Habit, isNew: Boolean) {
        dao.upsert(if (isNew) habit.copy(sortOrder = dao.maxSortOrder() + 1) else habit.copy(updatedAt = now()))
        onChanged()
    }

    suspend fun setArchived(habit: Habit, archived: Boolean) {
        dao.upsert(habit.copy(archived = archived, updatedAt = now()))
        if (archived) onClosed(habit.id)
        onChanged()
    }

    suspend fun delete(habit: Habit) {
        dao.softDelete(habit.id)
        onClosed(habit.id)
        onChanged()
    }

    /** Undo for [delete]: [habit] is the state it had before deletion. */
    suspend fun restore(habit: Habit) {
        dao.upsert(habit.copy(deleted = false, updatedAt = now()))
        onChanged()
    }

    suspend fun setCount(habitId: String, day: LocalDate, count: Int) {
        val existing = dao.checkIn(habitId, day.toEpochDay())
        dao.upsertCheckIn(
            existing?.copy(count = count, deleted = false, updatedAt = now())
                ?: HabitCheckIn(habitId = habitId, day = day.toEpochDay(), count = count)
        )
        if (day == LocalDate.now() && count >= (dao.active().firstOrNull { it.id == habitId }?.goal ?: 1)) onClosed(habitId)
        onChanged()
    }

    suspend fun countOn(habitId: String, day: LocalDate): Int =
        dao.checkIn(habitId, day.toEpochDay())?.takeIf { !it.deleted }?.count ?: 0
}

class FocusRepository(db: AppDatabase) {
    private val dao = db.focusDao()

    fun observeFocusSince(since: Long) = dao.observeFocusSince(since)
    fun observeTotalFocusMs() = dao.observeTotalFocusMs()
    fun observeFocusCount() = dao.observeFocusCount()
    suspend fun record(session: FocusSession) = dao.upsert(session)
}
