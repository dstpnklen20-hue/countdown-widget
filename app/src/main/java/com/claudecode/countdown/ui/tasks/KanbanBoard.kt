package com.claudecode.countdown.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.claudecode.countdown.data.db.Section
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.data.db.TaskList
import com.claudecode.countdown.domain.GroupKind
import com.claudecode.countdown.domain.TaskFilter
import com.claudecode.countdown.ui.ConfirmDialog
import com.claudecode.countdown.ui.TextInputDialog

private data class Column(val section: Section?, val tasks: List<Task>)

/** Board for a list: one column per section plus "Без раздела"; done tasks are left out. */
@Composable
fun KanbanBoard(
    vm: TasksViewModel,
    snapshot: Snapshot,
    list: TaskList,
    onOpenTask: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    val sections = snapshot.sections.filter { it.listId == list.id }
    val open = snapshot.groups(TaskFilter.ListFilter(list.id)).filter { it.kind != GroupKind.DONE }.flatMap { it.tasks }
    val sectionIds = sections.mapTo(HashSet()) { it.id }
    val unsectioned = open.filter { it.sectionId == null || it.sectionId !in sectionIds }
    val columns = buildList {
        add(Column(null, unsectioned))
        for (s in sections) add(Column(s, open.filter { it.sectionId == s.id }))
    }
    var newColumn by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Section?>(null) }
    var deleting by remember { mutableStateOf<Section?>(null) }
    var adding by remember { mutableStateOf<Column?>(null) }

    LazyRow(
        modifier = Modifier.padding(contentPadding).fillMaxHeight(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(columns, key = { it.section?.id ?: "none" }) { column ->
            KanbanColumn(
                column = column,
                sections = sections,
                snapshot = snapshot,
                vm = vm,
                onOpenTask = onOpenTask,
                onAdd = { adding = column },
                onRename = { renaming = column.section },
                onDelete = { deleting = column.section },
            )
        }
        item(key = "new") {
            TextButton(onClick = { newColumn = true }, modifier = Modifier.width(200.dp)) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.size(6.dp))
                Text("Новая колонка")
            }
        }
    }

    if (newColumn) {
        TextInputDialog(
            title = "Новая колонка",
            confirmLabel = "Создать",
            onConfirm = { vm.createSection(list.id, it); newColumn = false },
            onDismiss = { newColumn = false },
        )
    }
    renaming?.let { s ->
        TextInputDialog(
            title = "Колонка",
            initial = s.name,
            onConfirm = { vm.renameSection(s, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { s ->
        ConfirmDialog(
            title = "Удалить колонку?",
            text = "Задачи из «${s.name}» останутся в списке без раздела.",
            confirmLabel = "Удалить",
            onConfirm = { vm.deleteSection(s) },
            onDismiss = { deleting = null },
        )
    }
    adding?.let { column ->
        TextInputDialog(
            title = "Задача в «${column.section?.name ?: "Без раздела"}»",
            confirmLabel = "Добавить",
            onConfirm = { vm.addTask(it, list.id, column.section?.id); adding = null },
            onDismiss = { adding = null },
        )
    }
}

@Composable
private fun KanbanColumn(
    column: Column,
    sections: List<Section>,
    snapshot: Snapshot,
    vm: TasksViewModel,
    onOpenTask: (String) -> Unit,
    onAdd: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .width(280.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surfaceContainerLow)
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                column.section?.name ?: "Без раздела",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.size(8.dp))
            Text("${column.tasks.size}", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, "Добавить задачу") }
            if (column.section != null) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Ещё") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Переименовать") }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text("Удалить") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        }
        HorizontalDivider()
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(column.tasks, key = { it.id }) { task ->
                var moveMenu by remember { mutableStateOf(false) }
                Box(Modifier.clip(RoundedCornerShape(12.dp))) {
                    TaskRow(
                        task, snapshot, showList = false,
                        onToggle = { vm.toggleDone(task) },
                        onClick = { onOpenTask(task.id) },
                        onLongClick = { moveMenu = true },
                    )
                    DropdownMenu(moveMenu, { moveMenu = false }) {
                        Text(
                            "Переместить в",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                        if (task.sectionId != null) {
                            DropdownMenuItem(text = { Text("Без раздела") }, onClick = { moveMenu = false; vm.moveToSection(task, null) })
                        }
                        for (s in sections.filter { it.id != task.sectionId }) {
                            DropdownMenuItem(text = { Text(s.name) }, onClick = { moveMenu = false; vm.moveToSection(task, s.id) })
                        }
                    }
                }
            }
            if (column.tasks.isEmpty()) {
                item {
                    Text(
                        "Пусто",
                        color = scheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onAdd).padding(12.dp),
                    )
                }
            }
        }
    }
}
