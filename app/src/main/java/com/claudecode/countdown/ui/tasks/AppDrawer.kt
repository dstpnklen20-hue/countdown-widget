package com.claudecode.countdown.ui.tasks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AllInbox
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.claudecode.countdown.data.db.Folder
import com.claudecode.countdown.data.db.Tag
import com.claudecode.countdown.data.db.TaskList
import com.claudecode.countdown.domain.TaskFilter
import com.claudecode.countdown.ui.ColorPicker
import com.claudecode.countdown.ui.ConfirmDialog
import com.claudecode.countdown.ui.TextInputDialog

private sealed interface DrawerDialog {
    data class EditList(val list: TaskList?) : DrawerDialog
    data class EditFolder(val folder: Folder?) : DrawerDialog
    data class EditTag(val tag: Tag) : DrawerDialog
    data class DeleteList(val list: TaskList) : DrawerDialog
}

private fun smartIcon(filter: TaskFilter): ImageVector = when (filter) {
    TaskFilter.Inbox -> Icons.Outlined.Inbox
    TaskFilter.Today -> Icons.Outlined.Today
    TaskFilter.Tomorrow -> Icons.Outlined.WbSunny
    TaskFilter.Next7Days -> Icons.Outlined.DateRange
    TaskFilter.All -> Icons.Outlined.AllInbox
    TaskFilter.Countdowns -> Icons.Outlined.HourglassBottom
    TaskFilter.Completed -> Icons.Outlined.CheckCircle
    else -> Icons.AutoMirrored.Outlined.List
}

@Composable
fun AppDrawer(
    vm: TasksViewModel,
    snapshot: Snapshot,
    selected: TaskFilter,
    onSelect: (TaskFilter) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var dialog by remember { mutableStateOf<DrawerDialog?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    val expandedFolders = remember { mutableStateMapOf<String, Boolean>() }
    val userLists = snapshot.lists.filter { !it.isInbox }

    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.background) {
        LazyColumn(Modifier.weight(1f)) {
            item {
                Text(
                    "Tik Tak",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 12.dp),
                )
            }
            items(TaskFilter.SMART, key = { it.key }) { filter ->
                DrawerItem(
                    icon = smartIcon(filter),
                    label = snapshot.title(filter),
                    count = if (filter == TaskFilter.Completed) 0 else snapshot.openCount(filter),
                    selected = selected == filter,
                    onClick = { onSelect(filter) },
                )
            }
            item { SectionHeader("Списки") {
                Box {
                    IconButton(onClick = { addMenu = true }) { Icon(Icons.Filled.Add, "Добавить") }
                    DropdownMenu(addMenu, { addMenu = false }) {
                        DropdownMenuItem(text = { Text("Новый список") }, onClick = { addMenu = false; dialog = DrawerDialog.EditList(null) })
                        DropdownMenuItem(text = { Text("Новая папка") }, onClick = { addMenu = false; dialog = DrawerDialog.EditFolder(null) })
                    }
                }
            } }
            for (folder in snapshot.folders) {
                val expanded = expandedFolders[folder.id] ?: true
                val inFolder = userLists.filter { it.folderId == folder.id }
                item(key = "f:${folder.id}") {
                    DrawerItem(
                        icon = Icons.Outlined.Folder,
                        label = folder.name,
                        count = 0,
                        selected = false,
                        trailing = if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                        onClick = { expandedFolders[folder.id] = !expanded },
                        onLongClick = { dialog = DrawerDialog.EditFolder(folder) },
                    )
                }
                if (expanded) {
                    items(inFolder, key = { "l:${it.id}" }) { list ->
                        ListItem(list, snapshot, selected, indent = true, onSelect) { dialog = DrawerDialog.EditList(list) }
                    }
                }
            }
            val folderIds = snapshot.folders.mapTo(HashSet()) { it.id }
            items(userLists.filter { it.folderId == null || it.folderId !in folderIds }, key = { "l:${it.id}" }) { list ->
                ListItem(list, snapshot, selected, indent = false, onSelect) { dialog = DrawerDialog.EditList(list) }
            }
            if (userLists.isEmpty() && snapshot.folders.isEmpty()) {
                item {
                    TextButton(onClick = { dialog = DrawerDialog.EditList(null) }, modifier = Modifier.padding(start = 12.dp)) {
                        Text("+ Создать список")
                    }
                }
            }
            if (snapshot.tags.isNotEmpty()) {
                item { SectionHeader("Теги") {} }
                items(snapshot.tags, key = { "t:${it.id}" }) { tag ->
                    val filter = TaskFilter.TagFilter(tag.id)
                    DrawerItem(
                        icon = Icons.AutoMirrored.Outlined.Label,
                        iconTint = tag.color?.let { Color(it) },
                        label = tag.name,
                        count = snapshot.openCount(filter),
                        selected = selected == filter,
                        onClick = { onSelect(filter) },
                        onLongClick = { dialog = DrawerDialog.EditTag(tag) },
                    )
                }
                item {
                    Text(
                        "Удерживайте список, папку или тег, чтобы изменить",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                    )
                }
            }
        }
        HorizontalDivider()
        DrawerItem(icon = Icons.Outlined.Delete, label = "Корзина", count = 0, selected = false, onClick = onOpenTrash)
        DrawerItem(icon = Icons.Outlined.Settings, label = "Настройки", count = 0, selected = false, onClick = onOpenSettings)
    }

    when (val d = dialog) {
        is DrawerDialog.EditList -> ListDialog(
            list = d.list,
            folders = snapshot.folders,
            onDismiss = { dialog = null },
            onSave = { name, color, folderId ->
                dialog = null
                if (d.list == null) {
                    vm.createList(name, color, folderId) { onSelect(TaskFilter.ListFilter(it.id)) }
                } else {
                    vm.updateList(d.list.copy(name = name, color = color, folderId = folderId))
                }
            },
            onDelete = { dialog = DrawerDialog.DeleteList(d.list!!) },
        )
        is DrawerDialog.DeleteList -> ConfirmDialog(
            title = "Удалить список?",
            text = "Список «${d.list.name}» и все его задачи будут удалены.",
            confirmLabel = "Удалить",
            onConfirm = {
                vm.deleteList(d.list)
                if (selected == TaskFilter.ListFilter(d.list.id)) onSelect(TaskFilter.Inbox)
            },
            onDismiss = { dialog = null },
        )
        is DrawerDialog.EditFolder -> TextInputDialog(
            title = if (d.folder == null) "Новая папка" else "Папка",
            initial = d.folder?.name.orEmpty(),
            onConfirm = { name ->
                dialog = null
                if (d.folder == null) vm.createFolder(name) else vm.updateFolder(d.folder.copy(name = name))
            },
            onDismiss = { dialog = null },
            extra = {
                if (d.folder != null) {
                    TextButton(onClick = { vm.deleteFolder(d.folder); dialog = null }) {
                        Text("Удалить папку (списки останутся)", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
        )
        is DrawerDialog.EditTag -> {
            var color by remember(d.tag.id) { mutableStateOf(d.tag.color) }
            TextInputDialog(
                title = "Тег",
                initial = d.tag.name,
                onConfirm = { name -> vm.updateTag(d.tag.copy(name = name.removePrefix("#"), color = color)); dialog = null },
                onDismiss = { dialog = null },
                extra = {
                    Column {
                        ColorPicker(color) { color = it }
                        TextButton(onClick = {
                            vm.deleteTag(d.tag)
                            if (selected == TaskFilter.TagFilter(d.tag.id)) onSelect(TaskFilter.Inbox)
                            dialog = null
                        }) { Text("Удалить тег", color = MaterialTheme.colorScheme.error) }
                    }
                },
            )
        }
        null -> Unit
    }
}

@Composable
private fun ListItem(
    list: TaskList,
    snapshot: Snapshot,
    selected: TaskFilter,
    indent: Boolean,
    onSelect: (TaskFilter) -> Unit,
    onEdit: () -> Unit,
) {
    val filter = TaskFilter.ListFilter(list.id)
    DrawerItem(
        icon = Icons.AutoMirrored.Outlined.List,
        iconTint = list.color?.let { Color(it) },
        label = list.name,
        count = snapshot.openCount(filter),
        selected = selected == filter,
        indent = indent,
        onClick = { onSelect(filter) },
        onLongClick = onEdit,
    )
}

@Composable
private fun ListDialog(
    list: TaskList?,
    folders: List<Folder>,
    onDismiss: () -> Unit,
    onSave: (String, Int?, String?) -> Unit,
    onDelete: () -> Unit,
) {
    var color by remember { mutableStateOf(list?.color) }
    var folderId by remember { mutableStateOf(list?.folderId) }
    var folderMenu by remember { mutableStateOf(false) }
    TextInputDialog(
        title = if (list == null) "Новый список" else "Список",
        initial = list?.name.orEmpty(),
        confirmLabel = if (list == null) "Создать" else "Сохранить",
        onConfirm = { onSave(it, color, folderId) },
        onDismiss = onDismiss,
        extra = {
            Column {
                ColorPicker(color) { color = it }
                if (folders.isNotEmpty()) {
                    Box {
                        TextButton(onClick = { folderMenu = true }) {
                            Text("Папка: " + (folders.firstOrNull { it.id == folderId }?.name ?: "нет"))
                        }
                        DropdownMenu(folderMenu, { folderMenu = false }) {
                            DropdownMenuItem(text = { Text("Без папки") }, onClick = { folderId = null; folderMenu = false })
                            for (f in folders) {
                                DropdownMenuItem(text = { Text(f.name) }, onClick = { folderId = f.id; folderMenu = false })
                            }
                        }
                    }
                }
                if (list != null) {
                    TextButton(onClick = onDelete) { Text("Удалить список", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
    )
}

@Composable
private fun SectionHeader(title: String, action: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 28.dp, end = 12.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        action()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerItem(
    icon: ImageVector,
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    iconTint: Color? = null,
    trailing: ImageVector? = null,
    indent: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (selected) scheme.primaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = if (indent) 32.dp else 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = iconTint ?: if (selected) scheme.primary else scheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(
            label,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) scheme.primary else scheme.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (count > 0) Text("$count", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        if (trailing != null) Icon(trailing, null, tint = scheme.onSurfaceVariant)
    }
}
