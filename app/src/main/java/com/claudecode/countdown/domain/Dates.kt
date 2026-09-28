package com.claudecode.countdown.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class Due(val at: Long, val isAllDay: Boolean, val timeZone: String)

fun allDayDue(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()) =
    Due(date.atStartOfDay(zone).toInstant().toEpochMilli(), true, zone.id)

fun timedDue(date: LocalDate, time: LocalTime, zone: ZoneId = ZoneId.systemDefault()) =
    Due(date.atTime(time).atZone(zone).toInstant().toEpochMilli(), false, zone.id)

fun today(zone: ZoneId = ZoneId.systemDefault()): LocalDate = LocalDate.now(zone)

fun localTimeOf(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalTime =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalTime()

fun parseQuickAdd(text: String): com.claudecode.tiktak.core.QuickAddResult =
    com.claudecode.tiktak.core.QuickAddParser(today(), LocalTime.now()).parse(text)
