package com.claudecode.countdown.ui.matrix

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import com.claudecode.countdown.ui.AddFab
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import com.claudecode.countdown.ui.priorityCategory

/** Quadrants map to priorities, like TickTick's default matrix rules. */
private data class Quadrant(val title: String, val subtitle: String, val priority: Int)

private val QUADRANTS = listOf(
    Quadrant(priorityCategory(Priority.HIGH), "Высокий приоритет", Priority.HIGH),
    Quadrant(priorityCategory(Priority.MEDIUM), "Средний приоритет", Priority.MEDIUM),
    Quadrant(priorityCategory(Priority.LOW), "Низкий приоритет", Priority.LOW),
    Quadrant(priorityCategory(Priority.NONE), "Без приоритета", Priority.NONE),
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
        val drag = remember { MatrixDrag() }
        val target = drag.target()
        Box(Modifier.fillMaxSize().onGloballyPositioned { drag.origin = it.positionInRoot() }) {
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 8.dp).padding(bottom = 8.dp)) {
            // The same control as the calendar's modes, so both screens read alike.
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                MatrixPeriod.entries.forEachIndexed { i, p ->
                    SegmentedButton(
                        selected = period == p,
                        onClick = { period = p },
                        shape = SegmentedButtonDefaults.itemShape(i, MatrixPeriod.entries.size),
                        icon = {},
                    ) { Text(p.label, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
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
                            drag = drag,
                            highlighted = target == q.priority && drag.task?.priority != q.priority,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .padding(4.dp)
                                .onGloballyPositioned { drag.bounds[q.priority] = it.boundsInRoot() },
                        )
                    }
                }
            }
        }
        // The task being dragged follows the finger as a small card.
        drag.task?.let { task ->
            val at = drag.pointer - drag.origin
            val density = LocalDensity.current
            Text(
                task.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .offset { IntOffset((at.x - with(density) { 80.dp.toPx() }).toInt(), (at.y - with(density) { 44.dp.toPx() }).toInt()) }
                    .widthIn(max = 200.dp)
                    .shadow(8.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        }
    }
}

/**
 * Dragging a task between quadrants: which task, where the finger is (in root coordinates) and
 * where each quadrant is on the screen, so the drop lands in the one under the finger.
 */
private class MatrixDrag {
    var task by mutableStateOf<Task?>(null)
    var pointer by mutableStateOf(Offset.Zero)
    var origin = Offset.Zero
    val bounds = HashMap<Int, Rect>()

    /** Priority of the quadrant under the finger while dragging. */
    fun target(): Int? = if (task == null) null else bounds.entries.firstOrNull { pointer in it.value }?.key
}

@Composable
private fun QuadrantCard(
    quadrant: Quadrant,
    snapshot: Snapshot,
    tasks: List<Task>,
    vm: TasksViewModel,
    onOpenTask: (String) -> Unit,
    onExpand: () -> Unit,
    drag: MatrixDrag,
    highlighted: Boolean,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val color = quadrantColor(quadrant)
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .clip(shape)
            .background(if (highlighted) color.copy(alpha = 0.16f) else scheme.surfaceContainerLow)
            .then(if (highlighted) Modifier.border(2.dp, color, shape) else Modifier),
    ) {
        // Tapping the header (or the empty space) opens the quadrant on the whole screen.
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Level with the first line of the title, which may wrap.
            Box(Modifier.align(Alignment.Top).padding(top = 7.dp)) { Dot(color, 8) }
            Spacer(Modifier.size(6.dp))
            Column(Modifier.weight(1f)) {
                // Two lines: "Не срочно и не важно" doesn't fit one on a phone.
                Text(quadrant.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = color, maxLines = 2)
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
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(top = 2.dp)
                    // A tap that no task row took (the free space below the tasks) opens the quadrant;
                    // rows consume their own taps, and scrolling cancels the gesture.
                    .pointerInput(onExpand) { detectTapGestures { onExpand() } },
            ) {
                items(tasks, key = { it.id }) { task -> MatrixTaskRow(task, snapshot, vm, onOpenTask, compact = true, drag = drag) }
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
            AddFab("Добавить задачу в этот квадрант") { adding = true }
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

/**
 * A task in the matrix. In the four quadrants a long press picks it up to drag into another one
 * (held without moving, it opens the "move to" menu); on a quadrant's own screen, long press
 * opens the menu.
 */
@Composable
private fun MatrixTaskRow(
    task: Task,
    snapshot: Snapshot,
    vm: TasksViewModel,
    onOpenTask: (String) -> Unit,
    compact: Boolean,
    drag: MatrixDrag? = null,
) {
    var menu by remember { mutableStateOf(false) }
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val haptic = LocalHapticFeedback.current
    val dragging = drag?.task?.id == task.id
    Box(
        Modifier
            .onGloballyPositioned { coords = it }
            .graphicsLayer { alpha = if (dragging) 0.35f else 1f }
            .then(
                if (drag == null) Modifier else Modifier.longPressDrag(
                    key = task.id,
                    onStart = { local ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        drag.pointer = coords?.localToRoot(local) ?: Offset.Zero
                        drag.task = task
                    },
                    onMove = { local -> coords?.let { drag.pointer = it.localToRoot(local) } },
                    onEnd = { moved ->
                        val to = drag.target()
                        drag.task = null
                        when {
                            !moved -> menu = true
                            to != null && to != task.priority -> vm.setPriority(task, to)
                        }
                    },
                )
            ),
    ) {
        TaskRow(
            task, snapshot, showList = !compact,
            onToggle = { vm.toggleDone(task) },
            onClick = { onOpenTask(task.id) },
            onLongClick = if (drag == null) ({ menu = true }) else null,
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

/**
 * Long press, then drag. Until the press is long enough nothing is taken, so taps and scrolling
 * work as usual; after that every event is consumed before the row sees it, so letting go does
 * not also open the task. [onEnd] says whether the finger moved at all.
 */
private fun Modifier.longPressDrag(
    key: Any,
    onStart: (Offset) -> Unit,
    onMove: (Offset) -> Unit,
    onEnd: (moved: Boolean) -> Unit,
): Modifier = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                if (!change.pressed || change.isConsumed) return@withTimeoutOrNull true
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull true
            }
        }
        if (released != null) return@awaitEachGesture
        onStart(down.position)
        var moved = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            event.changes.forEach { it.consume() }
            if (!change.pressed) break
            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
            onMove(change.position)
        }
        onEnd(moved)
    }
}
