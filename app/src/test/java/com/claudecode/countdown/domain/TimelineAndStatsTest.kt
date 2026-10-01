package com.claudecode.countdown.domain

import com.claudecode.countdown.data.db.FocusSession
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class TimelineAndStatsTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val day = LocalDate.of(2026, 10, 1)
    private fun at(h: Int, m: Int = 0) = day.atTime(LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun separateBlocksKeepFullWidth() {
        val placed = layoutBlocks(listOf(TimeBlock("a", 540, 570), TimeBlock("b", 600, 630)))
        assertEquals(listOf(1, 1), placed.map { it.lanes })
        assertEquals(listOf(0, 0), placed.map { it.lane })
    }

    @Test
    fun overlappingBlocksShareTheWidth() {
        val placed = layoutBlocks(
            listOf(TimeBlock("a", 540, 600), TimeBlock("b", 560, 590), TimeBlock("c", 590, 620), TimeBlock("d", 700, 730))
        ).associateBy { it.item }
        // a, b, c chain into one group of two lanes; c reuses b's lane once b has ended.
        assertEquals(2, placed.getValue("a").lanes)
        assertEquals(0, placed.getValue("a").lane)
        assertEquals(1, placed.getValue("b").lane)
        assertEquals(1, placed.getValue("c").lane)
        assertEquals(1, placed.getValue("d").lanes)
    }

    @Test
    fun blockUsesStartTimeWhenThereIsOne() {
        assertEquals(600 to 630, blockMinutes(at(10), null, zone))
        assertEquals(540 to 600, blockMinutes(at(10), at(9), zone))
        // Late in the evening a default block stops at midnight.
        assertEquals(1430 to 1440, blockMinutes(at(23, 50), null, zone))
    }

    @Test
    fun countsAndMinutesPerDay() {
        val from = day.minusDays(2)
        assertEquals(listOf(0, 1, 2), countPerDay(listOf(at(9) - 86_400_000L, at(9), at(18)), from, 3, zone))
        val sessions = listOf(
            FocusSession(startedAt = at(9), endedAt = at(9, 25), durationMs = 25 * 60_000L),
            FocusSession(startedAt = at(11), endedAt = at(11, 25), durationMs = 25 * 60_000L),
        )
        assertEquals(listOf(0, 0, 50), focusMinutesPerDay(sessions, from, 3, zone))
        assertEquals("1 ч 5 мин", formatDuration(65 * 60_000L))
        assertEquals("2 ч", formatDuration(120 * 60_000L))
    }
}
