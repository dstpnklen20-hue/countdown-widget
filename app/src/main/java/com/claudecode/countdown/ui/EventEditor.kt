package com.claudecode.countdown.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.data.db.TaskList
import com.claudecode.countdown.domain.allDayDue
import com.claudecode.countdown.domain.localDateOf
import com.claudecode.countdown.domain.localTimeOf
import com.claudecode.countdown.domain.timedDue
import com.claudecode.countdown.domain.dueDay
import kotlinx.coroutines.android.awaitFrame
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val ru = Locale("ru")
private val dateLabel = DateTimeFormatter.ofPattern("EE, d MMM", ru)
private val dateYearLabel = DateTimeFormatter.ofPattern("EE, d MMM yyyy", ru)

/** When an event happens: from a start to an end (dates and times), or over whole days. */
data class EventTime(
    val startDate: LocalDate,
    val start: LocalTime,
    val endDate: LocalDate,
    val end: LocalTime,
    val allDay: Boolean,
) {
    private val from: LocalDateTime get() = startDate.atTime(start)
    private val to: LocalDateTime get() = endDate.atTime(end)

    /** Moving the start keeps the length, like calendar apps do. */
    fun withStart(date: LocalDate, time: LocalTime): EventTime {
        val length = Duration.between(from, to).coerceAtLeast(Duration.ZERO)
        val newFrom = date.atTime(time)
        val newTo = newFrom.plus(length)
        return copy(startDate = date, start = time, endDate = newTo.toLocalDate(), end = newTo.toLocalTime())
    }

    /** An end time before the start on the same day means the next morning (sleep 23:00–6:00). */
    fun withEnd(date: LocalDate, time: LocalTime): EventTime {
        var newTo = date.atTime(time)
        if (newTo <= from && date == startDate && !allDay) newTo = newTo.plusDays(1)
        if (newTo < from) newTo = from
        return copy(endDate = newTo.toLocalDate(), end = newTo.toLocalTime())
    }

    fun applyTo(task: Task, zone: ZoneId = ZoneId.systemDefault()): Task =
        if (allDay) {
            val last = if (endDate < startDate) startDate else endDate
            task.copy(startAt = allDayDue(startDate, zone).at, dueAt = allDayDue(last, zone).at, isAllDay = true, timeZone = zone.id)
        } else {
            task.copy(startAt = timedDue(startDate, start, zone).at, dueAt = timedDue(endDate, end, zone).at, isAllDay = false, timeZone = zone.id)
        }

    companion object {
        /** An hour from [time] (9:00 when there is none). */
        fun at(date: LocalDate, time: LocalTime?): EventTime {
            val from = date.atTime(time ?: LocalTime.of(9, 0))
            val to = from.plusHours(1)
            return EventTime(date, from.toLocalTime(), to.toLocalDate(), to.toLocalTime(), allDay = false)
        }

        fun of(task: Task, zone: ZoneId = ZoneId.systemDefault()): EventTime {
            val due = task.dueAt ?: return at(LocalDate.now(zone), null)
            if (task.isAllDay) {
                val last = task.dueDay(zone) ?: localDateOf(due, zone)
                val first = task.startAt?.let { task.copy(dueAt = it).dueDay(zone) }?.takeIf { it <= last } ?: last
                return EventTime(first, LocalTime.of(9, 0), last, LocalTime.of(10, 0), allDay = true)
            }
            val start = task.startAt?.takeIf { it <= due } ?: due
            return EventTime(localDateOf(start, zone), localTimeOf(start, zone), localDateOf(due, zone), localTimeOf(due, zone), allDay = false)
        }
    }
}

fun formatPickerDate(date: LocalDate, today: LocalDate = LocalDate.now()): String =
    date.format(if (date.year == today.year) dateLabel else dateYearLabel).replaceFirstChar { it.uppercase() }

fun formatClock(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

/** Ready-made repeats for a new entry on [date]: label and RRULE (null for none). */
fun repeatPresets(date: LocalDate): List<Pair<String, String?>> {
    val day = date.dayOfWeek.name.take(2)
    val weekday = date.dayOfWeek.getDisplayName(TextStyle.FULL, ru)
    return listOf(
        "Не повторять" to null,
        "Каждый день" to "FREQ=DAILY",
        "По будням (пн–пт)" to "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR",
        "Каждую неделю ($weekday)" to "FREQ=WEEKLY;BYDAY=$day",
        "Каждый месяц (${date.dayOfMonth}-го)" to "FREQ=MONTHLY;BYMONTHDAY=${date.dayOfMonth}",
        monthlyByWeekday(date),
        "Каждый год" to "FREQ=YEARLY",
    ) + listOfNotNull(
        ("Каждый месяц в последний день" to "FREQ=MONTHLY;BYMONTHDAY=-1").takeIf { date.dayOfMonth == date.lengthOfMonth() },
    )
}

/**
 * "Каждый месяц во 2-ю субботу", or "в последнюю субботу" when this is the month's last one
 * (as Google Calendar offers for 31 October).
 */
private fun monthlyByWeekday(date: LocalDate): Pair<String, String> {
    val last = date.dayOfMonth + 7 > date.lengthOfMonth()
    val n = (date.dayOfMonth - 1) / 7 + 1
    // Accusative form and the gender ending of the ordinal for each weekday.
    val (name, ending) = when (date.dayOfWeek) {
        java.time.DayOfWeek.MONDAY -> "понедельник" to "й"
        java.time.DayOfWeek.TUESDAY -> "вторник" to "й"
        java.time.DayOfWeek.WEDNESDAY -> "среду" to "ю"
        java.time.DayOfWeek.THURSDAY -> "четверг" to "й"
        java.time.DayOfWeek.FRIDAY -> "пятницу" to "ю"
        java.time.DayOfWeek.SATURDAY -> "субботу" to "ю"
        java.time.DayOfWeek.SUNDAY -> "воскресенье" to "е"
    }
    val code = date.dayOfWeek.name.take(2)
    return if (last) {
        val word = when (ending) { "й" -> "последний"; "ю" -> "последнюю"; else -> "последнее" }
        "Каждый месяц в $word $name" to "FREQ=MONTHLY;BYDAY=-1$code"
    } else {
        // "во 2-ю": the ordinal "второй" takes "во".
        "Каждый месяц ${if (n == 2) "во" else "в"} $n-$ending $name" to "FREQ=MONTHLY;BYDAY=$n$code"
    }
}

/** Start and end rows with date and time buttons, and the "all day" switch. */
@Composable
fun EventTimeFields(value: EventTime, onChange: (EventTime) -> Unit) {
    // Which picker is open: "startDate", "startTime", "endDate", "endTime".
    var picking by remember { mutableStateOf<String?>(null) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Весь день", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = value.allDay, onCheckedChange = { onChange(value.copy(allDay = it)) })
        }
        TimeRow("Начало", value.startDate, value.start.takeIf { !value.allDay }, { picking = "startDate" }, { picking = "startTime" })
        TimeRow("Конец", value.endDate, value.end.takeIf { !value.allDay }, { picking = "endDate" }, { picking = "endTime" })
    }
    when (picking) {
        "startDate" -> PickDate(value.startDate, { onChange(value.withStart(it, value.start)); picking = null }) { picking = null }
        "endDate" -> PickDate(value.endDate, { onChange(value.withEnd(it, value.end)); picking = null }) { picking = null }
        "startTime" -> PickTime(value.start, { onChange(value.withStart(value.startDate, it)); picking = null }) { picking = null }
        "endTime" -> PickTime(value.end, { onChange(value.withEnd(endDateFor(value), it)); picking = null }) { picking = null }
    }
}

@Composable
private fun TimeRow(label: String, date: LocalDate, time: LocalTime?, onDate: () -> Unit, onTime: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(64.dp))
        TextButton(onClick = onDate) { Text(formatPickerDate(date)) }
        Spacer(Modifier.weight(1f))
        if (time != null) TextButton(onClick = onTime) { Text(formatClock(time)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickDate(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onPick(state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: initial)
            }) { Text("Готово") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    ) {
        DatePicker(state = state, title = null, headline = null, showModeToggle = false)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickTime(initial: LocalTime, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        text = { TimePicker(state = state) },
    )
}

/** Changes when an existing event happens (from its details screen). */
@Composable
fun EventTimeDialog(task: Task, onConfirm: (EventTime) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(EventTime.of(task)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Время события") },
        text = { EventTimeFields(value) { value = it } },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("Готово") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/**
 * A new calendar entry: an event (sleep, lunch: takes time, nothing to tick) or a task (something
 * to do, optionally at a time). [onCreate] gets the new entry and whether it should remind.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewEntryDialog(
    date: LocalDate,
    time: LocalTime?,
    event: Boolean,
    onCreate: (Task, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var isEvent by remember { mutableStateOf(event) }
    var title by remember { mutableStateOf("") }
    var span by remember { mutableStateOf(EventTime.at(date, time)) }
    var taskDate by remember { mutableStateOf(date) }
    var taskTime by remember { mutableStateOf(time) }
    var repeat by remember { mutableStateOf<String?>(null) }
    var color by remember { mutableStateOf<Int?>(null) }
    var repeatMenu by remember { mutableStateOf(false) }
    var pickTaskDate by remember { mutableStateOf(false) }
    var pickTaskTime by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        awaitFrame()
        runCatching { focus.requestFocus() }
    }

    fun create() {
        if (title.isBlank()) return
        val base = Task(title = title.trim(), listId = TaskList.INBOX_ID, repeatRule = repeat, color = color)
        if (isEvent) {
            onCreate(span.applyTo(base.copy(isEvent = true)), false)
        } else {
            val due = taskTime?.let { timedDue(taskDate, it) } ?: allDayDue(taskDate)
            onCreate(base.copy(dueAt = due.at, isAllDay = due.isAllDay, timeZone = due.timeZone), taskTime != null)
        }
    }

    val anchor = if (isEvent) span.startDate else taskDate
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(true to "Событие", false to "Задача").forEachIndexed { i, (value, label) ->
                        SegmentedButton(
                            selected = isEvent == value,
                            onClick = { isEvent = value },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                            icon = {},
                        ) { Text(label) }
                    }
                }
                Text(
                    if (isEvent) "Занимает время в календаре: сон, обед, пара. Без галочки и не в списках задач."
                    else "То, что нужно сделать: с галочкой, в списках задач и в календаре.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Название") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (isEvent) {
                    EventTimeFields(span) { span = it }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Дата", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(64.dp))
                        TextButton(onClick = { pickTaskDate = true }) { Text(formatPickerDate(taskDate)) }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { pickTaskTime = true }) { Text(taskTime?.let(::formatClock) ?: "Без времени") }
                    }
                }
                Box {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Повтор", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(64.dp))
                        TextButton(onClick = { repeatMenu = true }) {
                            Text(repeatPresets(anchor).firstOrNull { it.second == repeat }?.first ?: "Свой")
                        }
                    }
                    DropdownMenu(repeatMenu, { repeatMenu = false }) {
                        for ((label, rule) in repeatPresets(anchor)) {
                            DropdownMenuItem(text = { Text(label) }, onClick = { repeat = rule; repeatMenu = false })
                        }
                    }
                }
                ColorPicker(color) { color = it }
            }
        },
        confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = ::create) { Text("Добавить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
    if (pickTaskDate) PickDate(taskDate, { taskDate = it; pickTaskDate = false }) { pickTaskDate = false }
    if (pickTaskTime) {
        PickTime(taskTime ?: LocalTime.of(9, 0), { taskTime = it; pickTaskTime = false }) { pickTaskTime = false }
    }
}

/** An end time picked on its own lands within a day after the start (the next morning when it is earlier). */
private fun endDateFor(value: EventTime): LocalDate =
    if (value.endDate <= value.startDate.plusDays(1)) value.startDate else value.endDate
