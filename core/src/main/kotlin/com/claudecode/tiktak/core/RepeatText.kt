package com.claudecode.tiktak.core

import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ru = Locale("ru")

private val SHORT_DAYS = mapOf(
    DayOfWeek.MONDAY to "пн", DayOfWeek.TUESDAY to "вт", DayOfWeek.WEDNESDAY to "ср", DayOfWeek.THURSDAY to "чт",
    DayOfWeek.FRIDAY to "пт", DayOfWeek.SATURDAY to "сб", DayOfWeek.SUNDAY to "вс",
)

private val DAY_ACC = mapOf(
    DayOfWeek.MONDAY to "понедельник", DayOfWeek.TUESDAY to "вторник", DayOfWeek.WEDNESDAY to "среду",
    DayOfWeek.THURSDAY to "четверг", DayOfWeek.FRIDAY to "пятницу", DayOfWeek.SATURDAY to "субботу", DayOfWeek.SUNDAY to "воскресенье",
)

fun shortDayName(day: DayOfWeek): String = SHORT_DAYS.getValue(day)

fun plural(n: Int, one: String, few: String, many: String): String {
    val m100 = n % 100
    val m10 = n % 10
    return when {
        m100 in 11..14 -> many
        m10 == 1 -> one
        m10 in 2..4 -> few
        else -> many
    }
}

private fun ordinalName(n: Int): String = when (n) {
    -1 -> "последний"
    1 -> "первый"
    2 -> "второй"
    3 -> "третий"
    4 -> "четвёртый"
    else -> "$n-й"
}

/** Russian description, e.g. "Каждые 2 недели: пн, ср" or "Ежемесячно, последний день". */
fun RepeatRule.describe(): String {
    val base = when (freq) {
        Freq.DAILY -> if (interval == 1) "Каждый день" else "Каждые $interval ${plural(interval, "день", "дня", "дней")}"
        Freq.WEEKLY -> {
            val days = byDay.map { it.day }.sorted()
            when {
                interval == 1 && days == RepeatRule.WEEKDAYS -> "По будням"
                interval == 1 && days.size == 1 -> "Каждую неделю в ${DAY_ACC.getValue(days.single())}"
                else -> {
                    val head = if (interval == 1) "Каждую неделю" else "Каждые $interval ${plural(interval, "неделю", "недели", "недель")}"
                    if (days.isEmpty()) head else head + ": " + days.joinToString(", ") { SHORT_DAYS.getValue(it) }
                }
            }
        }
        Freq.MONTHLY -> {
            val head = if (interval == 1) "Ежемесячно" else "Каждые $interval ${plural(interval, "месяц", "месяца", "месяцев")}"
            val ordinal = byDay.firstOrNull { it.ordinal != 0 }
            when {
                ordinal != null -> "$head, ${ordinalName(ordinal.ordinal)} ${SHORT_DAYS.getValue(ordinal.day)}"
                byMonthDay == -1 -> "$head, последний день"
                byMonthDay != null -> "$head, $byMonthDay числа"
                else -> head
            }
        }
        Freq.YEARLY -> if (interval == 1) "Ежегодно" else "Каждые $interval ${plural(interval, "год", "года", "лет")}"
    }
    val tail = when {
        count != null -> ", ещё $count ${plural(count, "раз", "раза", "раз")}"
        until != null -> ", до " + until.format(DateTimeFormatter.ofPattern("d MMM yyyy", ru))
        else -> ""
    }
    return base + tail
}
