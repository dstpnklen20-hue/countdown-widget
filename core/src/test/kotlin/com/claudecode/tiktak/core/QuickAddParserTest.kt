package com.claudecode.tiktak.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.LocalDate
import java.time.LocalTime

class QuickAddParserTest {
    // Monday, 28 September 2026, 14:00.
    private val today = LocalDate.of(2026, 9, 28)
    private val parser = QuickAddParser(today, LocalTime.of(14, 0))
    private fun p(s: String) = parser.parse(s)
    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)

    @Test
    fun specExample() {
        val r = p("завтра в 10 позвонить !высокий #работа")
        assertEquals("позвонить", r.title)
        assertEquals(today.plusDays(1), r.date)
        assertEquals(t(10), r.time)
        assertEquals(3, r.priority)
        assertEquals(listOf("работа"), r.tags)
    }

    @Test
    fun eventWithLengthOrRange() {
        val r = p("Встреча завтра в 15:00 2 часа")
        assertEquals("Встреча", r.title)
        assertEquals(today.plusDays(1), r.date)
        assertEquals(t(15), r.time)
        assertEquals(120, r.durationMinutes)
        assertEquals(true, r.dateGiven)

        val range = p("Планёрка с 10 до 11:30")
        assertEquals("Планёрка", range.title)
        assertEquals(t(10), range.time)
        assertEquals(t(11, 30), range.endTime)
        assertEquals(false, range.dateGiven)

        val colon = p("Lecture 9:00-10:30 tomorrow")
        assertEquals("Lecture", colon.title)
        assertEquals(t(9), colon.time)
        assertEquals(t(10, 30), colon.endTime)

        assertEquals(30, p("Созвон в 16 на полчаса").durationMinutes)
        assertEquals(90, p("Тренировка полтора часа").durationMinutes)
        assertEquals(60, p("Обед на час").durationMinutes)
        assertEquals(45, p("Run for 45 min").durationMinutes)
        assertEquals(t(17, 0), p("с 5 до 7 вечера кино").time)
    }

    @Test
    fun hoursWordAfterTimeIsNotLeftInTitle() {
        val r = p("в 2 часа дня врач")
        assertEquals("врач", r.title)
        assertEquals(t(14), r.time)
        assertNull(r.durationMinutes)
        // "через 2 часа" is a moment, not a length.
        assertNull(p("через 2 часа позвонить").durationMinutes)
    }

    @Test
    fun englishExample() {
        val r = p("call mom tomorrow at 5pm !high #family ~Home")
        assertEquals("call mom", r.title)
        assertEquals(today.plusDays(1), r.date)
        assertEquals(t(17), r.time)
        assertEquals(3, r.priority)
        assertEquals(listOf("family"), r.tags)
        assertEquals("Home", r.listName)
    }

    @Test
    fun plainTitleIsUntouched() {
        val r = p("Купить 10 яиц и молоко")
        assertEquals("Купить 10 яиц и молоко", r.title)
        assertNull(r.date)
        assertNull(r.time)
        assertNull(r.priority)
    }

    @Test
    fun relativeDaysAndHours() {
        assertEquals(today.plusDays(3), p("через 3 дня отчёт").date)
        val inTwoHours = p("через 2 часа выключить духовку")
        assertEquals("выключить духовку", inTwoHours.title)
        assertEquals(today, inTwoHours.date)
        assertEquals(t(16), inTwoHours.time)
        assertEquals(t(15), p("in an hour stretch").time)
        assertEquals(today.plusWeeks(1), p("через неделю визит").date)
        assertEquals(today.plusDays(2), p("послезавтра встреча").date)
    }

    @Test
    fun weekdays() {
        assertEquals(LocalDate.of(2026, 10, 2), p("в пятницу сдать проект").date)
        assertEquals("сдать проект", p("в пятницу сдать проект").title)
        assertEquals(LocalDate.of(2026, 9, 30), p("meeting on wed").date)
        assertEquals(today, p("понедельник планёрка").date)
        assertEquals(LocalDate.of(2026, 10, 5), p("в следующий понедельник планёрка").date)
        // A short name without a preposition is just a word.
        assertEquals("sun cream", p("sun cream").title)
    }

    @Test
    fun absoluteDates() {
        assertEquals(LocalDate.of(2026, 10, 15), p("15 октября день рождения").date)
        assertEquals(LocalDate.of(2027, 3, 8), p("8 марта цветы").date)
        assertEquals(LocalDate.of(2026, 12, 31), p("31.12 ёлка").date)
        assertEquals(LocalDate.of(2027, 1, 5), p("dentist jan 5").date)
        assertEquals(LocalDate.of(2028, 2, 29), p("29.02.2028 високосный").date)
    }

    @Test
    fun times() {
        assertEquals(t(19), p("кино в 7 вечера").time)
        assertEquals(t(10, 30), p("созвон в 10:30").time)
        assertEquals(t(9, 15), p("зарядка 9:15").time)
        assertEquals(t(12), p("обед в полдень").time)
        assertEquals(t(9), p("утром пробежка").time)
        assertEquals("пробежка", p("утром пробежка").title)
        // Time already passed today -> tomorrow.
        assertEquals(today.plusDays(1), p("в 9 будильник").date)
        assertEquals(today, p("в 18 ужин").date)
        // "в 2026" is not a time.
        assertNull(p("отчёт в 2026").time)
    }

    @Test
    fun priorities() {
        assertEquals(3, p("срочно !!!").priority)
        assertEquals(2, p("задача !!").priority)
        assertEquals(1, p("задача !низкий").priority)
        assertEquals(2, p("task !2").priority)
        assertEquals("Ура!", p("Ура!").title)
    }

    @Test
    fun repeats() {
        val daily = p("каждый день медитация")
        assertEquals("медитация", daily.title)
        assertEquals(Freq.DAILY, daily.repeat?.freq)
        assertEquals(today, daily.date)

        val weekdays = p("стендап по будням в 10")
        assertEquals("стендап", weekdays.title)
        assertEquals(RepeatRule.WEEKDAYS, weekdays.repeat?.byDay?.map { it.day })
        assertEquals(t(10), weekdays.time)

        val tueThu = p("тренировка по вторникам и четвергам")
        assertEquals(listOf(TUESDAY, THURSDAY), tueThu.repeat?.byDay?.map { it.day })
        assertEquals(LocalDate.of(2026, 9, 29), tueThu.date)

        assertEquals(listOf(MONDAY), p("every monday review").repeat?.byDay?.map { it.day })
        val biweekly = p("каждые 2 недели уборка")
        assertEquals(Freq.WEEKLY, biweekly.repeat?.freq)
        assertEquals(2, biweekly.repeat?.interval)
        assertEquals(Freq.MONTHLY, p("оплатить интернет ежемесячно").repeat?.freq)
        assertEquals(Freq.YEARLY, p("renew passport yearly").repeat?.freq)
    }

    @Test
    fun tagsWithCyrillicAndSeveralTags() {
        val r = p("купить хлеб #дом #магазин")
        assertEquals("купить хлеб", r.title)
        assertEquals(listOf("дом", "магазин"), r.tags)
    }
}
