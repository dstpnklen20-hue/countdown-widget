package com.claudecode.countdown.ui.calendar

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.claudecode.countdown.container
import com.claudecode.countdown.data.TaskRepository.ReminderSpec
import com.claudecode.countdown.data.db.CalendarLayer
import com.claudecode.countdown.data.db.DisplayMode
import com.claudecode.countdown.data.db.EventType
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.domain.SeriesScope
import com.claudecode.countdown.domain.atOccurrence
import com.claudecode.countdown.domain.repeatDescription
import com.claudecode.countdown.domain.today
import com.claudecode.countdown.ui.PaletteDialog
import com.claudecode.countdown.ui.formatCountdown
import com.claudecode.countdown.ui.formatEventSpan
import com.claudecode.countdown.ui.rememberNow
import java.time.LocalDate

/**
 * The card an event opens with, as in Google Calendar: what, when, where, which calendar and
 * reminders, with buttons to edit, copy, share, recolour and delete it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailsSheet(
    task: Task,
    occurrence: LocalDate,
    calendars: List<CalendarLayer>,
    allDayMinutes: Int,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onColor: (Int?) -> Unit,
    onDelete: (SeriesScope) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val shown = task.atOccurrence(occurrence)
    val calendar = calendars.firstOrNull { it.id == (task.calendarId ?: CalendarLayer.PERSONAL_ID) }
    val color = Color(task.color ?: calendar?.color ?: 0xFF039BE5.toInt())
    val reminders by produceState(emptyList<ReminderSpec>(), task.id) {
        val repo = context.container.tasks
        value = with(repo) { repo.remindersOf(task.id) }
    }
    var pickColor by remember { mutableStateOf(false) }
    var askScope by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Изменить") }
                IconButton(onClick = { pickColor = true }) { Icon(Icons.Outlined.Palette, "Цвет") }
                IconButton(onClick = onDuplicate) { Icon(Icons.Outlined.ContentCopy, "Создать копию") }
                IconButton(onClick = { share(context, shown) }) { Icon(Icons.Outlined.Share, "Поделиться") }
                IconButton(onClick = { if (task.repeatRule != null) askScope = true else onDelete(SeriesScope.ALL) }) {
                    Icon(Icons.Outlined.Delete, "Удалить")
                }
            }
            Row(Modifier.padding(start = 24.dp, end = 20.dp, top = 4.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 6.dp).size(18.dp).clip(RoundedCornerShape(5.dp)).background(color))
                Spacer(Modifier.width(22.dp))
                Column {
                    Text(task.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Normal)
                    formatEventSpan(shown, today())?.let {
                        Text(it.replaceFirstChar { c -> c.uppercase() }, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                    }
                    task.eventType?.let { t -> EventType.entries.firstOrNull { it.name == t } }?.let {
                        Text(it.label, style = MaterialTheme.typography.bodyMedium, color = scheme.primary)
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
            task.repeatDescription()?.let { DetailRow(Icons.Outlined.Repeat, it.replaceFirstChar { c -> c.uppercase() }) }
            task.location?.let { place -> DetailRow(Icons.Outlined.LocationOn, place, onClick = { openMap(context, place) }) }
            if (task.content.isNotBlank()) DetailRow(Icons.AutoMirrored.Outlined.Notes, task.content)
            DetailRow(Icons.Outlined.CalendarMonth, calendar?.name ?: "Личное")
            for (r in reminders) DetailRow(Icons.Outlined.Notifications, reminderText(r, task.isAllDay, allDayMinutes))
            if (task.displayMode == DisplayMode.COUNTDOWN && shown.startAt != null) {
                val now by rememberNow(60_000)
                val left = (shown.startAt ?: shown.dueAt!!) - now
                if (left > 0) DetailRow(Icons.Outlined.HourglassBottom, "До события: " + formatCountdown(left, withSeconds = false))
            }
        }
    }
    if (pickColor) PaletteDialog("Цвет события", task.color, "Цвет календаря", onColor) { pickColor = false }
    if (askScope) {
        ScopeDialog("Удалить повторяющееся событие", SeriesScope.entries, { askScope = false; onDelete(it) }) { askScope = false }
    }
}

@Composable
private fun DetailRow(icon: ImageVector, text: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(20.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

/** Sends the event as text: title, time, place and description. */
private fun share(context: Context, task: Task) {
    val text = listOfNotNull(
        task.title,
        formatEventSpan(task, today()),
        task.location?.let { "Место: $it" },
        task.content.takeIf { it.isNotBlank() },
    ).joinToString("\n")
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Поделиться событием").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
