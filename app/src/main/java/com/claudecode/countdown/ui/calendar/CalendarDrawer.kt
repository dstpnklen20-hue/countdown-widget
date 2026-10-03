package com.claudecode.countdown.ui.calendar

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.claudecode.countdown.data.Settings
import com.claudecode.countdown.data.db.CalendarLayer

/** What the calendar's side menu can ask for. */
internal class DrawerActions(
    val onMode: (CalendarMode) -> Unit,
    val onCalendarShown: (String, Boolean) -> Unit,
    val onTasksShown: (Boolean) -> Unit,
    val onHolidays: (Boolean) -> Unit,
    val onBirthdays: (Boolean) -> Unit,
    val onSearch: () -> Unit,
    val onManage: () -> Unit,
    val onImport: () -> Unit,
    val onExport: () -> Unit,
    val onSettings: () -> Unit,
)

/**
 * The calendar's side menu, as in Google Calendar: the views, then every calendar with a
 * checkbox in its colour (hiding one keeps its events), tasks, holidays and birthdays.
 */
@Composable
internal fun CalendarDrawer(mode: CalendarMode, settings: Settings, calendars: List<CalendarLayer>, actions: DrawerActions) {
    val scheme = MaterialTheme.colorScheme
    ModalDrawerSheet(Modifier.width(300.dp)) {
        Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                "Календарь",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 12.dp),
            )
            for (m in CalendarMode.entries) {
                NavigationDrawerItem(
                    label = { Text(m.label) },
                    icon = { Icon(m.icon, null) },
                    selected = m == mode,
                    onClick = { actions.onMode(m) },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionLabel("Календари")
            for (c in calendars) {
                LayerRow(c.name, Color(c.color), c.id !in settings.hiddenCalendars) { actions.onCalendarShown(c.id, it) }
            }
            LayerRow("Задачи", scheme.primary, settings.calendarTasks, Icons.Outlined.TaskAlt) { actions.onTasksShown(it) }
            LayerRow("Праздники России", Color(0xFF0B8043), settings.showHolidays, Icons.Outlined.Celebration) { actions.onHolidays(it) }
            LayerRow("Дни рождения", Color(0xFFF4511E), settings.showBirthdays, Icons.Outlined.Cake) { actions.onBirthdays(it) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            DrawerLink("Поиск", Icons.Outlined.Search, actions.onSearch)
            DrawerLink("Календари и цвета", Icons.Outlined.EditCalendar, actions.onManage)
            DrawerLink("Импорт из .ics", Icons.Outlined.FileDownload, actions.onImport)
            DrawerLink("Экспорт в .ics", Icons.Outlined.FileUpload, actions.onExport)
            DrawerLink("Настройки календаря", Icons.Outlined.Settings, actions.onSettings)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun LayerRow(name: String, color: Color, checked: Boolean, icon: ImageVector? = null, onChange: (Boolean) -> Unit) {
    NavigationDrawerItem(
        label = { Text(name) },
        icon = {
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(checkedColor = color, uncheckedColor = color),
            )
        },
        badge = icon?.let { { Icon(it, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) } },
        selected = false,
        onClick = { onChange(!checked) },
    )
}

@Composable
private fun DrawerLink(label: String, icon: ImageVector, onClick: () -> Unit) {
    NavigationDrawerItem(label = { Text(label) }, icon = { Icon(icon, null) }, selected = false, onClick = onClick)
}
