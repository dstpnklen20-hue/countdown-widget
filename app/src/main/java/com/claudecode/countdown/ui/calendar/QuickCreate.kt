package com.claudecode.countdown.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.claudecode.countdown.ui.EventTime
import com.claudecode.countdown.ui.PickDate
import com.claudecode.countdown.ui.PickTime
import com.claudecode.countdown.ui.formatClock
import com.claudecode.countdown.ui.formatPickerDate
import kotlinx.coroutines.android.awaitFrame
import java.time.LocalDate
import java.time.LocalTime

/**
 * What is being created in the calendar: an event over [time], or a task on its start day (at
 * its start time when [timed]). The time grid draws it as a block while the card is open.
 */
data class QuickDraft(
    val time: EventTime,
    val event: Boolean,
    val title: String = "",
    /** A task without a time sits on its day as an all-day entry. */
    val timed: Boolean = true,
) {
    companion object {
        fun at(date: LocalDate, time: LocalTime?, minutes: Int, event: Boolean): QuickDraft {
            val start = date.atTime(time ?: LocalTime.of(9, 0))
            val end = start.plusMinutes(minutes.toLong())
            return QuickDraft(EventTime(date, start.toLocalTime(), end.toLocalDate(), end.toLocalTime(), allDay = false), event, timed = time != null || event)
        }
    }

    /** The minutes of [day] the draft covers on the time grid; null when it is not there. */
    fun minutesOn(day: LocalDate): IntRange? {
        if (time.allDay || !timed) return null
        if (day < time.startDate || day > time.endDate) return null
        val from = if (day == time.startDate) time.start.toSecondOfDay() / 60 else 0
        val to = if (day == time.endDate) time.end.toSecondOfDay() / 60 else 24 * 60
        return from until maxOf(to, from + 15)
    }
}

/**
 * The card at the bottom of the calendar for a quick new entry, as in Google Calendar: it covers
 * only the bottom of the screen, so the block it makes stays in view above it. "Ещё параметры"
 * opens the full form.
 */
@Composable
fun QuickCreateCard(
    draft: QuickDraft,
    onChange: (QuickDraft) -> Unit,
    onSave: () -> Unit,
    onMore: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    var picking by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        awaitFrame()
        runCatching { focus.requestFocus() }
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = scheme.surfaceContainerLow,
        tonalElevation = 3.dp,
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).size(width = 32.dp, height = 4.dp).clip(RoundedCornerShape(2.dp))
                    .background(scheme.onSurfaceVariant.copy(alpha = 0.4f))
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(true to "Событие", false to "Задача").forEachIndexed { i, (value, label) ->
                    SegmentedButton(
                        selected = draft.event == value,
                        onClick = { onChange(draft.copy(event = value)) },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                        icon = {},
                    ) { Text(label) }
                }
            }
            OutlinedTextField(
                value = draft.title,
                onValueChange = { onChange(draft.copy(title = it)) },
                placeholder = { Text(if (draft.event) "Название события" else "Что нужно сделать") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (draft.title.isNotBlank()) onSave() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val t = draft.time
                TimeButton(formatPickerDate(t.startDate)) { picking = "date" }
                if (draft.event) {
                    if (!t.allDay) {
                        TimeButton(formatClock(t.start)) { picking = "start" }
                        Text("–", color = scheme.onSurfaceVariant)
                        TimeButton(formatClock(t.end)) { picking = "end" }
                    }
                    Spacer(Modifier.weight(1f))
                    FilterChip(selected = t.allDay, onClick = { onChange(draft.copy(time = t.copy(allDay = !t.allDay))) }, label = { Text("Весь день") })
                } else {
                    TimeButton(if (draft.timed) formatClock(t.start) else "Без времени") { picking = "start" }
                    if (draft.timed) TextButton(onClick = { onChange(draft.copy(timed = false)) }) { Text("Убрать время") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onMore) { Text("Ещё параметры") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Отмена") }
                Spacer(Modifier.width(4.dp))
                Button(onClick = onSave, enabled = draft.title.isNotBlank()) { Text("Сохранить") }
            }
            Spacer(Modifier.height(0.dp))
        }
    }
    val t = draft.time
    when (picking) {
        "date" -> PickDate(t.startDate, { onChange(draft.copy(time = t.withStart(it, t.start))); picking = null }) { picking = null }
        "start" -> PickTime(t.start, { onChange(draft.copy(time = t.withStart(t.startDate, it), timed = true)); picking = null }) { picking = null }
        "end" -> PickTime(t.end, {
            onChange(draft.copy(time = t.withEnd(if (t.endDate <= t.startDate.plusDays(1)) t.startDate else t.endDate, it)))
            picking = null
        }) { picking = null }
    }
}

@Composable
private fun TimeButton(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
    )
}
