package com.claudecode.countdown.ui.matrix

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.claudecode.countdown.data.db.Priority
import com.claudecode.countdown.domain.GroupKind
import com.claudecode.countdown.domain.TaskFilter
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
    val open = snapshot.groups(TaskFilter.All).filter { it.kind != GroupKind.DONE }.flatMap { it.tasks }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Матрица Эйзенхауэра") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(8.dp)) {
            for (row in QUADRANTS.chunked(2)) {
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    for (q in row) {
                        QuadrantCard(
                            quadrant = q,
                            snapshot = snapshot,
                            tasks = open.filter { it.priority == q.priority },
                            vm = vm,
                            onOpenTask = onOpenTask,
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
    tasks: List<com.claudecode.countdown.data.db.Task>,
    vm: TasksViewModel,
    onOpenTask: (String) -> Unit,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val color = priorityColor(quadrant.priority, Color(0xFF9E9E9E))
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(scheme.surfaceContainerLow)) {
        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(color, 10)
            Spacer(Modifier.size(8.dp))
            Column(Modifier.weight(1f)) {
                Text(quadrant.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = color)
                Text(quadrant.subtitle, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            Text("${tasks.size}", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        }
        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Нет задач", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(top = 4.dp)) {
                items(tasks, key = { it.id }) { task ->
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        TaskRow(
                            task, snapshot, showList = false,
                            onToggle = { vm.toggleDone(task) },
                            onClick = { onOpenTask(task.id) },
                            onLongClick = { menu = true },
                            compact = true,
                        )
                        DropdownMenu(menu, { menu = false }) {
                            Text(
                                "Переместить в",
                                style = MaterialTheme.typography.labelMedium,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                            for (q in QUADRANTS.filter { it.priority != task.priority }) {
                                DropdownMenuItem(
                                    text = { Text(q.title) },
                                    leadingIcon = { Dot(priorityColor(q.priority, Color(0xFF9E9E9E))) },
                                    onClick = { menu = false; vm.setPriority(task, q.priority) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
