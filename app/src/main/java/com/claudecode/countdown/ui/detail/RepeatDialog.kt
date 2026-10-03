package com.claudecode.countdown.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.claudecode.countdown.data.db.RepeatFrom
import com.claudecode.tiktak.core.Freq
import com.claudecode.tiktak.core.RepeatRule
import com.claudecode.tiktak.core.WeekdayNum
import com.claudecode.tiktak.core.describe
import com.claudecode.tiktak.core.shortDayName
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private enum class MonthMode { DAY, LAST_DAY, WEEKDAY }
private enum class EndMode { NEVER, COUNT, UNTIL }

/** Result: RRULE (null = no repeat) and what the next date is counted from. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RepeatDialog(
    anchor: LocalDate,
    currentRule: String?,
    currentFrom: RepeatFrom,
    onConfirm: (String?, RepeatFrom) -> Unit,
    onDismiss: () -> Unit,
    /** Events have nothing to complete, so they repeat from their dates only. */
    allowFromCompletion: Boolean = true,
) {
    val weekOrdinal = if (anchor.dayOfMonth + 7 > anchor.lengthOfMonth()) -1 else (anchor.dayOfMonth - 1) / 7 + 1
    val presets = listOf(
        RepeatRule(Freq.DAILY),
        RepeatRule(Freq.WEEKLY, byDay = listOf(WeekdayNum(anchor.dayOfWeek))),
        RepeatRule(Freq.WEEKLY, byDay = RepeatRule.WEEKDAYS.map { WeekdayNum(it) }),
        RepeatRule(Freq.MONTHLY, byMonthDay = anchor.dayOfMonth),
        RepeatRule(Freq.YEARLY),
    )
    val parsed = RepeatRule.parse(currentRule)
    var custom by remember { mutableStateOf(parsed != null && presets.none { it.toRRule() == parsed.toRRule() }) }
    var selected by remember { mutableStateOf(parsed?.toRRule()) }
    var fromCompletion by remember { mutableStateOf(currentFrom == RepeatFrom.COMPLETION) }

    // Custom editor state, seeded from the current rule.
    val seed = parsed ?: RepeatRule(Freq.WEEKLY, byDay = listOf(WeekdayNum(anchor.dayOfWeek)))
    var freq by remember { mutableStateOf(seed.freq) }
    var interval by remember { mutableStateOf(seed.interval) }
    var days by remember { mutableStateOf(seed.byDay.filter { it.ordinal == 0 }.map { it.day }.toSet().ifEmpty { setOf(anchor.dayOfWeek) }) }
    var monthMode by remember {
        mutableStateOf(
            when {
                seed.byDay.any { it.ordinal != 0 } -> MonthMode.WEEKDAY
                seed.byMonthDay == -1 -> MonthMode.LAST_DAY
                else -> MonthMode.DAY
            }
        )
    }
    var endMode by remember { mutableStateOf(if (seed.count != null) EndMode.COUNT else if (seed.until != null) EndMode.UNTIL else EndMode.NEVER) }
    var count by remember { mutableStateOf(seed.count ?: 10) }
    var until by remember { mutableStateOf(seed.until ?: anchor.plusMonths(3)) }
    var pickUntil by remember { mutableStateOf(false) }

    fun customRule() = RepeatRule(
        freq = freq,
        interval = interval,
        byDay = when {
            freq == Freq.WEEKLY -> DayOfWeek.entries.filter { it in days }.map { WeekdayNum(it) }
            freq == Freq.MONTHLY && monthMode == MonthMode.WEEKDAY -> listOf(WeekdayNum(anchor.dayOfWeek, weekOrdinal))
            else -> emptyList()
        },
        byMonthDay = when {
            freq != Freq.MONTHLY -> null
            monthMode == MonthMode.DAY -> anchor.dayOfMonth
            monthMode == MonthMode.LAST_DAY -> -1
            else -> null
        },
        count = if (endMode == EndMode.COUNT) count else null,
        until = if (endMode == EndMode.UNTIL) until else null,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Повтор") },
        confirmButton = {
            TextButton(onClick = {
                val rule = if (custom) customRule().toRRule() else selected
                onConfirm(rule, if (fromCompletion) RepeatFrom.COMPLETION else RepeatFrom.DUE)
            }) { Text("Готово") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OptionRow("Не повторять", !custom && selected == null) { custom = false; selected = null }
                for (p in presets) {
                    val rrule = p.toRRule()
                    OptionRow(p.describe(), !custom && selected == rrule) { custom = false; selected = rrule }
                }
                OptionRow("Свой вариант…", custom) { custom = true }

                if (custom) {
                    Column(Modifier.padding(start = 8.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Каждые")
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { if (interval > 1) interval-- }) { Text("−") }
                            Text("$interval", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.titleMedium)
                            OutlinedButton(onClick = { if (interval < 99) interval++ }) { Text("+") }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for ((f, label) in listOf(Freq.DAILY to "дни", Freq.WEEKLY to "недели", Freq.MONTHLY to "месяцы", Freq.YEARLY to "годы")) {
                                FilterChip(selected = freq == f, onClick = { freq = f }, label = { Text(label) })
                            }
                        }
                        if (freq == Freq.WEEKLY) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (d in DayOfWeek.entries) {
                                    FilterChip(
                                        selected = d in days,
                                        onClick = { days = if (d in days && days.size > 1) days - d else days + d },
                                        label = { Text(shortDayName(d)) },
                                    )
                                }
                            }
                        }
                        if (freq == Freq.MONTHLY) {
                            val ordinalRule = RepeatRule(Freq.MONTHLY, byDay = listOf(WeekdayNum(anchor.dayOfWeek, weekOrdinal)))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(monthMode == MonthMode.DAY, { monthMode = MonthMode.DAY }, label = { Text("${anchor.dayOfMonth} числа") })
                                FilterChip(monthMode == MonthMode.LAST_DAY, { monthMode = MonthMode.LAST_DAY }, label = { Text("Последний день") })
                                FilterChip(
                                    monthMode == MonthMode.WEEKDAY, { monthMode = MonthMode.WEEKDAY },
                                    label = { Text(ordinalRule.describe().substringAfter(", ")) },
                                )
                            }
                        }
                        Text("Окончание", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(endMode == EndMode.NEVER, { endMode = EndMode.NEVER }, label = { Text("Никогда") })
                            FilterChip(endMode == EndMode.COUNT, { endMode = EndMode.COUNT }, label = { Text("После $count раз") })
                            FilterChip(endMode == EndMode.UNTIL, { endMode = EndMode.UNTIL; pickUntil = true }, label = { Text("До даты") })
                        }
                        if (endMode == EndMode.COUNT) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(onClick = { if (count > 1) count-- }) { Text("−") }
                                Text("$count", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.titleMedium)
                                OutlinedButton(onClick = { if (count < 999) count++ }) { Text("+") }
                            }
                        }
                        Text(customRule().describe(), color = MaterialTheme.colorScheme.primary)
                    }
                }

                if (allowFromCompletion) Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("От даты выполнения")
                        Text(
                            "Следующая дата считается от дня, когда задача выполнена",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(fromCompletion, { fromCompletion = it })
                }
            }
        },
    )

    if (pickUntil) UntilPicker(until, { until = it; pickUntil = false }, { pickUntil = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UntilPicker(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    ) { DatePicker(state, title = null, headline = null, showModeToggle = false) }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}
