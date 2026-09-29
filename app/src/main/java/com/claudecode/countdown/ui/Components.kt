package com.claudecode.countdown.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.claudecode.countdown.data.db.Priority
import com.claudecode.countdown.domain.Due
import com.claudecode.countdown.domain.allDayDue
import com.claudecode.countdown.domain.localTimeOf
import com.claudecode.countdown.domain.timedDue
import com.claudecode.countdown.domain.today
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Current time, refreshed every [periodMs] while [enabled]. */
@Composable
fun rememberNow(periodMs: Long, enabled: Boolean = true): State<Long> {
    val state = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs, enabled) {
        while (enabled) {
            state.longValue = System.currentTimeMillis()
            delay(periodMs - System.currentTimeMillis() % periodMs)
        }
    }
    return state
}

@Composable
fun PriorityCheckbox(done: Boolean, priority: Int, onToggle: () -> Unit, modifier: Modifier = Modifier, small: Boolean = false) {
    val color = priorityColor(priority, MaterialTheme.colorScheme.outline)
    Box(
        modifier = modifier
            .size(if (small) 34.dp else 40.dp)
            .clip(CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(if (small) 16.dp else 20.dp)
                .clip(RoundedCornerShape(if (small) 4.dp else 5.dp))
                .background(if (done) color.copy(alpha = 0.55f) else Color.Transparent)
                .border(if (small) 1.5.dp else 2.dp, color, RoundedCornerShape(if (small) 4.dp else 5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(if (small) 11.dp else 14.dp))
        }
    }
}

@Composable
fun PriorityMenu(expanded: Boolean, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        for (p in listOf(Priority.HIGH, Priority.MEDIUM, Priority.LOW, Priority.NONE)) {
            DropdownMenuItem(
                text = { Text(priorityName(p)) },
                leadingIcon = {
                    Icon(
                        if (p == Priority.NONE) Icons.Outlined.Flag else Icons.Filled.Flag,
                        null,
                        tint = priorityColor(p, MaterialTheme.colorScheme.outline),
                    )
                },
                onClick = { onSelect(p); onDismiss() },
            )
        }
    }
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String = "",
    label: String = "Название",
    confirmLabel: String = "Сохранить",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        awaitFrame()
        runCatching { focus.requestFocus() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(label) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onConfirm(text.trim()) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                extra()
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun ColorPicker(selected: Int?, onSelect: (Int?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(24.dp).clip(CircleShape)
                .border(if (selected == null) 3.dp else 1.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                .clickable { onSelect(null) }
        )
        for (color in LIST_COLORS.take(8)) {
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(Color(color))
                    .border(if (selected == color) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    .clickable { onSelect(color) }
            )
        }
    }
}

/**
 * Date picker with quick buttons and an optional time. Returns null when the date is removed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DueDateDialog(
    initialAt: Long?,
    initialAllDay: Boolean,
    onConfirm: (Due?) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val initialDate = initialAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: today()
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    )
    var time by remember { mutableStateOf(if (initialAt != null && !initialAllDay) localTimeOf(initialAt) else null) }
    var pickTime by remember { mutableStateOf(false) }

    fun selectedDate(): LocalDate = dateState.selectedDateMillis
        ?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: initialDate

    fun confirm(date: LocalDate) {
        onConfirm(time?.let { timedDue(date, it) } ?: allDayDue(date))
    }

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { confirm(selectedDate()) }) { Text("Готово") } },
        dismissButton = {
            Row {
                if (initialAt != null) TextButton(onClick = { onConfirm(null) }) { Text("Убрать дату") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    ) {
        // The dialog puts content into a Box, so stack it ourselves; scroll keeps it usable on short screens.
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QuickDateButton("Сегодня") { confirm(today()) }
                QuickDateButton("Завтра") { confirm(today().plusDays(1)) }
                QuickDateButton("Через неделю") { confirm(today().plusDays(7)) }
            }
            DatePicker(state = dateState, title = null, headline = null, showModeToggle = false)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Время", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { pickTime = true }) { Text(time?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "Не задано") }
                if (time != null) TextButton(onClick = { time = null }) { Text("Убрать") }
            }
        }
    }

    if (pickTime) {
        val initial = time ?: LocalTime.of(9, 0)
        val timeState = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = {
                TextButton(onClick = { time = LocalTime.of(timeState.hour, timeState.minute); pickTime = false }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Отмена") } },
            text = { TimePicker(state = timeState) },
        )
    }
}

@Composable
private fun QuickDateButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun Dot(color: Color, size: Int = 10) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

@Composable
fun HSpace(width: Int) = Spacer(Modifier.width(width.dp))
