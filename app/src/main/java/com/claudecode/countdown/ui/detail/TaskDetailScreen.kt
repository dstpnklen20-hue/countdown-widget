package com.claudecode.countdown.ui.detail

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.claudecode.countdown.domain.allDayReminderPresets
import com.claudecode.countdown.domain.TIMED_REMINDER_PRESETS
import com.claudecode.countdown.domain.reminderLabel
import com.claudecode.countdown.container
import com.claudecode.countdown.reminders.ReminderNotifier
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Repeat
import com.claudecode.countdown.data.db.RepeatFrom
import com.claudecode.countdown.domain.repeatDescription
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claudecode.countdown.data.db.ChecklistItem
import com.claudecode.countdown.data.db.DisplayMode
import com.claudecode.countdown.data.db.Priority
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.domain.dueDay
import com.claudecode.countdown.domain.isOverdue
import com.claudecode.countdown.domain.today
import com.claudecode.countdown.ui.AppSnackbarHost
import com.claudecode.countdown.ui.ColorPicker
import com.claudecode.countdown.ui.ConfirmDialog
import com.claudecode.countdown.ui.DueDateDialog
import com.claudecode.countdown.ui.PriorityCheckbox
import com.claudecode.countdown.ui.PriorityMenu
import com.claudecode.countdown.ui.formatCountdown
import com.claudecode.countdown.ui.formatDue
import com.claudecode.countdown.ui.formatFullDate
import com.claudecode.countdown.ui.formatTime
import com.claudecode.countdown.ui.priorityColor
import com.claudecode.countdown.ui.priorityName
import com.claudecode.countdown.ui.rememberNow

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskDetailScreen(
    vm: TaskDetailViewModel,
    onBack: () -> Unit,
    onOpenTask: (String) -> Unit,
) {
    val task by vm.task.collectAsStateWithLifecycle()
    val checklist by vm.checklist.collectAsStateWithLifecycle()
    val subtasks by vm.subtasks.collectAsStateWithLifecycle()
    val tags by vm.tagNames.collectAsStateWithLifecycle()
    val allTags by vm.allTags.collectAsStateWithLifecycle()
    val lists by vm.lists.collectAsStateWithLifecycle()

    var pickDate by remember { mutableStateOf(false) }
    var priorityMenu by remember { mutableStateOf(false) }
    var listMenu by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickRepeat by remember { mutableStateOf(false) }
    var reminderMenu by remember { mutableStateOf(false) }
    val reminders by vm.reminders.collectAsStateWithLifecycle()
    val allDayMinutes = LocalContext.current.container.settings.state.collectAsStateWithLifecycle().value.allDayReminderMinutes

    val context = LocalContext.current
    var notificationsAllowed by remember { mutableStateOf(ReminderNotifier.canNotify(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAllowed = it
    }
    fun requestNotifications() {
        if (!notificationsAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // Setting a time adds a default reminder, so ask for the permission right then.
    LaunchedEffect(reminders.isNotEmpty()) { if (reminders.isNotEmpty()) requestNotifications() }

    val t = task
    if (t == null || t.deleted) {
        if (vm.loaded && t?.deleted != false) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Задача не найдена")
                    TextButton(onClick = onBack) { Text("Назад") }
                }
            }
        }
        return
    }
    val scheme = MaterialTheme.colorScheme
    val today = today()

    Scaffold(
        snackbarHost = { AppSnackbarHost() },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                title = {
                    Box {
                        Row(
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable { listMenu = true }.padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.List, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(
                                lists.firstOrNull { it.id == t.listId }?.let { if (it.isInbox) "Входящие" else it.name } ?: "",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        DropdownMenu(listMenu, { listMenu = false }) {
                            for (list in lists) {
                                DropdownMenuItem(
                                    text = { Text(if (list.isInbox) "Входящие" else list.name) },
                                    onClick = { vm.setList(list.id); listMenu = false },
                                )
                            }
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { overflow = true }) { Icon(Icons.Filled.MoreVert, "Ещё") }
                        DropdownMenu(overflow, { overflow = false }) {
                            DropdownMenuItem(text = { Text("Удалить") }, onClick = { overflow = false; confirmDelete = true })
                        }
                    }
                },
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
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                PriorityCheckbox(t.isDone, t.priority, { vm.toggleDone() })
                val due = formatDue(t, today)
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable { pickDate = true }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val overdue = t.isOverdue(System.currentTimeMillis(), today)
                    val color = if (overdue) Color(0xFFE53935) else if (due != null) scheme.primary else scheme.onSurfaceVariant
                    Icon(Icons.Outlined.CalendarToday, null, tint = color, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(due ?: "Дата", color = color, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { priorityMenu = true }) {
                        Icon(
                            if (t.priority == Priority.NONE) Icons.Outlined.Flag else Icons.Filled.Flag,
                            priorityName(t.priority),
                            tint = priorityColor(t.priority, scheme.onSurfaceVariant),
                        )
                    }
                    PriorityMenu(priorityMenu, { priorityMenu = false }) { vm.setPriority(it) }
                }
            }

            Row(
                Modifier
                    .padding(start = 56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { pickRepeat = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val repeat = t.repeatDescription()
                val color = if (repeat != null) scheme.primary else scheme.onSurfaceVariant
                Icon(Icons.Filled.Repeat, null, tint = color, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(
                    repeat?.let { if (t.repeatFrom == RepeatFrom.COMPLETION) "$it (от выполнения)" else it } ?: "Не повторять",
                    color = color,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            PlainField(
                value = vm.title,
                onValueChange = vm::onTitleChange,
                placeholder = "Название",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (t.isDone) TextDecoration.LineThrough else null,
                ),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            PlainField(
                value = vm.content,
                onValueChange = vm::onContentChange,
                placeholder = "Описание",
                singleLine = false,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            if (t.displayMode == DisplayMode.COUNTDOWN && t.dueAt != null) CountdownCard(t)

            SectionTitle("Напоминания")
            if (t.dueAt == null) {
                Text(
                    "Задайте дату, чтобы добавить напоминание",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            } else {
                if (reminders.isNotEmpty()) {
                    FlowRow(
                        Modifier.padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        for (r in reminders) {
                            InputChip(
                                selected = false,
                                onClick = { vm.deleteReminder(r) },
                                label = { Text(reminderLabel(r.offsetMinutes ?: 0, t.isAllDay, allDayMinutes)) },
                                leadingIcon = { Icon(Icons.Outlined.Notifications, null, Modifier.size(16.dp)) },
                                trailingIcon = { Icon(Icons.Filled.Close, "Убрать", Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
                run {
                    // Looks like the other "+ Добавить …" rows of this screen.
                    Box {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { reminderMenu = true }
                                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("+", color = scheme.primary, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.size(14.dp))
                            Text("Добавить напоминание", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                        }
                        DropdownMenu(reminderMenu, { reminderMenu = false }) {
                            val presets = if (t.isAllDay) allDayReminderPresets(allDayMinutes) else TIMED_REMINDER_PRESETS
                            for (p in presets) {
                                DropdownMenuItem(text = { Text(p.label) }, onClick = {
                                    reminderMenu = false
                                    vm.addReminder(p.offsetMinutes)
                                    requestNotifications()
                                })
                            }
                        }
                    }
                }
                if (reminders.isNotEmpty() && !notificationsAllowed) {
                    TextButton(onClick = { requestNotifications() }, modifier = Modifier.padding(horizontal = 12.dp)) {
                        Text("Уведомления выключены — разрешить", color = scheme.error)
                    }
                }
            }

            SectionTitle("Чек-лист")
            for (item in checklist) ChecklistRow(item, vm)
            AddRow("Добавить пункт") { vm.addChecklistItem(it) }

            SectionTitle("Подзадачи")
            for (sub in subtasks) SubtaskRow(sub, onToggle = { vm.toggleSubtask(sub) }, onClick = { onOpenTask(sub.id) })
            AddRow("Добавить подзадачу") { vm.addSubtask(it) }

            SectionTitle("Теги")
            FlowRow(
                Modifier.padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (tag in tags) {
                    InputChip(
                        selected = false,
                        onClick = { vm.removeTag(tag) },
                        label = { Text("#$tag") },
                        trailingIcon = { Icon(Icons.Filled.Close, "Убрать", Modifier.size(16.dp)) },
                    )
                }
                for (tag in allTags.filter { it.name !in tags }.take(6)) {
                    AssistChip(onClick = { vm.addTag(tag.name) }, label = { Text("+ ${tag.name}") })
                }
            }
            AddRow("Новый тег") { vm.addTag(it) }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Цвет", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Метка в списке и в календаре; без цвета берётся цвет списка",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                ColorPicker(t.color) { vm.setColor(it) }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Событие с отсчётом", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (t.dueAt == null) "Сначала задайте дату" else "Живёт в «Отсчётах», календаре и на виджете, а не среди задач",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Switch(checked = t.displayMode == DisplayMode.COUNTDOWN, onCheckedChange = { vm.setCountdown(it) })
            }
        }
    }

    if (pickDate) {
        DueDateDialog(
            initialAt = t.dueAt,
            initialAllDay = t.isAllDay,
            onConfirm = { vm.setDue(it); pickDate = false },
            onDismiss = { pickDate = false },
        )
    }
    if (pickRepeat) {
        RepeatDialog(
            anchor = t.dueDay() ?: today,
            currentRule = t.repeatRule,
            currentFrom = t.repeatFrom,
            onConfirm = { rule, from -> vm.setRepeat(rule, from); pickRepeat = false },
            onDismiss = { pickRepeat = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Удалить задачу?",
            text = "«${vm.title}» и её подзадачи будут удалены.",
            confirmLabel = "Удалить",
            onConfirm = { vm.delete(onBack) },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun CountdownCard(task: Task) {
    val now by rememberNow(1_000)
    val due = task.dueAt ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            formatCountdown(due - now, withSeconds = true),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        val day = task.dueDay()
        if (day != null) {
            Text(
                formatFullDate(day) + if (task.isAllDay) "" else ", " + formatTime(due),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ChecklistRow(item: ChecklistItem, vm: TaskDetailViewModel) {
    var text by remember(item.id) { mutableStateOf(item.title) }
    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        PriorityCheckbox(item.checked, Priority.NONE, { vm.toggleChecklistItem(item) })
        PlainField(
            value = text,
            onValueChange = { text = it },
            placeholder = "",
            style = MaterialTheme.typography.bodyLarge.copy(
                textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                color = if (item.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            ),
            onDone = { if (it.isNotBlank() && it != item.title) vm.renameChecklistItem(item, it) },
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { vm.deleteChecklistItem(item) }) {
            Icon(Icons.Filled.Close, "Удалить", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SubtaskRow(sub: Task, onToggle: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PriorityCheckbox(sub.isDone, sub.priority, onToggle)
        Text(
            sub.title,
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (sub.isDone) TextDecoration.LineThrough else null,
            color = if (sub.isDone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(vertical = 10.dp),
        )
    }
}

@Composable
private fun AddRow(placeholder: String, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("+", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(14.dp))
        PlainField(
            value = text,
            onValueChange = { text = it },
            placeholder = placeholder,
            style = MaterialTheme.typography.bodyLarge,
            onDone = {
                if (it.isNotBlank()) onAdd(it.trim())
                text = ""
            },
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
    }
}

/** Borderless text field; [onDone] makes it single-line and fires on the IME action. */
@Composable
private fun PlainField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    onDone: ((String) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val textStyle = if (style.color == Color.Unspecified) style.copy(color = scheme.onSurface) else style
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = textStyle,
        singleLine = singleLine,
        cursorBrush = SolidColor(scheme.primary),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke(value) }),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, style = textStyle.copy(color = scheme.onSurfaceVariant))
                inner()
            }
        },
    )
    Spacer(Modifier.height(0.dp))
}
