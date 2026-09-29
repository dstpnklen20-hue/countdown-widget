package com.claudecode.countdown.ui.tasks

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.claudecode.countdown.data.db.DisplayMode
import com.claudecode.countdown.data.db.ListViewMode
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ViewKanban
import com.claudecode.countdown.data.db.Priority
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.data.db.TaskList
import com.claudecode.countdown.domain.Due
import com.claudecode.countdown.domain.GroupKind
import com.claudecode.countdown.domain.TaskFilter
import com.claudecode.countdown.domain.isOverdue
import com.claudecode.countdown.ui.AppSnackbarHost
import com.claudecode.countdown.ui.DueDateDialog
import com.claudecode.countdown.ui.PriorityCheckbox
import com.claudecode.countdown.ui.PriorityMenu
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.claudecode.countdown.domain.parseQuickAdd
import com.claudecode.countdown.domain.today
import com.claudecode.countdown.ui.formatDay
import com.claudecode.countdown.ui.priorityName
import com.claudecode.tiktak.core.QuickAddResult
import com.claudecode.tiktak.core.describe
import com.claudecode.countdown.ui.formatCountdown
import com.claudecode.countdown.ui.formatDue
import com.claudecode.countdown.ui.groupTitle
import com.claudecode.countdown.ui.priorityColor
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    vm: TasksViewModel,
    snapshot: Snapshot,
    filter: TaskFilter,
    onFilterChange: (TaskFilter) -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showQuickAdd by remember { mutableStateOf(false) }
    val list = (filter as? TaskFilter.ListFilter)?.let { snapshot.listsById[it.listId] }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawer(
                vm = vm,
                snapshot = snapshot,
                selected = filter,
                onSelect = { onFilterChange(it); scope.launch { drawerState.close() } },
                onOpenTrash = { scope.launch { drawerState.close() }; onOpenTrash() },
                onOpenSettings = { scope.launch { drawerState.close() }; onOpenSettings() },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(snapshot.title(filter), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Filled.Menu, "Меню") }
                    },
                    actions = {
                        if (list != null) {
                            val kanban = list.viewMode == ListViewMode.KANBAN
                            IconButton(onClick = { vm.setViewMode(list, if (kanban) ListViewMode.LIST else ListViewMode.KANBAN) }) {
                                Icon(
                                    if (kanban) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.ViewKanban,
                                    if (kanban) "Показать списком" else "Показать канбаном",
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            snackbarHost = { AppSnackbarHost() },
            floatingActionButton = {
                if (filter != TaskFilter.Completed && list?.viewMode != ListViewMode.KANBAN) {
                    FloatingActionButton(
                        onClick = { showQuickAdd = true },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) { Icon(Icons.Filled.Add, "Добавить задачу") }
                }
            },
        ) { padding ->
            if (list != null && list.viewMode == ListViewMode.KANBAN) {
                KanbanBoard(vm, snapshot, list, onOpenTask, padding)
            } else {
                TaskGroupsList(
                    vm = vm,
                    snapshot = snapshot,
                    filter = filter,
                    onOpenTask = onOpenTask,
                    contentPadding = padding,
                )
            }
        }
    }

    if (showQuickAdd) {
        QuickAddSheet(
            onDismiss = { showQuickAdd = false },
            onAdd = { title, due, priority -> vm.quickAdd(title, filter, due, priority) },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskGroupsList(
    vm: TasksViewModel,
    snapshot: Snapshot,
    filter: TaskFilter,
    onOpenTask: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    val groups = remember(snapshot, filter) { snapshot.groups(filter) }
    val collapsed = remember(filter) { mutableStateMapOf<String, Boolean>() }
    val showList = filter !is TaskFilter.ListFilter

    if (snapshot.loaded && groups.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
            Text(
                if (filter == TaskFilter.Completed) "Пока ничего не выполнено" else "Задач нет.\nНажмите «+», чтобы добавить.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 88.dp,
        ),
    ) {
        for (group in groups) {
            val key = "${group.kind}:${group.date}"
            val isCollapsed = collapsed[key] ?: (group.kind == GroupKind.DONE && filter != TaskFilter.Completed)
            if (groups.size > 1 || group.kind == GroupKind.DONE) {
                item(key = "h:$key") {
                    GroupHeader(
                        title = groupTitle(group, snapshot.today),
                        count = group.tasks.size,
                        collapsed = isCollapsed,
                        overdue = group.kind == GroupKind.OVERDUE,
                        onClick = { collapsed[key] = !isCollapsed },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            if (!isCollapsed) {
                items(group.tasks, key = { it.id }) { task ->
                    SwipeableTaskRow(
                        task = task,
                        snapshot = snapshot,
                        showList = showList,
                        onToggle = { vm.toggleDone(task) },
                        onDelete = { vm.delete(task) },
                        onClick = { onOpenTask(task.id) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(
    title: String,
    count: Int,
    collapsed: Boolean,
    overdue: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (overdue) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.size(8.dp))
        Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Icon(
            if (collapsed) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableTaskRow(
    task: Task,
    snapshot: Snapshot,
    showList: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            when (it) {
                SwipeToDismissBoxValue.StartToEnd -> onToggle()
                SwipeToDismissBoxValue.EndToStart -> onDelete()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        },
        positionalThreshold = { it * 0.35f },
    )
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = {
            val direction = state.dismissDirection
            val color by animateColorAsState(
                when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> Color(0xFF43A047)
                    SwipeToDismissBoxValue.EndToStart -> Color(0xFFE53935)
                    else -> Color.Transparent
                },
                label = "swipe",
            )
            Box(
                Modifier.fillMaxSize().background(color).padding(horizontal = 24.dp),
                contentAlignment = if (direction == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                if (direction == SwipeToDismissBoxValue.StartToEnd) Icon(Icons.Filled.Done, null, tint = Color.White)
                if (direction == SwipeToDismissBoxValue.EndToStart) Icon(Icons.Filled.Delete, null, tint = Color.White)
            }
        },
    ) {
        TaskRow(task, snapshot, showList, onToggle, onClick)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskRow(
    task: Task,
    snapshot: Snapshot,
    showList: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    compact: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .background(scheme.background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 4.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PriorityCheckbox(task.isDone, task.priority, onToggle)
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                task.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (task.isDone) scheme.onSurfaceVariant else scheme.onSurface,
                textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
            )
            val meta = rowMeta(task, snapshot, showList).let { if (compact) it.take(1) else it }
            if (meta.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 2.dp)) {
                    for ((text, color) in meta) {
                        Text(
                            text,
                            style = MaterialTheme.typography.labelMedium,
                            color = color ?: scheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        if (!compact && task.displayMode == DisplayMode.COUNTDOWN && task.dueAt != null && !task.isDone) {
            Text(
                formatCountdown(task.dueAt - snapshot.now),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = scheme.primary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun rowMeta(task: Task, snapshot: Snapshot, showList: Boolean): List<Pair<String, Color?>> {
    val out = mutableListOf<Pair<String, Color?>>()
    formatDue(task, snapshot.today)?.let { due ->
        val overdue = task.isOverdue(snapshot.now, snapshot.today)
        val repeat = if (task.repeatRule != null) " ⟳" else ""
        out += (due + repeat) to when {
            overdue -> Color(0xFFE53935)
            !task.isDone && due.startsWith("Сегодня") -> MaterialTheme.colorScheme.primary
            else -> null
        }
    }
    snapshot.checklist[task.id]?.let { if (it.total > 0) out += "☑ ${it.done}/${it.total}" to null }
    snapshot.subtasks[task.id]?.let { if (it.total > 0) out += "↳ ${it.done}/${it.total}" to null }
    if (showList && task.listId != TaskList.INBOX_ID) {
        snapshot.listsById[task.listId]?.let { list -> out += list.name to list.color?.let { Color(it) } }
    }
    snapshot.tagsByTask[task.id]?.take(3)?.forEach { out += "#${it.name}" to it.color?.let { c -> Color(c) } }
    return out
}

/** Shows what the parser understood; values picked with buttons take precedence. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParsedPreview(parsed: QuickAddResult, pickedDue: Due?, pickedPriority: Int) {
    val today = today()
    val chips = buildList {
        val date = parsed.date
        if (pickedDue == null && date != null) {
            add("📅 " + formatDay(date, today) + (parsed.time?.let { ", %02d:%02d".format(it.hour, it.minute) } ?: ""))
        }
        parsed.repeat?.let { add("⟳ " + it.describe()) }
        if (pickedPriority == Priority.NONE) parsed.priority?.let { add("⚑ " + priorityName(it)) }
        parsed.tags.forEach { add("#$it") }
        parsed.listName?.let { add("~$it") }
    }
    if (chips.isEmpty()) return
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (chip in chips) {
            Text(
                chip,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primaryContainer, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddSheet(onDismiss: () -> Unit, onAdd: (String, Due?, Int) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf("") }
    var due by remember { mutableStateOf<Due?>(null) }
    var priority by remember { mutableStateOf(Priority.NONE) }
    var pickDate by remember { mutableStateOf(false) }
    var priorityMenu by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    fun submit() {
        if (text.isBlank()) return
        onAdd(text.trim(), due, priority)
        text = ""
        due = null
        priority = Priority.NONE
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, dragHandle = null) {
        Column(Modifier.fillMaxWidth().imePadding().padding(8.dp)) {
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("завтра в 10 позвонить !высокий #работа", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            // The sheet lives in its own window: focus only after the field is attached there.
            LaunchedEffect(Unit) {
                awaitFrame()
                runCatching { focus.requestFocus() }
            }
            val parsed = remember(text) { if (text.isBlank()) null else parseQuickAdd(text) }
            if (parsed != null) ParsedPreview(parsed, due, priority)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { pickDate = true }) {
                    Icon(
                        Icons.Outlined.CalendarToday, "Дата",
                        tint = if (due != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { priorityMenu = true }) {
                        Icon(
                            if (priority == Priority.NONE) Icons.Outlined.Flag else Icons.Filled.Flag, "Приоритет",
                            tint = priorityColor(priority, MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                    }
                    PriorityMenu(priorityMenu, { priorityMenu = false }) { priority = it }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { submit() }, enabled = text.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Добавить", tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }

    if (pickDate) {
        DueDateDialog(
            initialAt = due?.at,
            initialAllDay = due?.isAllDay ?: true,
            onConfirm = { due = it; pickDate = false },
            onDismiss = { pickDate = false },
        )
    }
}
