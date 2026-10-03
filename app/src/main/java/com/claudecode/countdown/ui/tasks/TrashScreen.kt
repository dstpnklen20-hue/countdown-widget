package com.claudecode.countdown.ui.tasks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.domain.localDateOf
import com.claudecode.countdown.ui.AppSnackbarHost
import com.claudecode.countdown.ui.ConfirmDialog
import com.claudecode.countdown.ui.formatDay
import com.claudecode.countdown.ui.formatTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(vm: TasksViewModel, snapshot: Snapshot, onBack: () -> Unit) {
    val trash by vm.trash.collectAsStateWithLifecycle()
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmPurge by remember { mutableStateOf<Task?>(null) }
    val scheme = MaterialTheme.colorScheme

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Корзина") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                actions = {
                    if (!trash.isNullOrEmpty()) TextButton(onClick = { confirmEmpty = true }) { Text("Очистить") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background),
            )
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        val items = trash ?: return@Scaffold
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "Корзина пуста.\nУдалённые задачи и события попадают сюда, их можно вернуть в течение 30 дней.",
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                Text(
                    "Задачи хранятся в корзине, пока вы её не очистите.",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(items, key = { it.id }) { task ->
                TrashRow(
                    task = task,
                    place = trashPlace(task, snapshot),
                    deletedAt = task.updatedAt,
                    snapshot = snapshot,
                    onRestore = { vm.restoreFromTrash(task) },
                    onPurge = { confirmPurge = task },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

    if (confirmEmpty) {
        ConfirmDialog(
            title = "Очистить корзину?",
            text = "Задачи будут удалены навсегда, вернуть их будет нельзя.",
            confirmLabel = "Очистить",
            onConfirm = { vm.emptyTrash() },
            onDismiss = { confirmEmpty = false },
        )
    }
    confirmPurge?.let { task ->
        ConfirmDialog(
            title = "Удалить навсегда?",
            text = "«${task.title}» нельзя будет вернуть.",
            confirmLabel = "Удалить",
            onConfirm = { vm.purge(task) },
            onDismiss = { confirmPurge = null },
        )
    }
}

/** Where the task returns on restore. */
private fun trashPlace(task: Task, snapshot: Snapshot): String {
    if (task.parentId != null) {
        val parent = snapshot.tasks.firstOrNull { it.id == task.parentId }
        return "Подзадача" + (parent?.let { " «${it.title}»" } ?: "")
    }
    return snapshot.listsById[task.listId]?.name ?: "Список удалён → во «Входящие»"
}

@Composable
private fun TrashRow(
    task: Task,
    place: String,
    deletedAt: Long,
    snapshot: Snapshot,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(task.title.ifBlank { "Без названия" }, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "$place · удалено ${formatDay(localDateOf(deletedAt), snapshot.today).lowercase()}, ${formatTime(deletedAt)}",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRestore) { Icon(Icons.Outlined.Restore, "Вернуть", tint = scheme.primary) }
        IconButton(onClick = onPurge) { Icon(Icons.Outlined.DeleteForever, "Удалить навсегда", tint = scheme.error) }
    }
}
