package com.claudecode.countdown.ui.calendar

import com.claudecode.countdown.ui.zoneLabel
import com.claudecode.countdown.ui.ZonePickerDialog
import androidx.compose.material.icons.outlined.Public
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.claudecode.countdown.data.TaskRepository.ReminderSpec
import com.claudecode.countdown.data.db.CalendarLayer
import com.claudecode.countdown.data.db.DisplayMode
import com.claudecode.countdown.data.db.EventType
import com.claudecode.countdown.data.db.RepeatFrom
import com.claudecode.countdown.data.db.ReminderKind
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.domain.SeriesScope
import com.claudecode.countdown.domain.allDayReminderPresets
import com.claudecode.countdown.domain.reminderLabel
import com.claudecode.countdown.domain.repeatDescription
import com.claudecode.countdown.ui.EventTime
import com.claudecode.countdown.ui.EventTimeFields
import com.claudecode.countdown.ui.PaletteDialog
import com.claudecode.countdown.ui.colorName
import com.claudecode.countdown.ui.detail.RepeatDialog
import com.claudecode.countdown.ui.repeatPresets
import kotlinx.coroutines.android.awaitFrame
import java.time.LocalDate

/**
 * What the event editor works on: [task] is the event as it is on [occurrence] (its first one
 * for a new event), [master] the stored row it belongs to (null while it is new).
 */
data class EventDraft(
    val task: Task,
    val reminders: List<ReminderSpec>,
    val master: Task? = null,
    val occurrence: LocalDate? = null,
)

/** Reminder offsets offered for timed events, as in Google Calendar. */
private val EVENT_REMINDER_PRESETS = listOf(0, 5, 10, 30, 60, 24 * 60)

fun reminderText(spec: ReminderSpec, allDay: Boolean, allDayMinutes: Int): String {
    val base = spec.offsetMinutes?.let { reminderLabel(it, allDay, allDayMinutes) } ?: "В заданное время"
    return if (spec.kind == ReminderKind.ALARM) "$base · будильник" else base
}

/**
 * The full event form, as Google Calendar's: title, time, repeat, place, description, calendar,
 * colour, reminders and kind. [onSave] gets the edited event and its reminders, plus which
 * occurrences of a series the change applies to; [onDelete] likewise.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EventEditScreen(
    draft: EventDraft,
    calendars: List<CalendarLayer>,
    pastEvents: List<Task>,
    allDayMinutes: Int,
    onSave: (Task, List<ReminderSpec>, SeriesScope) -> Unit,
    onDelete: ((SeriesScope) -> Unit)?,
    onMakeTask: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val original = draft.task
    var title by remember { mutableStateOf(original.title) }
    var time by remember { mutableStateOf(EventTime.of(original)) }
    var rule by remember { mutableStateOf(original.repeatRule) }
    var location by remember { mutableStateOf(original.location.orEmpty()) }
    var content by remember { mutableStateOf(original.content) }
    var calendarId by remember { mutableStateOf(original.calendarId ?: CalendarLayer.PERSONAL_ID) }
    var color by remember { mutableStateOf(original.color) }
    var type by remember { mutableStateOf(original.eventType) }
    var countdown by remember { mutableStateOf(original.displayMode == DisplayMode.COUNTDOWN) }
    var reminders by remember { mutableStateOf(draft.reminders) }

    var repeatMenu by remember { mutableStateOf(false) }
    var customRepeat by remember { mutableStateOf(false) }
    var calendarMenu by remember { mutableStateOf(false) }
    var pickColor by remember { mutableStateOf(false) }
    var reminderMenu by remember { mutableStateOf(false) }
    var customReminder by remember { mutableStateOf(false) }
    var typeMenu by remember { mutableStateOf(false) }
    var askScope by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var zonesDialog by remember { mutableStateOf(false) }

    val isNew = draft.master == null
    val series = draft.master?.repeatRule != null
    val calendar = calendars.firstOrNull { it.id == calendarId } ?: calendars.firstOrNull()

    fun build(): Task = time.applyTo(
        original.copy(
            title = title.trim().ifEmpty { "(Без названия)" },
            repeatRule = rule,
            repeatFrom = RepeatFrom.DUE,
            location = location.trim().ifEmpty { null },
            content = content,
            calendarId = calendarId.takeIf { it != CalendarLayer.PERSONAL_ID },
            color = color,
            eventType = type,
            displayMode = if (countdown) DisplayMode.COUNTDOWN else DisplayMode.NORMAL,
            isEvent = true,
        )
    )

    val initial = remember { build() }
    val changed = build() != initial || reminders != draft.reminders
    fun close() = if (changed) confirmDiscard = true else onClose()
    BackHandler { close() }

    fun save() {
        when {
            series -> askScope = "save"
            else -> onSave(build(), reminders, SeriesScope.ALL)
        }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (isNew && title.isEmpty()) {
            awaitFrame()
            runCatching { focus.requestFocus() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = ::close) { Icon(Icons.Filled.Close, "Закрыть") } },
                title = { Text(if (isNew) "Новое событие" else "Событие") },
                actions = {
                    Button(onClick = ::save, modifier = Modifier.padding(end = 8.dp)) { Text("Сохранить") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            BasicTextField(
                value = title,
                onValueChange = { title = it },
                textStyle = MaterialTheme.typography.headlineSmall.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().padding(start = 64.dp, end = 20.dp, top = 8.dp, bottom = 8.dp).focusRequester(focus),
                decorationBox = { inner ->
                    Box {
                        if (title.isEmpty()) Text("Название", style = MaterialTheme.typography.headlineSmall, color = scheme.onSurfaceVariant)
                        inner()
                    }
                },
            )
            // Suggestions from earlier events: picking one also takes its place, calendar and colour.
            val suggestions = remember(title, pastEvents) { titleSuggestions(title, pastEvents) }
            if (suggestions.isNotEmpty()) {
                FlowRow(Modifier.padding(start = 64.dp, end = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in suggestions) {
                        SuggestionChip(onClick = {
                            title = s.title
                            if (location.isBlank()) location = s.location.orEmpty()
                            if (color == null) color = s.color
                            if (isNew) s.calendarId?.let { calendarId = it }
                        }, label = { Text(s.title) })
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            FieldRow(Icons.Outlined.AccessTime) { EventTimeFields(time) { time = it } }
            if (!time.allDay) {
                FieldRow(Icons.Outlined.Public, onClick = { zonesDialog = true }) {
                    Text(
                        if (!time.hasZones) "Часовой пояс приложения" else zonesText(time),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Hint(if (!time.hasZones) "Нажмите, чтобы задать свой пояс началу и концу (перелёт)" else "Время начала и конца — в этих поясах")
                }
            }
            FieldRow(Icons.Outlined.Repeat, onClick = { repeatMenu = true }) {
                Box {
                    Text(
                        rule?.let { Task(title = "", repeatRule = it).repeatDescription() } ?: "Не повторять",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    DropdownMenu(repeatMenu, { repeatMenu = false }) {
                        for ((label, r) in repeatPresets(time.startDate)) {
                            DropdownMenuItem(text = { Text(label) }, onClick = { rule = r; repeatMenu = false })
                        }
                        DropdownMenuItem(text = { Text("Другое…") }, onClick = { repeatMenu = false; customRepeat = true })
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            FieldRow(Icons.Outlined.LocationOn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = location,
                        onValueChange = { location = it },
                        placeholder = { Text("Место") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.weight(1f),
                    )
                    if (location.isNotBlank()) {
                        IconButton(onClick = { openMap(context, location) }) { Icon(Icons.Outlined.Map, "Открыть на карте") }
                    }
                }
                val places = remember(location, pastEvents) { placeSuggestions(location, pastEvents) }
                if (places.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (p in places) SuggestionChip(onClick = { location = p }, label = { Text(p) })
                    }
                }
            }
            FieldRow(Icons.AutoMirrored.Outlined.Notes) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    placeholder = { Text("Описание") },
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            FieldRow(Icons.Outlined.CalendarMonth, onClick = { calendarMenu = true }) {
                Box {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(Color(calendar?.color ?: 0xFF039BE5.toInt())))
                        Text(calendar?.name ?: "Личное", style = MaterialTheme.typography.bodyLarge)
                    }
                    DropdownMenu(calendarMenu, { calendarMenu = false }) {
                        for (c in calendars) {
                            DropdownMenuItem(
                                text = { Text(c.name) },
                                leadingIcon = { Box(Modifier.size(14.dp).clip(CircleShape).background(Color(c.color))) },
                                onClick = { calendarId = c.id; calendarMenu = false },
                            )
                        }
                    }
                }
            }
            FieldRow(Icons.Outlined.Palette, onClick = { pickColor = true }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(Color(color ?: calendar?.color ?: 0xFF039BE5.toInt())))
                    Text(colorName(color) ?: "Цвет календаря", style = MaterialTheme.typography.bodyLarge)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            FieldRow(Icons.Outlined.Notifications) {
                Column {
                    if (reminders.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (r in reminders) {
                                InputChip(
                                    selected = false,
                                    onClick = { reminders = reminders - r },
                                    label = { Text(reminderText(r, time.allDay, allDayMinutes)) },
                                    leadingIcon = if (r.kind == ReminderKind.ALARM) ({ Icon(Icons.Outlined.Alarm, null, Modifier.size(16.dp)) }) else null,
                                    trailingIcon = { Icon(Icons.Filled.Close, "Убрать", Modifier.size(16.dp)) },
                                )
                            }
                        }
                    }
                    Box {
                        Text(
                            "Добавить уведомление",
                            color = scheme.primary,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { reminderMenu = true }.padding(vertical = 8.dp),
                        )
                        DropdownMenu(reminderMenu, { reminderMenu = false }) {
                            val offsets = if (time.allDay) allDayReminderPresets(allDayMinutes).map { it.offsetMinutes } else EVENT_REMINDER_PRESETS
                            for (o in offsets) {
                                DropdownMenuItem(
                                    text = { Text(reminderLabel(o, time.allDay, allDayMinutes)) },
                                    onClick = {
                                        val spec = ReminderSpec(o)
                                        if (spec !in reminders) reminders = reminders + spec
                                        reminderMenu = false
                                    },
                                )
                            }
                            DropdownMenuItem(text = { Text("Своё…") }, onClick = { reminderMenu = false; customReminder = true })
                        }
                    }
                }
            }
            FieldRow(Icons.Outlined.Category, onClick = { typeMenu = true }) {
                Box {
                    Text(EventType.entries.firstOrNull { it.name == type }?.label ?: "Обычное событие", style = MaterialTheme.typography.bodyLarge)
                    DropdownMenu(typeMenu, { typeMenu = false }) {
                        DropdownMenuItem(text = { Text("Обычное событие") }, onClick = { type = null; typeMenu = false })
                        DropdownMenuItem(
                            text = { Column { Text(EventType.FOCUS.label); Hint("Напоминания в это время приходят без звука") } },
                            onClick = { type = EventType.FOCUS.name; typeMenu = false },
                        )
                        DropdownMenuItem(
                            text = { Column { Text(EventType.AWAY.label); Hint("Отпуск, командировка: блок со штриховкой") } },
                            onClick = { type = EventType.AWAY.name; typeMenu = false },
                        )
                    }
                }
            }
            FieldRow(Icons.Outlined.HourglassBottom) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Обратный отсчёт", style = MaterialTheme.typography.bodyLarge)
                        Hint("«До события осталось N дней» — в «Отсчётах» и на виджете")
                    }
                    Switch(countdown, { countdown = it })
                }
            }
            if (onMakeTask != null || onDelete != null) HorizontalDivider(Modifier.padding(vertical = 8.dp))
            if (onMakeTask != null) {
                TextButton(onClick = onMakeTask, modifier = Modifier.padding(start = 52.dp)) { Text("Сделать задачей") }
            }
            if (onDelete != null) {
                TextButton(onClick = { if (series) askScope = "delete" else onDelete(SeriesScope.ALL) }, modifier = Modifier.padding(start = 52.dp)) {
                    Icon(Icons.Outlined.Delete, null, tint = scheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text("Удалить", color = scheme.error)
                }
            }
        }
    }

    if (customRepeat) {
        RepeatDialog(
            anchor = time.startDate,
            currentRule = rule,
            currentFrom = RepeatFrom.DUE,
            onConfirm = { r, _ -> rule = r; customRepeat = false },
            onDismiss = { customRepeat = false },
            allowFromCompletion = false,
        )
    }
    if (pickColor) {
        PaletteDialog("Цвет события", color, "Цвет календаря", { color = it }) { pickColor = false }
    }
    if (customReminder) {
        CustomReminderDialog(time.allDay, onAdd = { if (it !in reminders) reminders = reminders + it; customReminder = false }) { customReminder = false }
    }
    askScope?.let { action ->
        // A new repeat rule only makes sense for a series from here on, not for one occurrence.
        val ruleChanged = rule != draft.master?.repeatRule
        ScopeDialog(
            title = if (action == "save") "Изменить повторяющееся событие" else "Удалить повторяющееся событие",
            scopes = if (action == "save" && ruleChanged) listOf(SeriesScope.FOLLOWING, SeriesScope.ALL) else SeriesScope.entries,
            onPick = { scope ->
                askScope = null
                if (action == "save") onSave(build(), reminders, scope) else onDelete?.invoke(scope)
            },
            onDismiss = { askScope = null },
        )
    }
    if (zonesDialog) {
        ZonesDialog(time, onChange = { time = it }) { zonesDialog = false }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Не сохранять изменения?") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onClose() }) { Text("Не сохранять") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Продолжить") } },
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A row of the form: an icon on the left and the field, like Google Calendar's editor. */
@Composable
private fun FieldRow(icon: ImageVector, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp).size(24.dp))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) { content() }
    }
}

/** Which occurrences a change or a deletion applies to. */
@Composable
fun ScopeDialog(title: String, scopes: List<SeriesScope>, onPick: (SeriesScope) -> Unit, onDismiss: () -> Unit) {
    var chosen by remember { mutableStateOf(scopes.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                for (s in scopes) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { chosen = s }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(selected = chosen == s, onClick = { chosen = s })
                        Text(s.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(chosen) }) { Text("ОК") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** A reminder of any length before the start: N minutes, hours, days or weeks; a notification or an alarm. */
@Composable
private fun CustomReminderDialog(allDay: Boolean, onAdd: (ReminderSpec) -> Unit, onDismiss: () -> Unit) {
    val units = if (allDay) listOf("дн." to 24 * 60, "нед." to 7 * 24 * 60) else listOf("мин" to 1, "ч" to 60, "дн." to 24 * 60, "нед." to 7 * 24 * 60)
    var amount by remember { mutableStateOf(if (allDay) "1" else "15") }
    var unit by remember { mutableStateOf(units.first()) }
    var alarm by remember { mutableStateOf(false) }
    val value = amount.toIntOrNull()?.takeIf { it in 0..999 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Своё напоминание") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("За")
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it.filter(Char::isDigit).take(3) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(80.dp),
                    )
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    units.forEachIndexed { i, u ->
                        SegmentedButton(selected = unit == u, onClick = { unit = u }, shape = SegmentedButtonDefaults.itemShape(i, units.size), icon = {}) {
                            Text(u.first)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Будильник", fontWeight = FontWeight.Medium)
                        Hint("Звонит и показывается на весь экран — для важного")
                    }
                    Switch(alarm, { alarm = it })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = value != null, onClick = {
                onAdd(ReminderSpec(value!! * unit.second, kind = if (alarm) ReminderKind.ALARM else ReminderKind.NOTIFY))
            }) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Earlier events whose title starts like [typed]: the newest of each title, at most three. */
private fun titleSuggestions(typed: String, events: List<Task>): List<Task> {
    val q = typed.trim()
    if (q.length < 2) return emptyList()
    return events.asSequence()
        .filter { it.title.startsWith(q, ignoreCase = true) && !it.title.equals(q, ignoreCase = true) }
        .sortedByDescending { it.updatedAt }
        .distinctBy { it.title.lowercase() }
        .take(3)
        .toList()
}

/** Places of earlier events that match what is typed, most used first. */
private fun placeSuggestions(typed: String, events: List<Task>): List<String> {
    val q = typed.trim()
    val places = events.mapNotNull { it.location?.trim()?.takeIf(String::isNotEmpty) }
    return places.groupingBy { it }.eachCount().entries
        .filter { (p, _) -> !p.equals(q, ignoreCase = true) && (q.isEmpty() || p.contains(q, ignoreCase = true)) }
        .sortedByDescending { it.value }
        .take(3)
        .map { it.key }
}

/** Opens the place in a maps app (any app that handles geo: links). */
fun openMap(context: android.content.Context, place: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=" + Uri.encode(place))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/** "Москва (UTC+3) → Дубай (UTC+4)", or one zone when both ends share it. */
private fun zonesText(time: EventTime): String {
    val app = java.time.ZoneId.systemDefault().id
    val start = time.startZone ?: app
    val end = time.endZone ?: start
    return if (start == end) zoneLabel(start) else "${zoneLabel(start)} → ${zoneLabel(end)}"
}

/** Zones for the start and the end of an event; the clock times stay as typed. */
@Composable
private fun ZonesDialog(time: EventTime, onChange: (EventTime) -> Unit, onDismiss: () -> Unit) {
    var picking by remember { mutableStateOf<String?>(null) }
    val app = java.time.ZoneId.systemDefault().id
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Часовой пояс") },
        text = {
            Column {
                Text(
                    "Например, вылет в 10:00 по Москве и прилёт в 14:00 по Дубаю. В календаре событие встанет на ваше время.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ZoneChoice("Начало", zoneLabel(time.startZone ?: app) + if (time.startZone == null) " — как в приложении" else "") { picking = "start" }
                ZoneChoice("Конец", zoneLabel(time.endZone ?: time.startZone ?: app) + if (time.endZone == null) " — как у начала" else "") { picking = "end" }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
        dismissButton = { if (time.hasZones) TextButton(onClick = { onChange(time.withZones(null, null)); onDismiss() }) { Text("Как в приложении") } },
    )
    when (picking) {
        "start" -> ZonePickerDialog("Пояс начала", time.startZone, "Как в приложении", { onChange(time.withZones(it, time.endZone)) }) { picking = null }
        "end" -> ZonePickerDialog("Пояс конца", time.endZone, "Как у начала", { onChange(time.withZones(time.startZone, it)) }) { picking = null }
    }
}

@Composable
private fun ZoneChoice(label: String, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }
}
