package com.claudecode.countdown.ui.matrix

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.style.TextAlign
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.ui.tasks.QuickAddSheet
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.claudecode.countdown.data.db.Priority
import com.claudecode.countdown.domain.MatrixPeriod
import com.claudecode.countdown.domain.TaskFilter
import com.claudecode.countdown.domain.matrixTasks
import com.claudecode.countdown.ui.AppSnackbarHost
import com.claudecode.countdown.ui.Dot
import com.claudecode.countdown.ui.priorityColor
import com.claudecode.countdown.ui.tasks.Snapshot
import com.claudecode.countdown.ui.tasks.TaskRow
import com.claudecode.countdown.ui.tasks.TasksViewModel

/** Quadrants map to priorities, like TickTick's default matrix rules. */
private data class Quadrant(val title: String, val subtitle: String, val priority: Int)

private val QUADRANTS = listOf(
    Quadrant("Срочно и важно", "Высокий приоритет", Priority.HIGH),
    Quadrant("Важно, не срочно", "Средний приоритет", Priority.MEDIUM),
    Quadrant("Срочно, не важно", "Низкий приоритет", Priority.LOW),
    Quadrant("Не срочно и не важно", "Без приоритета", Priority.NONE),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatrixScreen(vm: TasksViewModel, snapshot: Snapshot, onOpenTask: (String) -> Unit) {
    var period by rememberSaveable { mutableStateOf(MatrixPeriod.ALL) }
    val open = remember(snapshot, period) {
        matrixTasks(snapshot.groups(TaskFilter.All).flatMap { it.tasks }, period, snapshot.today)
    }
    var expandedPriority by rememberSaveable { mutableStateOf<Int?>(null) }
    val expanded = QUADRANTS.firstOrNull { it.priority == expandedPriority }
    if (expanded != null) {
        BackHandler { expandedPriority = null }
        ExpandedQuadrant(
            quadrant = expanded,
            period = period,
            tasks = open.filter { it.priority == expanded.priority },
            snapshot = snapshot,
            vm = vm,
            onOpenTask = onOpenTask,
            onBack = { expandedPriority = null },
        )
        return
    }
    Scaffold(
        snackbarHost = { AppSnackbarHost() },
        topBar = {
            TopAppBar(
                title = { Text("Матрица Эйзенхауэра") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 8.dp).padding(bottom = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (p in MatrixPeriod.entries) {
                    FilterChip(selected = period == p, onClick = { period = p }, label = { Text(p.label) })
                }
            }
            for (row in QUADRANTS.chunked(2)) {
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    for (q in row) {
                        QuadrantCard(
                            quadrant = q,
                            snapshot = snapshot,
                            tasks = open.filter { it.priority == q.priority },
                            vm = vm,
                            onOpenTask = onOpenTask,
                            onExpand = { expandedPriority = q.priority },
                            modifier = Modifier.weight(1f).fillMaxSize().padding(4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuadrantCard(
    quadrant: Quadrant,
    snapshot: Snapshot,
    tasks: List<Task>,
    vm: TasksViewModel,
    onOpenTask: (String) -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val color = quadrantColor(quadrant)
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(scheme.surfaceContainerLow)) {
        // Tapping the header (or the empty space) opens the quadrant on the whole screen.
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dot(color, 8)
            Spacer(Modifier.size(6.dp))
            Column(Modifier.weight(1f)) {
                Text(quadrant.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
                Text(quadrant.subtitle, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
            }
            Text("${tasks.size}", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Открыть квадрант", tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxSize().clickable(onClick = onExpand), contentAlignment = Alignment.Center) {
                Text("Нет задач", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(top = 2.dp)) {
                items(tasks, key = { it.id }) { task -> MatrixTaskRow(task, snapshot, vm, onOpenTask, compact = true) }
            }
        }
    }
}

/** One quadrant on the whole screen, with its own "+" that adds tasks straight into it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpandedQuadrant(
    quadrant: Quadrant,
    period: MatrixPeriod,
    tasks: List<Task>,
    snapshot: Snapshot,
    vm: TasksViewModel,
    onOpenTask: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var adding by remember { mutableStateOf(false) }
    Scaffold(
        snackbarHost = { AppSnackbarHost() },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                title = {
                    Column {
                        Text(quadrant.title, color = quadrantColor(quadrant), fontWeight = FontWeight.SemiBold)
                        Text(
                            quadrant.subtitle + if (period != MatrixPeriod.ALL) " · ${period.label.lowercase()}" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { adding = true },
                containerColor = scheme.primary,
                contentColor = scheme.onPrimary,
            ) { Icon(Icons.Filled.Add, "Добавить задачу в этот квадрант") }
        },
    ) { padding ->
        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Задач нет.\nНажмите «+», чтобы добавить.", color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 88.dp)) {
                items(tasks, key = { it.id }) { task -> MatrixTaskRow(task, snapshot, vm, onOpenTask, compact = false) }
            }
        }
    }
    if (adding) {
        QuickAddSheet(onDismiss = { adding = false }, initialPriority = quadrant.priority) { title, due, priority ->
            // With a period chosen, a task without a date would vanish from view: it gets today instead.
            vm.quickAdd(title, if (period == MatrixPeriod.ALL) TaskFilter.All else TaskFilter.Today, due, priority)
        }
    }
}

/** A task in the matrix; long press moves it to another quadrant. */
@Composable
private fun MatrixTaskRow(task: Task, snapshot: Snapshot, vm: TasksViewModel, onOpenTask: (String) -> Unit, compact: Boolean) {
    var menu by remember { mutableStateOf(false) }
    Box {
        TaskRow(
            task, snapshot, showList = !compact,
            onToggle = { vm.toggleDone(task) },
            onClick = { onOpenTask(task.id) },
            onLongClick = { menu = true },
            compact = compact,
        )
        DropdownMenu(menu, { menu = false }) {
            Text(
                "Переместить в",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            for (q in QUADRANTS.filter { it.priority != task.priority }) {
                DropdownMenuItem(
                    text = { Text(q.title) },
                    leadingIcon = { Dot(quadrantColor(q)) },
                    onClick = { menu = false; vm.setPriority(task, q.priority) },
                )
            }
        }
    }
}

private fun quadrantColor(q: Quadrant): Color = priorityColor(q.priority, Color(0xFF9E9E9E))
