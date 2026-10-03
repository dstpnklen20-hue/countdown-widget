package com.claudecode.countdown.ui.settings

import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claudecode.countdown.ThemeManager
import com.claudecode.countdown.Updater
import com.claudecode.countdown.container
import com.claudecode.countdown.data.AppSettings
import com.claudecode.countdown.data.barLayout
import com.claudecode.countdown.ui.EMPTY_ARTS
import com.claudecode.countdown.ui.EmptyArt
import com.claudecode.countdown.ui.Motion
import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.saveable.rememberSaveable
import com.claudecode.countdown.data.StartList
import com.claudecode.countdown.domain.formatMinuteOfDay
import com.claudecode.countdown.ui.AppSnackbarHost
import com.claudecode.countdown.widget.CountdownWidgetProvider
import com.claudecode.countdown.widget.QuickAddWidgetReceiver
import com.claudecode.countdown.widget.ScheduleWidgetReceiver
import com.claudecode.countdown.widget.TodayWidgetReceiver
import kotlinx.coroutines.launch

/**
 * App settings. [onBack] is null when the screen is a bottom-bar tab. [onThemeChanged] lets the
 * activity repaint everything after a theme option changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: (() -> Unit)?, onOpenTrash: () -> Unit, onOpenToolbar: () -> Unit, onThemeChanged: () -> Unit) {
    val context = LocalContext.current
    val appSettings = remember { context.container.settings }
    val settings by appSettings.state.collectAsStateWithLifecycle()
    // Theme values live in ThemeManager's prefs; bumping this re-reads them.
    var themeVersion by remember { mutableIntStateOf(0) }
    fun themeChanged() {
        themeVersion++
        onThemeChanged()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            Section("Панель инструментов") {
                val layout = barLayout(settings.tools, settings.barLimit)
                SettingRow(
                    "Разделы на панели",
                    buildString {
                        append(layout.visible.joinToString(", ") { it.label })
                        if (layout.more.isNotEmpty()) append("; в «Ещё»: ").append(layout.more.joinToString(", ") { it.label })
                    },
                    onClick = onOpenToolbar,
                )
            }

            Section("Задачи") {
                var startMenu by remember { mutableStateOf(false) }
                Box {
                    SettingRow("При открытии показывать", settings.startList.label, onClick = { startMenu = true })
                    DropdownMenu(startMenu, { startMenu = false }) {
                        for (option in StartList.entries) {
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = { appSettings.setStartList(option); startMenu = false },
                            )
                        }
                    }
                }
                SwitchRow("Показывать выполненные в списках", checked = settings.showCompleted) { appSettings.setShowCompleted(it) }
                var pickTime by remember { mutableStateOf(false) }
                SettingRow(
                    "Напоминать о задачах на весь день",
                    "в ${formatMinuteOfDay(settings.allDayReminderMinutes)}",
                    onClick = { pickTime = true },
                )
                if (pickTime) {
                    TimeDialog(settings.allDayReminderMinutes, onDismiss = { pickTime = false }) { minutes ->
                        appSettings.setAllDayReminderMinutes(minutes)
                        context.container.appScope.launch { context.container.reminders.reschedule() }
                        pickTime = false
                    }
                }
            }

            Section("Оформление") {
                var part by rememberSaveable { mutableStateOf(ThemePart.APP) }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                    ThemePart.entries.forEachIndexed { i, value ->
                        SegmentedButton(
                            selected = part == value,
                            onClick = { part = value },
                            shape = SegmentedButtonDefaults.itemShape(i, ThemePart.entries.size),
                            icon = {},
                        ) { Text(value.label, maxLines = 1) }
                    }
                }
                AnimatedContent(part, transitionSpec = { Motion.sectionChange() }, label = "themeTarget") { p ->
                    val t = if (p == ThemePart.WIDGETS) ThemeManager.Target.WIDGETS else ThemeManager.Target.APP
                    Column {
                        if (p == ThemePart.ICON) {
                            IconSettings()
                        } else if (p == ThemePart.WIDGETS) {
                            val own = remember(themeVersion) { ThemeManager.widgetsHaveOwnTheme(context) }
                            Spacer(Modifier.height(8.dp))
                            SwitchRow("Свои цвета для виджетов", checked = own) {
                                ThemeManager.setWidgetsHaveOwnTheme(context, it)
                                themeChanged()
                            }
                            Text(
                                if (own) "Виджеты на главном экране оформлены отдельно от приложения."
                                else "Сейчас виджеты повторяют оформление приложения.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            if (own) ThemeSettings(t, themeVersion, ::themeChanged)
                            else Spacer(Modifier.height(16.dp))
                        } else {
                            ThemeSettings(t, themeVersion, ::themeChanged)
                            EmptyArtSetting(settings.emptyArt, appSettings::setEmptyArt)
                        }
                    }
                }
            }

            Section("Уведомления") {
                NotificationSettings()
            }

            Section("Виджеты") {
                val manager = AppWidgetManager.getInstance(context)
                if (manager.isRequestPinAppWidgetSupported) {
                    for ((label, provider) in listOf(
                        "«Расписание»" to ScheduleWidgetReceiver::class.java,
                        "«Сегодня»" to TodayWidgetReceiver::class.java,
                        "«Быстро добавить»" to QuickAddWidgetReceiver::class.java,
                        "«Обратный отсчёт»" to CountdownWidgetProvider::class.java,
                    )) {
                        SettingRow("Добавить виджет $label", onClick = {
                            manager.requestPinAppWidget(ComponentName(context, provider), null, null)
                        })
                    }
                } else {
                    SettingRow("Виджеты добавляются с главного экрана: удерживайте пустое место → «Виджеты»")
                }
            }

            Section("Синхронизация") {
                SyncSettings()
            }

            Section("Данные") {
                SettingRow("Корзина", "Удалённые задачи можно вернуть", onClick = onOpenTrash)
                BackupSettings()
            }

            Section("О приложении") {
                var status by remember { mutableStateOf<String?>(null) }
                SettingRow("Версия", Updater.currentVersionName(context))
                SettingRow("Проверить обновления", status, onClick = {
                    (context as? AppCompatActivity)?.let { activity ->
                        Updater.checkForUpdates(activity, manual = true) { status = it }
                    }
                })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 8.dp),
    )
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow),
        content = content,
    )
}

@Composable
internal fun SettingRow(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onClick != null) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ThemeSettings(target: ThemeManager.Target, version: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val mode = remember(version, target) { ThemeManager.getMode(context, target) }
    val accent = remember(version, target) { ThemeManager.getAccentOverride(context, target) }
    val presetId = remember(version, target) { ThemeManager.getPresetId(context, target) }
    val isDark = remember(version, target) { ThemeManager.palette(context, target).isDark }

    Column(Modifier.padding(16.dp)) {
        Text("Режим", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        val modes = listOf(ThemeManager.MODE_SYSTEM to "Как в системе", ThemeManager.MODE_LIGHT to "Светлая", ThemeManager.MODE_DARK to "Тёмная")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { i, (value, label) ->
                SegmentedButton(
                    selected = mode == value,
                    onClick = { ThemeManager.setMode(context, value, target); onChanged() },
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    icon = {},
                ) { Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Акцентный цвет", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (color in ThemeManager.ACCENTS) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(color))
                        .border(if (accent == color) 3.dp else 0.dp, scheme.onSurface, CircleShape)
                        .clickable { ThemeManager.setAccentOverride(context, color, target); onChanged() }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        FilterChip(
            selected = accent == null,
            onClick = { ThemeManager.setAccentOverride(context, null, target); onChanged() },
            label = { Text("Цвет из темы") },
        )

        Spacer(Modifier.height(12.dp))
        Text("Готовые темы", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        // Twenty themes would push every other setting far down: show a few until asked.
        var showAll by remember { mutableStateOf(false) }
        val selectedIndex = ThemeManager.PRESETS.indexOfFirst { it.id == presetId }
        val shown = if (showAll || selectedIndex >= COLLAPSED_PRESETS) ThemeManager.PRESETS else ThemeManager.PRESETS.take(COLLAPSED_PRESETS)
        for (row in shown.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (preset in row) {
                    val sc = if (isDark) preset.dark else preset.light
                    val selected = preset.id == presetId
                    Row(
                        Modifier
                            .weight(1f)
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(sc.bg))
                            .border(if (selected) 3.dp else 1.dp, Color(if (selected) sc.accent else sc.surface), RoundedCornerShape(12.dp))
                            .clickable { ThemeManager.setPreset(context, preset.id, target); onChanged() }
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            preset.name,
                            color = Color(sc.text),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        for (c in listOf(sc.surface, sc.accent)) {
                            Box(Modifier.padding(start = 4.dp).size(14.dp).clip(CircleShape).background(Color(c)))
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (shown.size < ThemeManager.PRESETS.size) {
            TextButton(onClick = { showAll = true }) { Text("Показать все темы (${ThemeManager.PRESETS.size})") }
        }
    }
}

private const val COLLAPSED_PRESETS = 6

/** What the "Оформление" section styles: the app, the home-screen widgets, or the launcher icon. */
private enum class ThemePart(val label: String) { APP("Приложение"), WIDGETS("Виджеты"), ICON("Значок") }

/** Which picture empty lists show: one of the gallery, or a different one every day. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyArtSetting(selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
    SettingRow(
        "Картинка в пустом списке",
        EMPTY_ARTS.getOrNull(selected)?.name ?: "Разные каждый день",
        onClick = { open = true },
    )
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Готово") } },
        title = { Text("Картинка в пустом списке") },
        text = {
            Column {
                FilterChip(
                    selected = selected == AppSettings.EMPTY_ART_DAILY,
                    onClick = { onSelect(AppSettings.EMPTY_ART_DAILY) },
                    label = { Text("Разные каждый день") },
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EMPTY_ARTS.forEachIndexed { i, art ->
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .border(
                                    if (i == selected) 2.dp else 0.dp,
                                    if (i == selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    RoundedCornerShape(12.dp),
                                )
                                .clickable { onSelect(i) }
                                .padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // On the list's own background, as it will look there.
                            EmptyArt(i, Modifier.size(76.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.background))
                            Text(
                                art.name,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 2,
                                minLines = 2,
                                modifier = Modifier.width(76.dp).padding(top = 4.dp),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        },
    )
}

/** Shows whether reminders can reach the user and links to the system switches that fix it. */
@Composable
private fun NotificationSettings() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    // The user comes back from system settings: re-read the permissions.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val notificationsOn = remember(refresh) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    SettingRow(
        "Уведомления",
        if (notificationsOn) "Включены" else "Выключены — напоминания не будут видны",
        onClick = { context.startActivity(appNotificationSettings(context)) },
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val exact = remember(refresh) { context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        SettingRow(
            "Точное время напоминаний",
            if (exact) "Разрешено" else "Запрещено — напоминания могут опаздывать. Нажмите, чтобы разрешить",
            onClick = if (exact) null else {
                {
                    runCatching {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                        )
                    }
                }
            },
        )
    }
}

private fun appNotificationSettings(context: Context): Intent =
    Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeDialog(minutes: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = minutes / 60, initialMinute = minutes % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("Готово") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        text = { TimePicker(state) },
    )
}
