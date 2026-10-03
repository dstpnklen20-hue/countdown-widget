package com.claudecode.countdown.ui.calendar

import com.claudecode.countdown.ui.zoneLabel
import com.claudecode.countdown.ui.ZonePickerDialog
import com.claudecode.countdown.data.AppZone
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claudecode.countdown.container
import com.claudecode.countdown.data.AppSettings
import com.claudecode.countdown.data.db.CalendarLayer
import com.claudecode.countdown.domain.formatMinuteOfDay
import com.claudecode.countdown.domain.reminderLabel
import com.claudecode.countdown.ui.AddFab
import com.claudecode.countdown.ui.AppSnackbarHost
import com.claudecode.countdown.ui.CALENDAR_COLORS
import com.claudecode.countdown.ui.PaletteGrid
import com.claudecode.countdown.ui.settings.Section
import com.claudecode.countdown.ui.settings.SettingRow
import com.claudecode.countdown.ui.settings.SwitchRow
import com.claudecode.countdown.ui.settings.TimeDialog
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.format.TextStyle


private val DURATIONS = listOf(15, 30, 45, 60, 90, 120)


/** Calendar settings, as Google Calendar has them: the week, new events, working and quiet hours, zones. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarSettingsScreen(onBack: () -> Unit, onOpenCalendars: () -> Unit) {
    val container = LocalContext.current.container
    val app = container.settings
    val s by app.state.collectAsStateWithLifecycle()
    val calendars by container.tasks.observeCalendars().collectAsStateWithLifecycle(emptyList())
    var dialog by remember { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки календаря") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Section("Вид") {
                SettingRow("Первый день недели", s.firstDay.getDisplayName(TextStyle.FULL_STANDALONE, ru).replaceFirstChar { it.uppercase() }) { dialog = "firstDay" }
                SwitchRow("Номера недель", s.weekNumbers, app::setWeekNumbers)
                SwitchRow("Выходные в виде «Неделя»", s.showWeekends, app::setShowWeekends)
                SwitchRow("Приглушать прошедшие события", s.dimPast, app::setDimPast)
                SwitchRow("Выполненные задачи в календаре", s.calendarDone, app::setCalendarDone)
                SettingRow(
                    "Масштаб шкалы времени",
                    "${s.hourHeight} dp на час. Меняется щипком двумя пальцами" + if (s.hourHeight != AppSettings.DEFAULT_HOUR_HEIGHT) " · нажмите, чтобы сбросить" else "",
                ) { app.setHourHeight(AppSettings.DEFAULT_HOUR_HEIGHT) }
            }
            Section("Новые события") {
                SettingRow("Длительность по умолчанию", "${s.eventMinutes} мин") { dialog = "duration" }
                val def = calendars.firstOrNull { it.id == s.defaultCalendarId } ?: calendars.firstOrNull()
                SettingRow("Календарь по умолчанию", def?.name ?: "Личное") { dialog = "calendar" }
                SettingRow("Календари, цвета и напоминания", "Создать, переименовать, цвет и уведомления по умолчанию", onOpenCalendars)
            }
            Section("Время") {
                SettingRow(
                    "Рабочие часы",
                    if (s.workStart != null && s.workEnd != null) "${formatMinuteOfDay(s.workStart!!)}–${formatMinuteOfDay(s.workEnd!!)} · остальное время затемнено" else "Выключены",
                ) { dialog = "work" }
                SettingRow("Второй часовой пояс", s.secondZone?.let(::zoneLabel) ?: "Нет") { dialog = "zone" }
                SettingRow(
                    "Тихие часы",
                    if (s.quietStart != null && s.quietEnd != null) "${formatMinuteOfDay(s.quietStart!!)}–${formatMinuteOfDay(s.quietEnd!!)} · напоминания без звука" else "Выключены",
                ) { dialog = "quiet" }
                SettingRow(
                    "Часовой пояс приложения",
                    s.appZone?.let { "${zoneLabel(it)} · на телефоне ${zoneLabel(AppZone.device().id)}" } ?: "Как на телефоне: ${zoneLabel(AppZone.device().id)}",
                ) { dialog = "appZone" }
            }
        }
    }
    when (dialog) {
        "firstDay" -> ChoiceDialog(
            "Первый день недели",
            listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY, DayOfWeek.SATURDAY).map { it.value to it.getDisplayName(TextStyle.FULL_STANDALONE, ru).replaceFirstChar { c -> c.uppercase() } },
            s.firstDayOfWeek,
            { app.setFirstDayOfWeek(it) },
        ) { dialog = null }
        "duration" -> ChoiceDialog("Длительность события", DURATIONS.map { it to "$it мин" }, s.eventMinutes, app::setEventMinutes) { dialog = null }
        "calendar" -> ChoiceDialog("Календарь по умолчанию", calendars.map { it.id to it.name }, s.defaultCalendarId, app::setDefaultCalendar) { dialog = null }
        "zone" -> ZonePickerDialog("Второй часовой пояс", s.secondZone, "Нет", app::setSecondZone) { dialog = null }
        "appZone" -> ZonePickerDialog(
            "Часовой пояс приложения",
            s.appZone,
            "Как на телефоне — ${zoneLabel(AppZone.device().id)}",
            { id ->
                app.setAppZone(id)
                // Reminders and widgets count days and times in the new zone.
                container.onDataChanged()
                container.refreshAllWidgets()
            },
        ) { dialog = null }
        "work" -> RangeDialog("Рабочие часы", s.workStart ?: 9 * 60, s.workEnd ?: 18 * 60, on = s.workStart != null, onSet = app::setWorkHours) { dialog = null }
        "quiet" -> RangeDialog("Тихие часы", s.quietStart ?: 23 * 60, s.quietEnd ?: 7 * 60, on = s.quietStart != null, onSet = app::setQuietHours) { dialog = null }
    }
}

@Composable
private fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for ((value, label) in options) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(value); onDismiss() }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(selected = value == selected, onClick = { onPick(value); onDismiss() })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

/** From–to times of day with an "off" button; [onSet] gets nulls when turned off. */
@Composable
private fun RangeDialog(title: String, start: Int, end: Int, on: Boolean, onSet: (Int?, Int?) -> Unit, onDismiss: () -> Unit) {
    var from by remember { mutableStateOf(start) }
    var to by remember { mutableStateOf(end) }
    var picking by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { picking = "from" }) { Text("с " + formatMinuteOfDay(from), style = MaterialTheme.typography.titleLarge) }
                TextButton(onClick = { picking = "to" }) { Text("до " + formatMinuteOfDay(to), style = MaterialTheme.typography.titleLarge) }
            }
        },
        confirmButton = { TextButton(onClick = { onSet(from, to); onDismiss() }) { Text("Включить") } },
        dismissButton = { if (on) TextButton(onClick = { onSet(null, null); onDismiss() }) { Text("Выключить") } else TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
    when (picking) {
        "from" -> TimeDialog(from, { picking = null }) { from = it; picking = null }
        "to" -> TimeDialog(to, { picking = null }) { to = it; picking = null }
    }
}

/** The calendars (layers): their names, colours and default reminders; "+" makes a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarsScreen(onBack: () -> Unit) {
    val container = LocalContext.current.container
    val repo = container.tasks
    val calendars by repo.observeCalendars().collectAsStateWithLifecycle(emptyList())
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<CalendarLayer?>(null) }
    var creating by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Календари") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = { AddFab("Новый календарь") { creating = true } },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(
                "Каждое событие лежит в одном календаре и берёт его цвет, если у события нет своего. Скрыть календарь можно галочкой в меню календаря — события останутся.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Section("Мои календари") {
                for (c in calendars) {
                    Row(
                        Modifier.fillMaxWidth().clickable { editing = c }.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(18.dp).clip(CircleShape).background(Color(c.color)))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.bodyLarge)
                            val parts = listOfNotNull(
                                "по умолчанию".takeIf { c.id == settings.defaultCalendarId },
                                c.defaultReminder?.let { reminderLabel(it, false) } ?: "без напоминания",
                            )
                            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    if (creating) {
        CalendarDialog(null, settings.allDayReminderMinutes, isDefault = false, onSave = { c, makeDefault ->
            scope.launch {
                val created = repo.createCalendar(c.name, c.color)
                repo.updateCalendar(created.copy(defaultReminder = c.defaultReminder, defaultAllDayReminder = c.defaultAllDayReminder))
                if (makeDefault) container.settings.setDefaultCalendar(created.id)
            }
            creating = false
        }, onDelete = null) { creating = false }
    }
    editing?.let { c ->
        CalendarDialog(c, settings.allDayReminderMinutes, isDefault = c.id == settings.defaultCalendarId, onSave = { edited, makeDefault ->
            scope.launch { repo.updateCalendar(edited) }
            if (makeDefault) container.settings.setDefaultCalendar(c.id)
            editing = null
        }, onDelete = if (c.id == CalendarLayer.PERSONAL_ID) null else ({
            scope.launch {
                val at = repo.deleteCalendar(c) ?: return@launch
                if (settings.defaultCalendarId == c.id) container.settings.setDefaultCalendar(CalendarLayer.PERSONAL_ID)
                container.undo.offer("Календарь «${c.name}» удалён") { repo.restoreCalendar(c, at) }
            }
            editing = null
        })) { editing = null }
    }
}

private val TIMED_DEFAULTS = listOf<Int?>(null, 0, 5, 10, 15, 30, 60, 120, 24 * 60)
private val ALL_DAY_DEFAULTS = listOf<Int?>(null, 0, 24 * 60, 2 * 24 * 60, 7 * 24 * 60)

@Composable
private fun CalendarDialog(
    calendar: CalendarLayer?,
    allDayMinutes: Int,
    isDefault: Boolean,
    onSave: (CalendarLayer, makeDefault: Boolean) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(calendar?.name.orEmpty()) }
    var color by remember { mutableStateOf(calendar?.color ?: CALENDAR_COLORS[7].argb) }
    var timed by remember { mutableStateOf(if (calendar == null) 30 else calendar.defaultReminder) }
    var allDay by remember { mutableStateOf(if (calendar == null) 24 * 60 else calendar.defaultAllDayReminder) }
    var makeDefault by remember { mutableStateOf(isDefault) }
    var menu by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (calendar == null) "Новый календарь" else "Календарь") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                PaletteGrid(color, null, { it?.let { c -> color = c } })
                Box {
                    SettingRow("Уведомление для событий", timed?.let { reminderLabel(it, false) } ?: "Нет") { menu = "timed" }
                    DropdownMenu(menu == "timed", { menu = null }) {
                        for (o in TIMED_DEFAULTS) DropdownMenuItem(text = { Text(o?.let { reminderLabel(it, false) } ?: "Нет") }, onClick = { timed = o; menu = null })
                    }
                }
                Box {
                    SettingRow("Для событий на весь день", allDay?.let { reminderLabel(it, true, allDayMinutes) } ?: "Нет") { menu = "allDay" }
                    DropdownMenu(menu == "allDay", { menu = null }) {
                        for (o in ALL_DAY_DEFAULTS) DropdownMenuItem(text = { Text(o?.let { reminderLabel(it, true, allDayMinutes) } ?: "Нет") }, onClick = { allDay = o; menu = null })
                    }
                }
                SwitchRow("Для новых событий", makeDefault) { makeDefault = it }
                if (onDelete != null) TextButton(onClick = { confirmDelete = true }) { Text("Удалить календарь", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val base = calendar ?: CalendarLayer(name = name.trim(), color = color)
                onSave(base.copy(name = name.trim(), color = color, defaultReminder = timed, defaultAllDayReminder = allDay), makeDefault)
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить календарь «${calendar?.name}»?") },
            text = { Text("Его события уйдут в корзину. Это можно отменить.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}
