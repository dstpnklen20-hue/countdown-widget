package com.claudecode.countdown.domain

import com.claudecode.countdown.data.db.Task
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId

/** Entries that are not stored but shown read-only (holidays, birthdays) have ids with this prefix. */
const val VIRTUAL_ID_PREFIX = "virtual:"

val Task.isVirtual: Boolean get() = id.startsWith(VIRTUAL_ID_PREFIX)

const val HOLIDAY_COLOR = 0xFF0B8043.toInt()
const val BIRTHDAY_COLOR = 0xFFF4511E.toInt()

/** Russian public holidays (days off by the Labour Code, art. 112): first day, last day, name. */
private val RUSSIAN_HOLIDAYS = listOf(
    Triple(MonthDay.of(1, 1), MonthDay.of(1, 6), "Новогодние каникулы"),
    Triple(MonthDay.of(1, 7), MonthDay.of(1, 7), "Рождество Христово"),
    Triple(MonthDay.of(1, 8), MonthDay.of(1, 8), "Новогодние каникулы"),
    Triple(MonthDay.of(2, 23), MonthDay.of(2, 23), "День защитника Отечества"),
    Triple(MonthDay.of(3, 8), MonthDay.of(3, 8), "Международный женский день"),
    Triple(MonthDay.of(5, 1), MonthDay.of(5, 1), "Праздник Весны и Труда"),
    Triple(MonthDay.of(5, 9), MonthDay.of(5, 9), "День Победы"),
    Triple(MonthDay.of(6, 12), MonthDay.of(6, 12), "День России"),
    Triple(MonthDay.of(11, 4), MonthDay.of(11, 4), "День народного единства"),
)

/** The holidays as yearly all-day events from [fromYear] on (the calendar repeats them). */
fun russianHolidays(fromYear: Int, zone: ZoneId = ZoneId.systemDefault()): List<Task> =
    RUSSIAN_HOLIDAYS.map { (first, last, name) ->
        Task(
            id = "${VIRTUAL_ID_PREFIX}holiday:$first",
            title = name,
            isEvent = true,
            isAllDay = true,
            startAt = allDayDue(first.atYear(fromYear), zone).at,
            dueAt = allDayDue(last.atYear(fromYear), zone).at,
            timeZone = zone.id,
            repeatRule = "FREQ=YEARLY",
            color = HOLIDAY_COLOR,
            createdAt = 0,
        )
    }

/** A contact's birthday; [year] is null when the contact has none (as "--MM-DD" in contacts). */
data class Birthday(val name: String, val day: MonthDay, val year: Int?)

/** Birthdays as yearly all-day events from [fromYear] on; the title says how old the person turns. */
fun birthdayEvents(birthdays: List<Birthday>, fromYear: Int, zone: ZoneId = ZoneId.systemDefault()): List<Task> =
    birthdays.map { b ->
        // February 29 falls on the 28th in other years (atYear handles it).
        val date: LocalDate = b.day.atYear(fromYear)
        Task(
            id = "${VIRTUAL_ID_PREFIX}birthday:${b.name}:${b.day}",
            title = "День рождения: ${b.name}",
            content = b.year?.let { "Родился(-ась) в $it году" }.orEmpty(),
            isEvent = true,
            isAllDay = true,
            dueAt = allDayDue(date, zone).at,
            timeZone = zone.id,
            repeatRule = "FREQ=YEARLY",
            color = BIRTHDAY_COLOR,
            createdAt = 0,
        )
    }
