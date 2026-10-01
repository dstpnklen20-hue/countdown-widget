package com.claudecode.countdown.domain

import com.claudecode.countdown.data.db.FocusSession
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Days [from] .. [from] + [days] - 1, oldest first. */
fun dayRange(from: LocalDate, days: Int): List<LocalDate> = List(days) { from.plusDays(it.toLong()) }

/** How many of [moments] (epoch millis) fall on each day of the range. */
fun countPerDay(moments: List<Long>, from: LocalDate, days: Int, zone: ZoneId = ZoneId.systemDefault()): List<Int> {
    val counts = IntArray(days)
    for (m in moments) {
        val i = Instant.ofEpochMilli(m).atZone(zone).toLocalDate().toEpochDay() - from.toEpochDay()
        if (i in 0 until days) counts[i.toInt()]++
    }
    return counts.toList()
}

/** Whole minutes of focus per day of the range, by the day each session started. */
fun focusMinutesPerDay(sessions: List<FocusSession>, from: LocalDate, days: Int, zone: ZoneId = ZoneId.systemDefault()): List<Int> {
    val ms = LongArray(days)
    for (s in sessions) {
        val i = Instant.ofEpochMilli(s.startedAt).atZone(zone).toLocalDate().toEpochDay() - from.toEpochDay()
        if (i in 0 until days) ms[i.toInt()] += s.durationMs
    }
    return ms.map { (it / 60_000).toInt() }
}

/** "1 ч 25 мин", "40 мин". */
fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 60 -> "$minutes мин"
        minutes % 60 == 0L -> "${minutes / 60} ч"
        else -> "${minutes / 60} ч ${minutes % 60} мин"
    }
}

/** Months [from] .. [to] inclusive, oldest first. */
fun monthRange(from: YearMonth, to: YearMonth): List<YearMonth> =
    generateSequence(from) { it.plusMonths(1) }.takeWhile { it <= to }.toList()

/** How many of [moments] fall in each of [months]. */
fun countPerMonth(moments: List<Long>, months: List<YearMonth>, zone: ZoneId = ZoneId.systemDefault()): List<Int> {
    val index = months.withIndex().associate { (i, m) -> m to i }
    val counts = IntArray(months.size)
    for (m in moments) index[YearMonth.from(Instant.ofEpochMilli(m).atZone(zone))]?.let { counts[it]++ }
    return counts.toList()
}

/** Whole minutes of focus in each of [months], by the day each session started. */
fun focusMinutesPerMonth(sessions: List<FocusSession>, months: List<YearMonth>, zone: ZoneId = ZoneId.systemDefault()): List<Int> {
    val index = months.withIndex().associate { (i, m) -> m to i }
    val ms = LongArray(months.size)
    for (s in sessions) index[YearMonth.from(Instant.ofEpochMilli(s.startedAt).atZone(zone))]?.let { ms[it] += s.durationMs }
    return ms.map { (it / 60_000).toInt() }
}
