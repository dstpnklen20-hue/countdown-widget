package com.claudecode.countdown.data

import com.claudecode.countdown.data.TaskRepository.ReminderSpec
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.data.db.TaskStatus
import com.claudecode.countdown.domain.allDayDue
import com.claudecode.countdown.domain.dueDay
import com.claudecode.countdown.domain.skippedDays
import com.claudecode.tiktak.core.RepeatRule
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** One VEVENT or VTODO read from a file: the entry and its reminders (minutes before). */
data class IcsItem(val task: Task, val reminders: List<Int>)

/**
 * iCalendar (RFC 5545) files: import events and tasks from other calendars, export ours. Covers
 * what the app stores: title, description, place, start/end or due, all-day, RRULE, EXDATE,
 * VALARM triggers and the done state of tasks.
 */
object Ics {
    private val DATE = DateTimeFormatter.BASIC_ISO_DATE
    private val LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /** Reads the file and stores what is in it; an entry seen before (same UID) is updated. Returns how many. */
    suspend fun import(repo: TaskRepository, text: String, calendarId: String?): Int {
        val items = parse(text)
        for (item in items) {
            val task = item.task.copy(calendarId = if (item.task.isEvent) calendarId else null)
            val existing = repo.get(task.id)
            val specs = item.reminders.map { ReminderSpec(it) }
            if (task.isEvent) {
                repo.saveEvent(if (existing != null) task.copy(createdAt = existing.createdAt, updatedAt = existing.updatedAt) else task, specs, existing)
            } else if (existing == null) {
                repo.create(task)
                for (m in item.reminders) repo.addReminder(task.id, m)
            } else {
                repo.update(task.copy(createdAt = existing.createdAt, sortOrder = existing.sortOrder))
            }
        }
        return items.size
    }

    suspend fun export(repo: TaskRepository, tasks: List<Task>): String =
        write(tasks.filter { !it.deleted && it.dueAt != null }.map { t -> IcsItem(t, repo.remindersOf(t.id).mapNotNull { it.offsetMinutes }) })

    // --- Reading ---

    private class Prop(val name: String, val params: Map<String, String>, val value: String)

    fun parse(text: String, zone: ZoneId = ZoneId.systemDefault()): List<IcsItem> {
        // Long lines are folded: a line starting with a space or tab continues the previous one.
        val lines = mutableListOf<String>()
        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && lines.isNotEmpty()) lines[lines.lastIndex] += raw.substring(1)
            else if (raw.isNotBlank()) lines += raw
        }
        val out = mutableListOf<IcsItem>()
        var props: MutableList<Prop>? = null
        var alarms = mutableListOf<Int>()
        var inAlarm = false
        var kind = ""
        for (line in lines) {
            val prop = parseLine(line) ?: continue
            when {
                prop.name == "BEGIN" && (prop.value == "VEVENT" || prop.value == "VTODO") -> {
                    props = mutableListOf(); alarms = mutableListOf(); kind = prop.value
                }
                prop.name == "BEGIN" && prop.value == "VALARM" -> inAlarm = true
                prop.name == "END" && prop.value == "VALARM" -> inAlarm = false
                prop.name == "END" && (prop.value == "VEVENT" || prop.value == "VTODO") -> {
                    props?.let { p -> toItem(p, alarms, kind == "VEVENT", zone)?.let(out::add) }
                    props = null
                }
                inAlarm -> if (prop.name == "TRIGGER") triggerMinutes(prop.value)?.let(alarms::add)
                else -> props?.add(prop)
            }
        }
        return out
    }

    private fun parseLine(line: String): Prop? {
        // NAME;PARAM=V;PARAM=V:VALUE — the value may contain ':' itself.
        var quoted = false
        var colon = -1
        for ((i, c) in line.withIndex()) {
            if (c == '"') quoted = !quoted
            if (c == ':' && !quoted) { colon = i; break }
        }
        if (colon < 0) return null
        val head = line.substring(0, colon).split(';')
        val params = head.drop(1).mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].uppercase() to it[1].trim('"') } }.toMap()
        return Prop(head[0].uppercase(), params, line.substring(colon + 1))
    }

    private fun unescape(s: String) = s.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    private class When(val at: Long, val allDay: Boolean, val date: LocalDate)

    private fun parseWhen(p: Prop, zone: ZoneId): When? = runCatching {
        val v = p.value.trim()
        if (p.params["VALUE"] == "DATE" || v.length == 8) {
            val d = LocalDate.parse(v.take(8), DATE)
            When(allDayDue(d, zone).at, true, d)
        } else {
            val local = LocalDateTime.parse(v.take(15), LOCAL)
            val z = when {
                v.endsWith("Z") -> ZoneOffset.UTC
                p.params["TZID"] != null -> runCatching { ZoneId.of(p.params["TZID"]) }.getOrDefault(zone)
                else -> zone
            }
            val at = local.atZone(z).toInstant()
            When(at.toEpochMilli(), false, at.atZone(zone).toLocalDate())
        }
    }.getOrNull()

    /** "-PT15M", "-P1D", "-PT1H30M", "PT0S" → minutes before the start. */
    fun triggerMinutes(value: String): Int? {
        val m = Regex("""^(-)?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$""").find(value.trim()) ?: return null
        val (sign, w, d, h, min) = m.destructured
        val total = (w.toIntOrNull() ?: 0) * 7 * 1440 + (d.toIntOrNull() ?: 0) * 1440 + (h.toIntOrNull() ?: 0) * 60 + (min.toIntOrNull() ?: 0)
        return if (sign == "-" || total == 0) total else null
    }

    private fun toItem(props: List<Prop>, alarms: List<Int>, event: Boolean, zone: ZoneId): IcsItem? {
        fun one(name: String) = props.firstOrNull { it.name == name }
        val uid = one("UID")?.value ?: UUID.randomUUID().toString()
        val title = one("SUMMARY")?.value?.let(::unescape)?.trim().orEmpty().ifEmpty { "(Без названия)" }
        val id = UUID.nameUUIDFromBytes("ics:$uid".toByteArray()).toString()
        val rule = one("RRULE")?.value?.let { RepeatRule.parse(it)?.toRRule() }
        val content = one("DESCRIPTION")?.value?.let(::unescape).orEmpty()
        val location = one("LOCATION")?.value?.let(::unescape)?.trim()?.ifEmpty { null }
        if (!event) {
            val due = one("DUE")?.let { parseWhen(it, zone) } ?: one("DTSTART")?.let { parseWhen(it, zone) }
            val done = one("STATUS")?.value == "COMPLETED"
            return IcsItem(
                Task(
                    id = id, title = title, content = content, dueAt = due?.at, isAllDay = due?.allDay ?: false,
                    timeZone = zone.id, repeatRule = rule, status = if (done) TaskStatus.DONE else TaskStatus.OPEN,
                    completedAt = if (done) System.currentTimeMillis() else null,
                ),
                alarms,
            )
        }
        val start = one("DTSTART")?.let { parseWhen(it, zone) } ?: return null
        var end = one("DTEND")?.let { parseWhen(it, zone) }
        if (end == null) {
            val minutes = one("DURATION")?.value?.removePrefix("+")?.let { triggerMinutes("-$it") }
            end = when {
                minutes != null && start.allDay -> When(allDayDue(start.date.plusDays((minutes / 1440).toLong()), zone).at, true, start.date.plusDays((minutes / 1440).toLong()))
                minutes != null -> When(start.at + minutes * 60_000L, false, start.date)
                start.allDay -> When(allDayDue(start.date.plusDays(1), zone).at, true, start.date.plusDays(1))
                else -> start
            }
        }
        val exDates = props.filter { it.name == "EXDATE" }.flatMap { p ->
            p.value.split(',').mapNotNull { v -> parseWhen(Prop("EXDATE", p.params, v), zone)?.date }
        }
        val task = if (start.allDay) {
            // The end of an all-day event is the day after its last day.
            val last = maxOf(start.date, end.date.minusDays(1))
            Task(id = id, title = title, isEvent = true, isAllDay = true, startAt = start.at, dueAt = allDayDue(last, zone).at, timeZone = zone.id)
        } else {
            Task(id = id, title = title, isEvent = true, startAt = start.at, dueAt = maxOf(end.at, start.at), timeZone = zone.id)
        }
        return IcsItem(
            task.copy(
                content = content, location = location, repeatRule = rule,
                exDates = exDates.distinct().sorted().joinToString(",").ifEmpty { null },
            ),
            alarms,
        )
    }

    // --- Writing ---

    private fun escape(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")

    /** Lines longer than 75 characters are folded, as the format asks. */
    private fun StringBuilder.line(text: String) {
        var rest = text
        var first = true
        while (rest.isNotEmpty()) {
            val take = if (first) 75 else 74
            if (!first) append(' ')
            append(rest.take(take)).append("\r\n")
            rest = rest.drop(take)
            first = false
        }
    }

    private fun utc(at: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneOffset.UTC).format(LOCAL) + "Z"

    fun write(items: List<IcsItem>, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        line("BEGIN:VCALENDAR")
        line("VERSION:2.0")
        line("PRODID:-//Tik Tak//RU")
        line("CALSCALE:GREGORIAN")
        val stamp = utc(System.currentTimeMillis())
        for ((t, reminders) in items) {
            val due = t.dueAt ?: continue
            val kind = if (t.isEvent || t.displayMode == com.claudecode.countdown.data.db.DisplayMode.COUNTDOWN) "VEVENT" else "VTODO"
            line("BEGIN:$kind")
            line("UID:${t.id}@tiktak")
            line("DTSTAMP:$stamp")
            line("SUMMARY:${escape(t.title)}")
            if (kind == "VEVENT") {
                val start = t.startAt?.takeIf { it <= due } ?: due
                if (t.isAllDay) {
                    val first = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
                    val last = t.dueDay(zone) ?: first
                    line("DTSTART;VALUE=DATE:${first.format(DATE)}")
                    line("DTEND;VALUE=DATE:${last.plusDays(1).format(DATE)}")
                } else {
                    line("DTSTART:${utc(start)}")
                    line("DTEND:${utc(if (due > start) due else start + 30 * 60_000L)}")
                }
            } else {
                line(if (t.isAllDay) "DUE;VALUE=DATE:${(t.dueDay(zone) ?: LocalDate.now()).format(DATE)}" else "DUE:${utc(due)}")
                line("STATUS:" + if (t.isDone) "COMPLETED" else "NEEDS-ACTION")
            }
            t.repeatRule?.let { line("RRULE:$it") }
            val skipped = t.skippedDays()
            if (skipped.isNotEmpty()) {
                if (t.isAllDay) line("EXDATE;VALUE=DATE:" + skipped.sorted().joinToString(",") { it.format(DATE) })
                else {
                    val time = Instant.ofEpochMilli(t.startAt?.takeIf { it <= due } ?: due).atZone(zone).toLocalTime()
                    val dueDay = t.dueDay(zone)
                    val startDay = Instant.ofEpochMilli(t.startAt?.takeIf { it <= due } ?: due).atZone(zone).toLocalDate()
                    val lead = if (dueDay != null) java.time.temporal.ChronoUnit.DAYS.between(startDay, dueDay) else 0
                    line("EXDATE:" + skipped.sorted().joinToString(",") { utc(it.minusDays(lead).atTime(time).atZone(zone).toInstant().toEpochMilli()) })
                }
            }
            t.location?.let { line("LOCATION:${escape(it)}") }
            if (t.content.isNotBlank()) line("DESCRIPTION:${escape(t.content)}")
            for (m in reminders) {
                line("BEGIN:VALARM")
                line("ACTION:DISPLAY")
                line("DESCRIPTION:${escape(t.title)}")
                line("TRIGGER:" + if (m == 0) "PT0S" else "-PT${m}M")
                line("END:VALARM")
            }
            line("END:$kind")
        }
        line("END:VCALENDAR")
    }
}
