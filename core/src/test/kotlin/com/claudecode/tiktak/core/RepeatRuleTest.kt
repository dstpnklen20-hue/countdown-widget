package com.claudecode.tiktak.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate

class RepeatRuleTest {

    private fun d(s: String) = LocalDate.parse(s)
    private fun next(rule: String, from: String) = RepeatRule.parse(rule)!!.nextAfter(d(from))

    @Test
    fun daily() {
        assertEquals(d("2026-09-29"), next("FREQ=DAILY", "2026-09-28"))
        assertEquals(d("2026-10-01"), next("FREQ=DAILY;INTERVAL=3", "2026-09-28"))
    }

    @Test
    fun weeklyByDays() {
        // 2026-09-28 is a Monday.
        assertEquals(d("2026-09-30"), next("FREQ=WEEKLY;BYDAY=MO,WE,FR", "2026-09-28"))
        assertEquals(d("2026-10-05"), next("FREQ=WEEKLY;BYDAY=MO,WE,FR", "2026-10-02"))
        assertEquals(d("2026-10-12"), next("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,FR", "2026-10-02"))
        assertEquals(d("2026-10-05"), next("FREQ=WEEKLY", "2026-09-28"))
    }

    @Test
    fun weekdays() {
        assertEquals(d("2026-10-05"), next("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", "2026-10-02"))
    }

    @Test
    fun monthlyByDayClampsWithoutDrift() {
        assertEquals(d("2026-02-28"), next("FREQ=MONTHLY;BYMONTHDAY=31", "2026-01-31"))
        assertEquals(d("2026-03-31"), next("FREQ=MONTHLY;BYMONTHDAY=31", "2026-02-28"))
        assertEquals(d("2026-02-28"), next("FREQ=MONTHLY;BYMONTHDAY=-1", "2026-01-31"))
    }

    @Test
    fun monthlyByOrdinalWeekday() {
        // Second Monday of October 2026 is the 12th; last Friday of October is the 30th.
        assertEquals(d("2026-10-12"), next("FREQ=MONTHLY;BYDAY=2MO", "2026-09-28"))
        assertEquals(d("2026-10-30"), next("FREQ=MONTHLY;BYDAY=-1FR", "2026-10-01"))
        assertEquals(d("2026-11-27"), next("FREQ=MONTHLY;BYDAY=-1FR", "2026-10-30"))
    }

    @Test
    fun yearlyLeapDay() {
        assertEquals(d("2029-02-28"), next("FREQ=YEARLY", "2028-02-29"))
    }

    @Test
    fun countAndUntilStopTheSeries() {
        val rule = RepeatRule.parse("FREQ=DAILY;COUNT=2")!!
        assertEquals(d("2026-09-29"), rule.nextAfter(d("2026-09-28")))
        assertNull(rule.advanced().nextAfter(d("2026-09-29")))
        assertNull(next("FREQ=DAILY;UNTIL=20260930", "2026-09-30"))
        assertEquals(d("2026-09-30"), next("FREQ=DAILY;UNTIL=20260930T235959Z", "2026-09-29"))
    }

    @Test
    fun roundTrip() {
        for (s in listOf("FREQ=DAILY", "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,FR", "FREQ=MONTHLY;BYDAY=-1FR", "FREQ=MONTHLY;BYMONTHDAY=15;COUNT=3", "FREQ=YEARLY;UNTIL=20301231")) {
            assertEquals(s, RepeatRule.parse(s)!!.toRRule())
        }
    }

    @Test
    fun describeInRussian() {
        assertEquals("Каждый день", RepeatRule(Freq.DAILY).describe())
        assertEquals("Каждые 3 дня", RepeatRule(Freq.DAILY, 3).describe())
        assertEquals("По будням", RepeatRule.parse("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR")!!.describe())
        assertEquals("Каждую неделю в среду", RepeatRule(Freq.WEEKLY, byDay = listOf(WeekdayNum(WEDNESDAY))).describe())
        assertEquals("Каждые 2 недели: пн, пт", RepeatRule(Freq.WEEKLY, 2, listOf(WeekdayNum(FRIDAY), WeekdayNum(MONDAY))).describe())
        assertEquals("Ежемесячно, последний день", RepeatRule(Freq.MONTHLY, byMonthDay = -1).describe())
        assertEquals("Ежемесячно, второй пн", RepeatRule.parse("FREQ=MONTHLY;BYDAY=2MO")!!.describe())
        assertEquals("Ежегодно, ещё 5 раз", RepeatRule(Freq.YEARLY, count = 5).describe())
    }
}
