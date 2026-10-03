package com.claudecode.countdown.domain

import com.claudecode.countdown.data.ContactBirthdays
import com.claudecode.countdown.data.Ics
import com.claudecode.countdown.data.IcsItem
import com.claudecode.countdown.data.db.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay
import java.time.ZoneId

class CalendarFeaturesTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val day = LocalDate.of(2026, 10, 5)

    private fun event(from: Int, to: Int, rule: String? = null) = Task(
        title = "Встреча", isEvent = true, repeatRule = rule,
        startAt = timedDue(day, LocalTime.of(from, 0), zone).at, dueAt = timedDue(day, LocalTime.of(to, 0), zone).at,
    )

    @Test
    fun icsWithTimezonesAllDayRepeatsAndAlarmsIsRead() {
        val text = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:abc-1
            SUMMARY:Планёрка\, важная
            DTSTART;TZID=Europe/Moscow:20261005T090000
            DTEND;TZID=Europe/Moscow:20261005T093000
            RRULE:FREQ=WEEKLY;BYDAY=MO;UNTIL=20261231T000000Z
            EXDATE;TZID=Europe/Moscow:20261012T090000
            LOCATION:Офис\, 3 этаж
            DESCRIPTION:Первая строка\nвторая
            BEGIN:VALARM
            TRIGGER:-PT15M
            END:VALARM
            END:VEVENT
            BEGIN:VEVENT
            UID:abc-2
            SUMMARY:Отпуск
            DTSTART;VALUE=DATE:20261101
            DTEND;VALUE=DATE:20261108
            END:VEVENT
            BEGIN:VTODO
            UID:abc-3
            SUMMARY:Купить билеты
            DUE;VALUE=DATE:20261020
            STATUS:COMPLETED
            END:VTODO
            END:VCALENDAR
        """.trimIndent()
        val items = Ics.parse(text, zone)
        assertEquals(3, items.size)
        val (meeting, alarms) = items[0]
        assertEquals("Планёрка, важная", meeting.title)
        assertEquals("Офис, 3 этаж", meeting.location)
        assertEquals("Первая строка\nвторая", meeting.content)
        assertEquals(LocalTime.of(9, 0), localTimeOf(meeting.startAt!!, zone))
        assertEquals(LocalTime.of(9, 30), localTimeOf(meeting.dueAt!!, zone))
        assertEquals("2026-10-12", meeting.exDates)
        assertEquals(listOf(15), alarms)
        assertTrue(meeting.repeatRule!!.contains("UNTIL=20261231"))
        val vacation = items[1].task
        assertTrue(vacation.isAllDay)
        // DTEND of an all-day event is the day after its last day.
        assertEquals(LocalDate.of(2026, 11, 7), vacation.dueDay(zone))
        val todo = items[2].task
        assertTrue(!todo.isEvent && todo.isDone)
        // The same UID gives the same id, so importing twice updates instead of duplicating.
        assertEquals(meeting.id, Ics.parse(text, zone)[0].task.id)
    }

    @Test
    fun writtenIcsReadsBackTheSame() {
        val original = event(14, 15, "FREQ=DAILY;COUNT=5").copy(location = "Кафе; у окна", content = "Взять ноутбук", exDates = "2026-10-07")
        val text = Ics.write(listOf(IcsItem(original, listOf(30))), zone)
        val back = Ics.parse(text, zone).single()
        assertEquals(original.title, back.task.title)
        assertEquals(original.location, back.task.location)
        assertEquals(original.startAt, back.task.startAt)
        assertEquals(original.dueAt, back.task.dueAt)
        assertEquals(original.repeatRule, back.task.repeatRule)
        assertEquals("2026-10-07", back.task.exDates)
        assertEquals(listOf(30), back.reminders)
    }

    @Test
    fun draggingMovesAndStretchingChangesTheEnd() {
        val e = event(9, 10)
        val moved = e.shifted(day, 90, 90, zone)
        assertEquals(LocalTime.of(10, 30), localTimeOf(moved.startAt!!, zone))
        assertEquals(LocalTime.of(11, 30), localTimeOf(moved.dueAt!!, zone))
        val longer = e.shifted(day, 0, 45, zone)
        assertEquals(LocalTime.of(9, 0), localTimeOf(longer.startAt!!, zone))
        assertEquals(LocalTime.of(10, 45), localTimeOf(longer.dueAt!!, zone))
        // A task with only a time: moving keeps it a point in time, stretching gives it a start.
        val task = Task(title = "Позвонить", dueAt = timedDue(day, LocalTime.of(12, 0), zone).at)
        assertNull(task.shifted(day, 60, 60, zone).startAt)
        val stretched = task.shifted(day, 0, 30, zone)
        assertEquals(LocalTime.of(12, 0), localTimeOf(stretched.startAt!!, zone))
        assertEquals(LocalTime.of(13, 0), localTimeOf(stretched.dueAt!!, zone))
        // A later occurrence of a series moves from its own day.
        val series = event(9, 10, "FREQ=DAILY")
        assertEquals(day.plusDays(3), series.shifted(day.plusDays(3), 60, 60, zone).dueDay(zone))
    }

    @Test
    fun calendarSearchLooksAtPlacesAndLeavesOutWords() {
        val today = day
        val tasks = listOf(
            event(9, 10).copy(title = "Обед", location = "Кафе у дома"),
            event(12, 13).copy(title = "Обед с командой", location = "Кафе в офисе"),
            Task(title = "Кафе: отзыв", dueAt = allDayDue(day.minusDays(3)).at, isAllDay = true),
        )
        assertEquals(3, searchCalendar(tasks, CalendarQuery("кафе"), today).size)
        assertEquals(listOf("Обед"), searchCalendar(tasks, CalendarQuery("кафе", without = "команд", kind = SearchKind.EVENTS), today).map { it.title })
        assertEquals(listOf("Кафе: отзыв"), searchCalendar(tasks, CalendarQuery("кафе", period = SearchPeriod.PAST), today).map { it.title })
    }

    @Test
    fun holidaysAndBirthdaysRepeatEveryYear() {
        val entries = calendarEntries(russianHolidays(2024), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
        assertTrue(entries.getValue(LocalDate.of(2026, 5, 9)).any { it.task.title == "День Победы" })
        assertEquals(6, (1..6).count { entries.containsKey(LocalDate.of(2026, 1, it)) })
        val b = ContactBirthdays.parse("Аня", "--03-15")!!
        assertEquals(MonthDay.of(3, 15), b.day)
        assertNull(b.year)
        assertEquals(1990, ContactBirthdays.parse("Петя", "1990-07-01")!!.year)
        val bd = calendarEntries(birthdayEvents(listOf(b), 2024), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31))
        assertTrue(bd.containsKey(LocalDate.of(2026, 3, 15)))
    }

    @Test
    fun quietHoursMayRunOverMidnight() {
        val s = com.claudecode.countdown.data.Settings(quietStart = 23 * 60, quietEnd = 7 * 60)
        assertTrue(s.isQuiet(23 * 60 + 30))
        assertTrue(s.isQuiet(3 * 60))
        assertTrue(!s.isQuiet(12 * 60))
    }
}
