package com.claudecode.countdown.ui.habits

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudecode.countdown.container
import com.claudecode.countdown.data.db.Habit
import com.claudecode.countdown.domain.habitDays
import com.claudecode.countdown.domain.habitDaysCode
import com.claudecode.countdown.domain.habitStats
import com.claudecode.countdown.domain.isScheduled
import com.claudecode.countdown.reminders.ReminderNotifier
import com.claudecode.countdown.ui.AppSnackbarHost
import com.claudecode.countdown.ui.ConfirmDialog
import com.claudecode.tiktak.core.shortDayName
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val EMOJIS = listOf("✅", "💧", "🏃", "📚", "🧘", "💪", "🥗", "😴", "💊", "✍️", "🎸", "🌿", "🚭", "💰", "🧹", "🍎")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitsScreen(today: LocalDate) {
    val context = LocalContext.current
    val repo = remember { context.container.habits }
    val scope = rememberCoroutineScope()
    val habits by remember { repo.observeHabits() }.collectAsState(initial = emptyList())
    val checkIns by remember { repo.observeCheckIns() }.collectAsState(initial = emptyList())
    val countsByHabit = remember(checkIns) {
        checkIns.groupBy { it.habitId }.mapValues { (_, list) -> list.associate { it.day to it.count } }
    }
    var editing by remember { mutableStateOf<Habit?>(null) }
    var creating by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<Habit?>(null) }
    val archived by remember { repo.observeArchived() }.collectAsState(initial = emptyList())
    var showArchive by remember { mutableStateOf(false) }
    val undo = remember { context.container.undo }

    fun setCount(habit: Habit, day: LocalDate, count: Int) = scope.launch { repo.setCount(habit.id, day, count) }

    fun setArchived(habit: Habit, archive: Boolean) = scope.launch {
        repo.setArchived(habit, archive)
        undo.offer(if (archive) "«${habit.name}» в архиве" else "«${habit.name}» возвращена") { repo.setArchived(habit, !archive) }
    }

    fun delete(habit: Habit) = scope.launch {
        repo.delete(habit)
        undo.offer("Привычка удалена") { repo.restore(habit) }
    }

    Scaffold(
        snackbarHost = { AppSnackbarHost() },
        topBar = {
            TopAppBar(
                title = { Text("Привычки") },
                actions = {
                    if (archived.isNotEmpty()) {
                        IconButton(onClick = { showArchive = true }) { Icon(Icons.Outlined.Archive, "Архив привычек") }
                    }
                    IconButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, "Новая привычка") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (habits.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Привычек пока нет", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { creating = true }) { Text("+ Создать привычку") }
                if (archived.isNotEmpty()) {
                    TextButton(onClick = { showArchive = true }) { Text("Архив (${archived.size})") }
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(habits, key = { it.id }) { habit ->
                    HabitCard(
                        habit = habit,
                        counts = countsByHabit[habit.id].orEmpty(),
                        today = today,
                        onSetCount = { day, count -> setCount(habit, day, count) },
                        onOpen = { details = habit },
                    )
                }
            }
        }
    }

    if (creating || editing != null) {
        HabitDialog(
            initial = editing,
            onSave = { habit ->
                scope.launch { repo.save(habit, isNew = editing == null) }
                creating = false
                editing = null
            },
            onDismiss = { creating = false; editing = null },
        )
    }
    details?.let { habit ->
        HabitDetails(
            habit = habit,
            counts = countsByHabit[habit.id].orEmpty(),
            today = today,
            onEdit = { details = null; editing = habit },
            onArchive = { setArchived(habit, true); details = null },
            onDelete = { delete(habit); details = null },
            onToggle = { day ->
                val current = countsByHabit[habit.id]?.get(day.toEpochDay()) ?: 0
                setCount(habit, day, if (current >= habit.goal) 0 else habit.goal)
            },
            onDismiss = { details = null },
        )
    }
    if (showArchive) {
        ArchiveDialog(
            habits = archived,
            onRestore = { setArchived(it, false) },
            onDelete = { delete(it) },
            onDismiss = { showArchive = false },
        )
    }
}

@Composable
private fun ArchiveDialog(habits: List<Habit>, onRestore: (Habit) -> Unit, onDelete: (Habit) -> Unit, onDismiss: () -> Unit) {
    // Closes by itself once the last habit leaves the archive.
    if (habits.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    var confirmDelete by remember { mutableStateOf<Habit?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Архив привычек") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (habit in habits) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${habit.emoji} ${habit.name}", Modifier.weight(1f), maxLines = 2)
                        TextButton(onClick = { onRestore(habit) }) { Text("Вернуть") }
                        IconButton(onClick = { confirmDelete = habit }) {
                            Icon(Icons.Outlined.DeleteForever, "Удалить", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
    confirmDelete?.let { habit ->
        ConfirmDialog(
            title = "Удалить привычку?",
            text = "История отметок «${habit.name}» будет удалена.",
            confirmLabel = "Удалить",
            onConfirm = { onDelete(habit) },
            onDismiss = { confirmDelete = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HabitCard(
    habit: Habit,
    counts: Map<Long, Int>,
    today: LocalDate,
    onSetCount: (LocalDate, Int) -> Unit,
    onOpen: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val stats = remember(habit, counts, today) { habitStats(habit, counts, today) }
    val todayCount = counts[today.toEpochDay()] ?: 0
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surfaceContainerLow)
            .clickable(onClick = onOpen)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(scheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Text(habit.emoji, fontSize = 22.sp) }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(habit.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Серия: ${stats.currentStreak} · Лучшая: ${stats.bestStreak}" +
                        if (habit.goal > 1) " · Сегодня $todayCount/${habit.goal}" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
            // Main button: tick (goal 1) or +1 (larger goals); long press resets today.
            val done = todayCount >= habit.goal
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (done) scheme.primary else Color.Transparent)
                    .border(2.dp, scheme.primary, CircleShape)
                    .combinedClickable(
                        enabled = habit.isScheduled(today) || todayCount > 0,
                        onClick = {
                            onSetCount(today, if (habit.goal == 1) (if (done) 0 else 1) else minOf(todayCount + 1, habit.goal))
                        },
                        onLongClick = { onSetCount(today, 0) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (done) Icon(Icons.Filled.Check, "Выполнено", tint = scheme.onPrimary)
                else if (habit.goal > 1) Text("+1", color = scheme.primary, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            for (i in 6 downTo 0) {
                val day = today.minusDays(i.toLong())
                val count = counts[day.toEpochDay()] ?: 0
                val scheduled = habit.isScheduled(day)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        shortDayName(day.dayOfWeek),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (day == today) scheme.primary else scheme.onSurfaceVariant,
                    )
                    Box(
                        Modifier
                            .padding(top = 2.dp)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    count >= habit.goal -> scheme.primary
                                    count > 0 -> scheme.primary.copy(alpha = 0.4f)
                                    scheduled -> scheme.surfaceVariant
                                    else -> Color.Transparent
                                }
                            )
                            .clickable { onSetCount(day, if (count >= habit.goal) 0 else habit.goal) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${day.dayOfMonth}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (count >= habit.goal) scheme.onPrimary else scheme.onSurface.copy(alpha = if (scheduled) 1f else 0.4f),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun HabitDialog(initial: Habit?, onSave: (Habit) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var emoji by remember { mutableStateOf(initial?.emoji ?: EMOJIS.first()) }
    var days by remember { mutableStateOf(habitDays(initial?.days.orEmpty())) }
    var goal by remember { mutableStateOf(initial?.goal ?: 1) }
    var reminder by remember { mutableStateOf(initial?.reminderMinute) }
    var pickTime by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Новая привычка" else "Привычка") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (e in EMOJIS) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(if (e == emoji) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { emoji = e },
                            contentAlignment = Alignment.Center,
                        ) { Text(e, fontSize = 20.sp) }
                    }
                }
                Text("Дни", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (d in DayOfWeek.entries) {
                        FilterChip(
                            selected = d in days,
                            onClick = { days = if (d in days && days.size > 1) days - d else days + d },
                            label = { Text(shortDayName(d)) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Цель в день", Modifier.weight(1f))
                    TextButton(onClick = { goal = (goal - 1).coerceAtLeast(1) }) { Text("−") }
                    Text("$goal", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { goal = (goal + 1).coerceAtMost(50) }) { Text("+") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Напоминание")
                        reminder?.let {
                            Text(
                                "%02d:%02d".format(it / 60, it % 60),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { pickTime = true },
                            )
                        }
                    }
                    Switch(reminder != null, { on ->
                        if (on) {
                            pickTime = true
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !ReminderNotifier.canNotify(context)) {
                                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        } else reminder = null
                    })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val base = initial ?: Habit(name = name.trim())
                onSave(base.copy(name = name.trim(), emoji = emoji, days = habitDaysCode(days), goal = goal, reminderMinute = reminder))
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )

    if (pickTime) {
        val initialMinute = reminder ?: (9 * 60)
        val state = rememberTimePickerState(initialMinute / 60, initialMinute % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = { TextButton(onClick = { reminder = state.hour * 60 + state.minute; pickTime = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Отмена") } },
            text = { TimePicker(state) },
        )
    }
}

@Composable
private fun HabitDetails(
    habit: Habit,
    counts: Map<Long, Int>,
    today: LocalDate,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val stats = habitStats(habit, counts, today)
    var month by remember { mutableStateOf(today.withDayOfMonth(1)) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${habit.emoji} ${habit.name}") },
        text = {
            Column {
                Row {
                    DetailStat("Серия", "${stats.currentStreak}", Modifier.weight(1f))
                    DetailStat("Лучшая", "${stats.bestStreak}", Modifier.weight(1f))
                    DetailStat("Всего", "${stats.totalDone}", Modifier.weight(1f))
                    DetailStat("Месяц", "${stats.monthRate}%", Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹") }
                    Text(
                        month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale("ru"))).replaceFirstChar { it.uppercase() },
                        Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = { month = month.plusMonths(1) }, enabled = month < today.withDayOfMonth(1)) { Text("›") }
                }
                val offset = month.dayOfWeek.value - 1
                val cells = offset + month.lengthOfMonth()
                for (week in 0 until (cells + 6) / 7) {
                    Row {
                        for (d in 0 until 7) {
                            val index = week * 7 + d - offset
                            if (index < 0 || index >= month.lengthOfMonth()) {
                                Spacer(Modifier.weight(1f).aspectRatio(1f))
                            } else {
                                val day = month.plusDays(index.toLong())
                                val count = counts[day.toEpochDay()] ?: 0
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .padding(2.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                count >= habit.goal -> scheme.primary
                                                count > 0 -> scheme.primary.copy(alpha = 0.4f)
                                                else -> Color.Transparent
                                            }
                                        )
                                        .clickable(enabled = !day.isAfter(today)) { onToggle(day) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "${day.dayOfMonth}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = when {
                                            count >= habit.goal -> scheme.onPrimary
                                            day.isAfter(today) || !habit.isScheduled(day) -> scheme.onSurface.copy(alpha = 0.35f)
                                            else -> scheme.onSurface
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                Row(Modifier.padding(top = 8.dp)) {
                    TextButton(onClick = onEdit) { Text("Изменить") }
                    TextButton(onClick = onArchive) { Text("В архив") }
                    TextButton(onClick = { confirmDelete = true }) { Text("Удалить", color = scheme.error) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
    if (confirmDelete) {
        ConfirmDialog(
            title = "Удалить привычку?",
            text = "История отметок «${habit.name}» будет удалена.",
            confirmLabel = "Удалить",
            onConfirm = onDelete,
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun DetailStat(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
