package com.claudecode.countdown.domain

import com.claudecode.countdown.data.AppZone
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.ui.EventTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone

class TimeZonesTest {
    private val original = TimeZone.getDefault()

    @After
    fun restore() = TimeZone.setDefault(original)

    @Test
    fun flightKeepsLocalTimesOfBothEnds() {
        val day = LocalDate.of(2026, 10, 5)
        // Departure 10:00 in Moscow, arrival 14:00 in Dubai: 3 hours in the air.
        val time = EventTime(day, LocalTime.of(10, 0), day, LocalTime.of(14, 0), allDay = false, startZone = "Europe/Moscow", endZone = "Asia/Dubai")
        val task = time.applyTo(Task(title = "Рейс", isEvent = true))
        assertEquals(Duration.ofHours(3), Duration.ofMillis(task.dueAt!! - task.startAt!!))
        assertEquals("Asia/Dubai", task.endZone)
        // Read back, it shows the same clock times in the same zones.
        val back = EventTime.of(task)
        assertEquals(LocalTime.of(10, 0), back.start)
        assertEquals(LocalTime.of(14, 0), back.end)
        // Moving the departure keeps the 3 hours of flight.
        val later = back.withStart(day, LocalTime.of(12, 0))
        assertEquals(LocalTime.of(16, 0), later.end)
    }

    @Test
    fun appZoneIsUsedEverywhereAndCanFollowThePhoneAgain() {
        AppZone.apply("Asia/Vladivostok")
        assertEquals(ZoneId.of("Asia/Vladivostok"), ZoneId.systemDefault())
        // An event without zones of its own follows the app's zone.
        val day = LocalDate.of(2026, 10, 5)
        val task = EventTime.at(day, LocalTime.of(9, 0)).applyTo(Task(title = "Планёрка", isEvent = true))
        assertEquals(LocalTime.of(9, 0), localTimeOf(task.startAt!!, ZoneId.of("Asia/Vladivostok")))
        AppZone.apply(null)
        assertEquals(AppZone.device(), ZoneId.systemDefault())
    }
}
