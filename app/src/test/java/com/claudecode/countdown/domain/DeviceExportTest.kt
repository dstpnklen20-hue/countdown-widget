package com.claudecode.countdown.domain

import com.claudecode.countdown.data.db.RepeatFrom
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.data.db.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class DeviceExportTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val monday = LocalDate.of(2026, 11, 2)
    private val now = timedDue(monday, LocalTime.of(8, 0), zone).at

    private fun at(day: LocalDate, h: Int, m: Int = 0) = timedDue(day, LocalTime.of(h, m), zone).at

    @Test
    fun aRepeatingEventGoesWithItsRuleLengthAndSkippedDays() {
        val ev = Task(
            title = "Планёрка", isEvent = true, startAt = at(monday, 9), dueAt = at(monday, 10),
            repeatRule = "FREQ=DAILY;UNTIL=20261130", exDates = "2026-11-04", timeZone = zone.id,
        )
        val e = exportedEvents(listOf(ev), now, zone).single()
        assertEquals(at(monday, 9), e.start)
        assertNull(e.end)
        assertEquals("P3600S", e.duration)
        // UNTIL of a timed event is the end of that day as a UTC moment.
        assertEquals("FREQ=DAILY;UNTIL=20261130T205959Z", e.rrule)
        // The skipped occurrence is named by its start: 9:00 Moscow = 6:00 UTC.
        assertEquals("20261104T060000Z", e.exDate)
        assertEquals("Europe/Moscow", e.timeZone)
    }

    @Test
    fun allDayEventsRunFromUtcMidnightToTheDayAfter() {
        val vacation = Task(
            title = "Отпуск", isEvent = true, isAllDay = true, timeZone = zone.id,
            startAt = allDayDue(monday, zone).at, dueAt = allDayDue(monday.plusDays(4), zone).at,
        )
        val e = exportedEvents(listOf(vacation), now, zone).single()
        assertTrue(e.allDay)
        assertEquals("UTC", e.timeZone)
        assertEquals(monday.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), e.start)
        assertEquals(monday.plusDays(5).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), e.end)
    }

    @Test
    fun tasksTakeTheirBlockAndDoneOrOldOnesStayOut() {
        val call = Task(title = "Позвонить", dueAt = at(monday, 18))
        val lesson = Task(title = "Урок", startAt = at(monday, 10), dueAt = at(monday, 11, 30))
        val done = Task(title = "Сделано", dueAt = at(monday, 12), status = TaskStatus.DONE)
        val old = Task(title = "Давно", isEvent = true, startAt = at(monday.minusDays(60), 9), dueAt = at(monday.minusDays(60), 10))
        val fromCompletion = Task(title = "Полить", dueAt = at(monday, 9), repeatRule = "FREQ=WEEKLY", repeatFrom = RepeatFrom.COMPLETION)
        val out = exportedEvents(listOf(call, lesson, done, old, fromCompletion), now, zone).associateBy { it.title }
        assertEquals(setOf("Позвонить", "Урок", "Полить"), out.keys)
        // A task with only a time is a half-hour block, one with a length keeps it.
        assertEquals(30 * 60_000L, out.getValue("Позвонить").end!! - out.getValue("Позвонить").start)
        assertEquals(90 * 60_000L, out.getValue("Урок").end!! - out.getValue("Урок").start)
        // Repeating from completion has no dates ahead: a single entry.
        assertNull(out.getValue("Полить").rrule)
    }

    @Test
    fun theFingerprintChangesWithTheEntry() {
        val t = Task(title = "Встреча", isEvent = true, startAt = at(monday, 9), dueAt = at(monday, 10))
        val a = exportedEvents(listOf(t), now, zone).single().fingerprint
        val b = exportedEvents(listOf(t.copy(dueAt = at(monday, 11))), now, zone).single().fingerprint
        assertTrue(a != b)
        assertEquals(a, exportedEvents(listOf(t.copy(color = 1)), now, zone).single().fingerprint)
    }
}
