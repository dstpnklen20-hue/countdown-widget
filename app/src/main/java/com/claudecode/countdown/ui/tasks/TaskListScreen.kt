package com.claudecode.countdown.ui.tasks

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claudecode.countdown.container
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TaskAlt
import com.claudecode.countdown.domain.TaskSort
import com.claudecode.countdown.ui.emptyArtIndex
import com.claudecode.countdown.ui.EMPTY_ARTS
import com.claudecode.countdown.ui.EmptyArt
import com.claudecode.countdown.ui.Motion
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
    sections: List<DrawerSection>,
    /** Tablets keep the lists panel open next to the tasks instead of a sliding menu. */
    permanentDrawer: Boolean = false,
    /** Phones search from the top bar; tablets have Search in the side rail and pass null. */
    onOpenSearch: (() -> Unit)? = null,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showQuickAdd by remember { mutableStateOf(false) }
    val list = (filter as? TaskFilter.ListFilter)?.let { snapshot.listsById[it.listId] }

    val drawer = @Composable {
        AppDrawer(
            vm = vm,
            snapshot = snapshot,
            selected = filter,
            onSelect = { onFilterChange(it); scope.launch { drawerState.close() } },
            onOpenTrash = { scope.launch { drawerState.close() }; onOpenTrash() },
            sections = sections.map { s -> DrawerSection(s.label, s.icon) { scope.launch { drawerState.close() }; s.onClick() } },
            permanent = permanentDrawer,
        )
    }
    val content = @Composable {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(snapshot.title(filter), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        if (!permanentDrawer) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Filled.Menu, "Меню") }
                        }
                    },
                    actions = {
                        if (onOpenSearch != null) IconButton(onClick = onOpenSearch) { Icon(Icons.Filled.Search, "Поиск") }
                        if (list != null) {
                            val kanban = list.viewMode == ListViewMode.KANBAN
                            IconButton(onClick = { vm.setViewMode(list, if (kanban) ListViewMode.LIST else ListViewMode.KANBAN) }) {
                                Icon(
                                    if (kanban) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.ViewKanban,
                                    if (kanban) "Показать списком" else "Показать канбаном",
                                )
                            }
                        }
                        if (filter != TaskFilter.Completed && list?.viewMode != ListViewMode.KANBAN) ListMenu(filter)
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            snackbarHost = { AppSnackbarHost() },
            floatingActionButton = {
                AnimatedVisibility(
                    visible = filter != TaskFilter.Completed && list?.viewMode != ListViewMode.KANBAN,
                    enter = Motion.popIn,
                    exit = Motion.popOut,
                ) {
                    FloatingActionButton(
                        onClick = { showQuickAdd = true },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) { Icon(Icons.Filled.Add, if (filter == TaskFilter.Countdowns) "Новое событие" else "Добавить задачу") }
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

    if (permanentDrawer) {
        Row(Modifier.fillMaxSize()) {
            drawer()
            Box(Modifier.weight(1f)) { content() }
        }
    } else {
        ModalNavigationDrawer(drawerState = drawerState, drawerContent = drawer) { content() }
    }

    if (showQuickAdd) {
        val event = filter == TaskFilter.Countdowns
        QuickAddSheet(
            onDismiss = { showQuickAdd = false },
            placeholder = if (event) "Отпуск 7 ноября" else "завтра в 10 позвонить !высокий #работа",
            requireDate = event,
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
    val appSettings = LocalContext.current.container.settings
    val settings by appSettings.state.collectAsStateWithLifecycle()
    val sort = settings.sortOf(filter)
    val groups = remember(snapshot, filter, settings.showCompleted, sort) {
        snapshot.groups(filter, sort).filter { settings.showCompleted || filter == TaskFilter.Completed || it.kind != GroupKind.DONE }
    }
    val collapsed = remember(filter) { mutableStateMapOf<String, Boolean>() }
    val showList = filter !is TaskFilter.ListFilter
    val hint = smartListHint(filter)?.takeIf { filter.key !in settings.dismissedHints }

    if (snapshot.loaded && groups.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            if (hint != null) HintBanner(hint) { appSettings.dismissHint(filter.key) }
            EmptyState(filter, emptyArtIndex(settings.emptyArt, filter.key, snapshot.today))
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
        if (hint != null) item(key = "hint") { HintBanner(hint, Modifier.animateItem()) { appSettings.dismissHint(filter.key) } }
        for (group in groups) {
            val key = group.key
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

/** What a smart list collects, shown once at its top until the user closes it. */
private fun smartListHint(filter: TaskFilter): String? = when (filter) {
    TaskFilter.Inbox -> "Сюда попадают новые задачи, для которых не выбран список."
    TaskFilter.Today -> "Задачи на сегодня и просроченные будут показаны здесь."
    TaskFilter.Tomorrow -> "Задачи с датой «завтра» будут показаны здесь."
    TaskFilter.Next7Days -> "Задачи на ближайшую неделю, по дням."
    TaskFilter.All -> "Все задачи из всех списков."
    TaskFilter.Countdowns -> "События, до которых идёт обратный отсчёт: отпуск, день рождения. Они не смешиваются с задачами."
    else -> null
}

@Composable
private fun HintBanner(text: String, modifier: Modifier = Modifier, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.primary)
            .padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.EventAvailable, null, tint = scheme.onPrimary)
        Spacer(Modifier.width(12.dp))
        Text(text, color = scheme.onPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Закрыть подсказку", tint = scheme.onPrimary) }
    }
}

/** Sort order of this list and whether completed tasks show, like TickTick's "⋮" menu. */
@Composable
private fun ListMenu(filter: TaskFilter) {
    val appSettings = LocalContext.current.container.settings
    val settings by appSettings.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "Сортировка и вид") }
        DropdownMenu(open, { open = false }) {
            Text(
                "Сортировка",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val current = settings.sortOf(filter)
            for (sort in TaskSort.entries) {
                DropdownMenuItem(
                    text = { Text(sort.label) },
                    leadingIcon = { Icon(sortIcon(sort), null) },
                    trailingIcon = { if (sort == current) Icon(Icons.Filled.Check, "Выбрано", tint = MaterialTheme.colorScheme.primary) },
                    onClick = { appSettings.setSort(filter, sort); open = false },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (settings.showCompleted) "Скрыть выполненные" else "Показать выполненные") },
                leadingIcon = { Icon(Icons.Outlined.TaskAlt, null) },
                onClick = { appSettings.setShowCompleted(!settings.showCompleted); open = false },
            )
        }
    }
}

private fun sortIcon(sort: TaskSort) = when (sort) {
    TaskSort.DATE -> Icons.Outlined.CalendarToday
    TaskSort.PRIORITY -> Icons.Outlined.Flag
    TaskSort.TITLE -> Icons.Filled.SortByAlpha
    TaskSort.CREATED -> Icons.Outlined.Schedule
}

@Composable
private fun EmptyState(filter: TaskFilter, art: Int) {
    val scheme = MaterialTheme.colorScheme
    val (title, subtitle) = when (filter) {
        TaskFilter.Today -> "Сегодня нет задач" to EMPTY_ARTS[art].subtitle
        TaskFilter.Tomorrow -> "На завтра задач нет" to "Можно запланировать что-нибудь приятное"
        TaskFilter.Completed -> "Пока ничего не выполнено" to "Выполненные задачи появятся здесь"
        TaskFilter.Countdowns -> "Событий нет" to "Нажмите «+», чтобы добавить событие с отсчётом"
        else -> "Задач нет" to "Нажмите «+», чтобы добавить"
    }
    // The picture floats up softly instead of popping in.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, Motion.soft(Motion.MEDIUM + 200)) }
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp)
            .graphicsLayer {
                alpha = appear.value
                translationY = (1f - appear.value) * 24.dp.toPx()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyArt(art, Modifier.size(200.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
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
        // The arrow turns rather than jumping between two icons.
        val turn by animateFloatAsState(if (collapsed) -90f else 0f, Motion.soft(), label = "chevron")
        Icon(
            Icons.Filled.KeyboardArrowDown,
            if (collapsed) "Развернуть" else "Свернуть",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.rotate(turn),
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
    // Ticking the box first draws a line through the title, then completes the task. Keyed on the
    // task so the line is gone once the stored task changes (e.g. a repeating task moved on).
    val strike = remember(task) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val completing = !task.isDone && strike.value > 0f
    val toggle: () -> Unit = {
        when {
            task.isDone -> onToggle()
            !strike.isRunning -> scope.launch {
                // finally: the tick still counts if the row scrolls away mid-animation.
                try {
                    strike.animateTo(1f, tween(STRIKE_MS))
                } finally {
                    onToggle()
                }
            }
        }
    }
    var titleLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val strikeColor = scheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            // Full rows cover the swipe background; compact ones sit on their card's colour.
            .background(if (compact) Color.Transparent else scheme.background)
            // A task's own colour shows as a bar at the left edge, without shifting the row.
            .then(
                if (task.color == null) Modifier else Modifier.drawBehind {
                    val bar = 4.dp.toPx()
                    drawRoundRect(
                        Color(task.color),
                        topLeft = Offset(0f, size.height * 0.2f),
                        size = Size(bar, size.height * 0.6f),
                        cornerRadius = CornerRadius(bar / 2),
                    )
                }
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 4.dp, end = if (compact) 8.dp else 16.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PriorityCheckbox(task.isDone || completing, task.priority, toggle, small = compact)
        Column(Modifier.weight(1f).padding(vertical = if (compact) 3.dp else 6.dp)) {
            Text(
                task.title,
                style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (task.isDone || completing) scheme.onSurfaceVariant else scheme.onSurface,
                textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
                onTextLayout = { titleLayout = it },
                modifier = Modifier.drawWithContent {
                    drawContent()
                    val layout = titleLayout
                    if (completing && layout != null) drawStrike(layout, strike.value, strikeColor, 1.5.dp.toPx())
                },
            )
            val meta = rowMeta(task, snapshot, showList).let { if (compact) it.take(1) else it }
            if (meta.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 2.dp)) {
                    for ((text, color) in meta) {
                        Text(
                            text,
                            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
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

private const val STRIKE_MS = 350

/** Draws [progress] of a line through the text, line after line, as if crossed out by hand. */
private fun DrawScope.drawStrike(layout: TextLayoutResult, progress: Float, color: Color, width: Float) {
    val lines = (0 until layout.lineCount).map { layout.getLineLeft(it) to layout.getLineRight(it) }
    var remaining = lines.sumOf { (l, r) -> (r - l).toDouble() }.toFloat() * progress
    for ((i, line) in lines.withIndex()) {
        if (remaining <= 0f) break
        val (left, right) = line
        val length = minOf(right - left, remaining)
        val y = (layout.getLineTop(i) + layout.getLineBottom(i)) / 2
        drawLine(color, Offset(left, y), Offset(left + length, y), strokeWidth = width)
        remaining -= length
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
fun QuickAddSheet(
    onDismiss: () -> Unit,
    initialPriority: Int = Priority.NONE,
    placeholder: String = "завтра в 10 позвонить !высокий #работа",
    requireDate: Boolean = false,
    onAdd: (String, Due?, Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf("") }
    var due by remember { mutableStateOf<Due?>(null) }
    var priority by remember { mutableStateOf(initialPriority) }
    var pickDate by remember { mutableStateOf(false) }
    var submitAfterDate by remember { mutableStateOf(false) }
    var priorityMenu by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    fun submit() {
        if (text.isBlank()) return
        // Events need a date: ask for one unless the text already names it.
        if (requireDate && due == null && parseQuickAdd(text).date == null) {
            submitAfterDate = true
            pickDate = true
            return
        }
        onAdd(text.trim(), due, priority)
        text = ""
        due = null
        priority = initialPriority
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, dragHandle = null) {
        Column(Modifier.fillMaxWidth().imePadding().padding(8.dp)) {
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
            onConfirm = {
                due = it
                pickDate = false
                if (submitAfterDate) {
                    submitAfterDate = false
                    submit()
                }
            },
            onDismiss = { pickDate = false; submitAfterDate = false },
        )
    }
}
