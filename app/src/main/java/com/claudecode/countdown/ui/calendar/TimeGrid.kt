package com.claudecode.countdown.ui.calendar

import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudecode.countdown.data.Settings
import com.claudecode.countdown.data.db.EventType
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.domain.CalendarEntry
import com.claudecode.countdown.domain.MINUTES_PER_DAY
import com.claudecode.countdown.domain.TimeBlock
import com.claudecode.countdown.domain.layoutBlocks
import com.claudecode.countdown.domain.localTimeOf
import com.claudecode.countdown.ui.PriorityCheckbox
import com.claudecode.countdown.ui.rememberNow
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.IsoFields
import kotlin.math.abs
import kotlin.math.roundToInt

private val MIN_BLOCK_HEIGHT = 20.dp
private val HOUR_LABEL_WIDTH = 50.dp
private val ZONE_LABEL_WIDTH = 40.dp
/** How far a block drawn over another one is moved in. */
private val NEST_INDENT = 10.dp
/** Times snap to this many minutes while a block is moved or stretched. */
private const val SNAP_MINUTES = 15
/** Pressing this close to a block's bottom edge stretches it instead of moving it. */
private val RESIZE_EDGE = 16.dp

/** What the time grid can ask for. */
internal class GridActions(
    val onOpen: (CalendarEntry) -> Unit,
    val onOpenDay: ((LocalDate) -> Unit)?,
    val onAddAt: (LocalDate, LocalTime) -> Unit,
    /** A block was moved or stretched: its start and its end shifted by these many minutes. */
    val onShift: (CalendarEntry, startMinutes: Long, endMinutes: Long) -> Unit,
    val onToggleDone: (Task) -> Unit,
    /** The pinch zoom settled on a new hour height (dp). */
    val onZoom: (Int) -> Unit,
    /** A task from the "Без даты" strip was dropped on [day] at [minute]. */
    val onDropTask: ((taskId: String, day: LocalDate, minute: Int) -> Unit)? = null,
)

/** A block being moved ([resize] false) or stretched, with how far it went so far. */
private data class Drag(
    val entry: CalendarEntry,
    val resize: Boolean,
    /** Column the block was picked up in. */
    val day: Int,
    val minutes: Float = 0f,
    val dxPx: Float = 0f,
    /** Columns it moved sideways. */
    val days: Int = 0,
) {
    val snapped: Int get() = snap(minutes.roundToInt())
}

/**
 * A time scale for one, three or seven days, like Google Calendar: hours down the side, each
 * hour a rounded tile, events as blocks filled with their colour (one that starts later than an
 * overlapping one is drawn over it, moved in), tasks as lighter blocks with a checkbox, all-day
 * entries in a strip above and a line at the current time. Tapping free space adds an entry at
 * that half hour; a long press moves a block (or stretches it from its bottom edge) in steps of
 * 15 minutes; two fingers zoom the hours.
 */
@Composable
internal fun TimeGrid(
    days: List<LocalDate>,
    today: LocalDate,
    entries: Map<LocalDate, List<CalendarEntry>>,
    draft: QuickDraft?,
    settings: Settings,
    actions: GridActions,
    /** Space a card takes at the bottom: a draft is scrolled to stay above it. */
    bottomInset: Dp = 0.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val allDay = days.map { d -> entries[d].orEmpty().filter { !it.timed } }
    val timed = days.map { d -> entries[d].orEmpty().filter { it.timed } }
    val scroll = rememberScrollState()

    // Zoom: the height of an hour, changed live by a pinch and saved when the fingers lift.
    var hourDp by remember { mutableFloatStateOf(settings.hourHeight.toFloat()) }
    LaunchedEffect(settings.hourHeight) { hourDp = settings.hourHeight.toFloat() }
    val hourHeight = hourDp.dp
    val hourPx = with(density) { hourHeight.toPx() }
    // Where to scroll once the zoomed grid has its new height (it cannot scroll there before).
    var zoomScroll by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(zoomScroll) {
        val target = zoomScroll ?: return@LaunchedEffect
        withFrameNanos { }
        scroll.scrollTo(target)
        zoomScroll = null
    }

    LaunchedEffect(days.first(), days.size) {
        // Open at the current hour on today's page, else at the first entry or the morning, an hour above.
        val first = timed.flatten().minOfOrNull { it.start!! }?.div(60)
        val hour = if (today in days) LocalTime.now().hour else first ?: 8
        scroll.scrollTo(((hour - 1).coerceAtLeast(0) * hourPx).toInt())
    }
    // Keep a new entry in view above the card that is creating it.
    var viewport by remember { mutableIntStateOf(0) }
    val draftStart = draft?.let { d -> days.firstNotNullOfOrNull { d.minutesOn(it) }?.first }
    LaunchedEffect(draftStart, viewport) {
        val start = draftStart ?: return@LaunchedEffect
        val top = (start / 60f * hourPx).toInt()
        val bottom = top + hourPx.toInt()
        val visibleBottom = scroll.value + viewport - with(density) { bottomInset.toPx() }.toInt()
        if (top < scroll.value || bottom > visibleBottom) scroll.animateScrollTo((top - with(density) { 24.dp.toPx() }).toInt().coerceAtLeast(0))
    }

    val now by rememberNow(60_000)
    val nowMinute = localTimeOf(now).let { it.hour * 60 + it.minute }
    val compact = days.size > 3
    val secondZone = settings.secondZone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
    val labelWidth = HOUR_LABEL_WIDTH + if (secondZone != null) ZONE_LABEL_WIDTH else 0.dp

    var drag by remember { mutableStateOf<Drag?>(null) }
    // Speed of the scroll while a dragged block is held near the top or bottom edge (px per frame).
    var edgeSpeed by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(drag != null) {
        while (drag != null) {
            withFrameNanos { }
            if (edgeSpeed != 0f) {
                val moved = scroll.scrollBy(edgeSpeed)
                // The content slid under the finger: the block follows it.
                drag = drag?.let { it.copy(minutes = it.minutes + moved / hourPx * 60f) }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(end = 6.dp)) {
        // Day captions: weekday over a big number, today's in a filled circle.
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Box(Modifier.width(labelWidth), contentAlignment = Alignment.BottomCenter) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (settings.weekNumbers) {
                        Text("Н${days.first().get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    }
                    if (secondZone != null) {
                        Text(zoneShort(secondZone) + " · " + zoneShort(ZoneId.systemDefault()), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            for (d in days) {
                val isToday = d == today
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .then(if (actions.onOpenDay != null) Modifier.clickable { actions.onOpenDay.invoke(d) } else Modifier)
                        .padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(WEEK_DAYS[d.dayOfWeek.value - 1], style = MaterialTheme.typography.labelMedium, color = if (isToday) scheme.primary else scheme.onSurfaceVariant)
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(if (isToday) scheme.primary else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${d.dayOfMonth}", fontSize = 22.sp, color = if (isToday) scheme.onPrimary else scheme.onSurface)
                    }
                }
            }
        }
        val draftAllDay = draft != null && (draft.time.allDay || !draft.timed)
        if (allDay.any { it.isNotEmpty() } || draftAllDay) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Text(
                    "весь\nдень",
                    modifier = Modifier.width(labelWidth).padding(start = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
                val shown = if (days.size == 1) 4 else 2
                days.forEachIndexed { i, day ->
                    val list = allDay[i]
                    Column(Modifier.weight(1f).padding(horizontal = 1.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (draftAllDay && day in draft!!.time.startDate..maxOf(draft.time.startDate, draft.time.endDate)) DraftChip(draft)
                        for (e in list.take(shown)) AllDayChip(e, now, settings.dimPast, actions.onOpen)
                        if (list.size > shown) Text("+${list.size - shown}", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).onSizeChanged { viewport = it.height }) {
            Row(
                Modifier
                    .fillMaxSize()
                    .pinchToZoom(
                        scroll = scroll,
                        hourDp = { hourDp },
                        onZoom = { height, target ->
                            hourDp = height
                            zoomScroll = target
                        },
                        onEnd = { actions.onZoom(hourDp.roundToInt()) },
                    )
                    .verticalScroll(scroll, enabled = drag == null),
            ) {
                Row(Modifier.fillMaxWidth().height(hourHeight * 24)) {
                    HourLabels(hourHeight, secondZone, days.first(), Modifier.width(labelWidth).fillMaxHeight())
                    val todayShown = today in days
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                        val columnWidth = maxWidth / days.size
                        val columnPx = with(density) { columnWidth.toPx() }
                        Row(Modifier.fillMaxSize()) {
                            days.forEachIndexed { i, day ->
                                DayColumn(
                                    day = day,
                                    dayIndex = i,
                                    entries = timed[i],
                                    hourHeight = hourHeight,
                                    nowMinute = if (todayShown) nowMinute else null,
                                    isToday = day == today,
                                    now = now,
                                    draft = draft?.minutesOn(day),
                                    draftTitle = draft?.cleanTitle,
                                    dragging = drag?.entry,
                                    settings = settings,
                                    compact = compact,
                                    actions = actions,
                                    onDragStart = { entry, resize ->
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        drag = Drag(entry, resize, i)
                                    },
                                    onDrag = { dx, dy, yInGrid ->
                                        drag = drag?.let { d ->
                                            val dxPx = if (d.resize) 0f else d.dxPx + dx
                                            d.copy(
                                                minutes = d.minutes + dy / hourPx * 60f,
                                                dxPx = dxPx,
                                                days = (dxPx / columnPx).roundToInt().coerceIn(-i, days.size - 1 - i),
                                            )
                                        }
                                        val edge = with(density) { 56.dp.toPx() }
                                        val y = yInGrid - scroll.value
                                        edgeSpeed = when {
                                            y < edge -> -12f
                                            y > viewport - edge -> 12f
                                            else -> 0f
                                        }
                                    },
                                    onDragEnd = { commit ->
                                        val d = drag
                                        drag = null
                                        edgeSpeed = 0f
                                        if (commit && d != null) {
                                            val minutes = d.snapped.toLong()
                                            val shiftDays = d.days.toLong() * MINUTES_PER_DAY
                                            if (minutes != 0L || d.days != 0) {
                                                if (d.resize) actions.onShift(d.entry, 0, minutes)
                                                else actions.onShift(d.entry, shiftDays + minutes, shiftDays + minutes)
                                            }
                                        }
                                    },
                                    onSnapStep = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                            }
                        }
                        // The block being dragged, drawn where it would land, with its new time.
                        drag?.let { d -> DragPreview(d, hourHeight, columnWidth) }
                    }
                }
            }
        }
    }
}

private fun snap(minutes: Int): Int = (minutes.toFloat() / SNAP_MINUTES).roundToInt() * SNAP_MINUTES

private fun zoneShort(zone: ZoneId): String = zone.getDisplayName(TextStyle.SHORT, ru).let { if (it.length > 6) zone.id.substringAfterLast('/').take(6) else it }

/**
 * Two fingers change the height of an hour (the point between them stays put); one finger is
 * left to the scroll. Runs in the initial pass so the scroll never sees a pinch.
 */
private fun Modifier.pinchToZoom(
    scroll: ScrollState,
    hourDp: () -> Float,
    /** The new hour height and the scroll position that keeps the content under the fingers. */
    onZoom: (Float, Int) -> Unit,
    onEnd: () -> Unit,
): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var zoomed = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.none { it.pressed }) break
                if (event.changes.count { it.pressed } < 2) continue
                val zoom = event.calculateZoom()
                if (zoom != 1f) {
                    val old = hourDp()
                    val new = (old * zoom).coerceIn(30f, 150f)
                    if (new != old) {
                        zoomed = true
                        val focus = event.calculateCentroid().y
                        onZoom(new, ((scroll.value + focus) * (new / old) - focus).roundToInt().coerceAtLeast(0))
                    }
                }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
            if (zoomed) onEnd()
        }
    }

/** Hours down the side, level with the lines they name; a second zone's hours to their left. */
@Composable
private fun HourLabels(hourHeight: Dp, second: ZoneId?, day: LocalDate, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val local = ZoneId.systemDefault()
    Row(modifier) {
        if (second != null) {
            Box(Modifier.width(ZONE_LABEL_WIDTH).fillMaxHeight()) {
                for (h in 1..23) {
                    val other = day.atTime(h, 0).atZone(local).withZoneSameInstant(second).toLocalTime()
                    Text(
                        "%02d:%02d".format(other.hour, other.minute),
                        modifier = Modifier.offset(y = hourHeight * h - 8.dp).padding(start = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        }
        Box(Modifier.width(HOUR_LABEL_WIDTH).fillMaxHeight()) {
            for (h in 1..23) {
                Text(
                    "%02d:00".format(h),
                    modifier = Modifier.offset(y = hourHeight * h - 8.dp).padding(start = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DayColumn(
    day: LocalDate,
    dayIndex: Int,
    entries: List<CalendarEntry>,
    hourHeight: Dp,
    nowMinute: Int?,
    isToday: Boolean,
    now: Long,
    draft: IntRange?,
    draftTitle: String?,
    dragging: CalendarEntry?,
    settings: Settings,
    compact: Boolean,
    actions: GridActions,
    onDragStart: (CalendarEntry, resize: Boolean) -> Unit,
    onDrag: (dx: Float, dy: Float, yInGrid: Float) -> Unit,
    onDragEnd: (commit: Boolean) -> Unit,
    onSnapStep: () -> Unit,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val tile = scheme.surfaceContainerLow
    val shade = scheme.onSurface.copy(alpha = 0.06f)
    val placed = remember(entries) {
        // Deeper blocks last, so a block drawn over another really is on top.
        layoutBlocks(entries.map { TimeBlock(it, it.start!!, it.end!!) }).sortedBy { it.depth }
    }
    val workStart = settings.workStart
    val workEnd = settings.workEnd
    val addAt by rememberUpdatedState(actions.onAddAt)
    val dropTask by rememberUpdatedState(actions.onDropTask)
    var topInRoot by remember { mutableFloatStateOf(0f) }
    val hourPxHere = with(LocalDensity.current) { hourHeight.toPx() }
    val dropTarget = remember(day, hourPxHere) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val id = event.taskId() ?: return false
                val y = event.toAndroidDragEvent().y - topInRoot
                val minute = snap((y / hourPxHere * 60).roundToInt()).coerceIn(0, MINUTES_PER_DAY - SNAP_MINUTES)
                dropTask?.invoke(id, day, minute)
                return true
            }
        }
    }
    BoxWithConstraints(
        modifier
            .drawBehind {
                // Every hour a rounded tile with a small gap around it.
                val hour = size.height / 24
                val gap = 1.5.dp.toPx()
                val radius = CornerRadius(6.dp.toPx())
                for (h in 0 until 24) {
                    drawRoundRect(tile, Offset(gap, hour * h + gap), Size(size.width - 2 * gap, hour - 2 * gap), radius)
                }
                // Outside working hours the grid is shaded, as in Google Calendar.
                if (workStart != null && workEnd != null && workEnd > workStart) {
                    val perMinute = size.height / MINUTES_PER_DAY
                    drawRect(shade, Offset.Zero, Size(size.width, workStart * perMinute))
                    drawRect(shade, Offset(0f, workEnd * perMinute), Size(size.width, size.height - workEnd * perMinute))
                }
            }
            // A task dragged from the "Без даты" strip lands at the quarter hour under the finger.
            .onGloballyPositioned { topInRoot = it.positionInRoot().y }
            .then(
                if (dropTask == null) Modifier else Modifier.dragAndDropTarget(
                    shouldStartDragAndDrop = { it.carriesTask() },
                    target = dropTarget,
                )
            )
            // Free space: a new entry at that half hour. Blocks take their own taps.
            .pointerInput(day) {
                detectTapGestures { offset ->
                    val minute = ((offset.y / (size.height / 24f)) * 60).toInt().coerceIn(0, MINUTES_PER_DAY - 1) / 30 * 30
                    addAt(day, LocalTime.of(minute / 60, minute % 60))
                }
            },
    ) {
        val heightPx = constraints.maxHeight.toFloat()
        for (p in placed) {
            val top = hourHeight * (p.start / 60f)
            val height = (hourHeight * ((p.end - p.start) / 60f)).coerceAtLeast(MIN_BLOCK_HEIGHT)
            val lane = maxWidth / p.lanes
            val indent = (NEST_INDENT * p.depth).coerceAtMost(lane / 2)
            val entry = p.item
            val past = settings.dimPast && entryEnd(entry) < now
            TimeBlockView(
                entry = entry,
                height = height,
                nested = p.depth > 0,
                compact = compact,
                faded = past,
                hidden = dragging == entry,
                actions = actions,
                modifier = Modifier
                    .offset(x = lane * p.lane + indent, y = top)
                    .width(lane - indent)
                    .height(height)
                    .padding(horizontal = 1.dp, vertical = 1.dp)
                    .then(
                        // Projected repeats of tasks do not exist yet: nothing to move.
                        if (entry.projected && !entry.task.happens) Modifier else Modifier.pointerInput(entry) {
                            val edge = RESIZE_EDGE.toPx()
                            var lastStep = 0
                            var dyTotal = 0f
                            detectDragGesturesAfterLongPress(
                                onDragStart = { start ->
                                    dyTotal = 0f
                                    lastStep = 0
                                    onDragStart(entry, start.y > size.height - edge && size.height > 2 * edge)
                                },
                                onDragEnd = { onDragEnd(true) },
                                onDragCancel = { onDragEnd(false) },
                            ) { change, amount ->
                                change.consume()
                                dyTotal += amount.y
                                val blockTop = p.start / 60f * (heightPx / 24f)
                                onDrag(amount.x, amount.y, blockTop + change.position.y)
                                val step = snap((dyTotal / (heightPx / 24f) * 60).roundToInt())
                                if (step != lastStep) {
                                    lastStep = step
                                    onSnapStep()
                                }
                            }
                        }
                    ),
            )
        }
        if (draft != null) {
            val top = hourHeight * (draft.first / 60f)
            val height = (hourHeight * ((draft.last + 1 - draft.first) / 60f)).coerceAtLeast(MIN_BLOCK_HEIGHT)
            Box(
                Modifier
                    .offset(y = top)
                    .fillMaxWidth()
                    .height(height)
                    .padding(1.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(scheme.primary)
                    .border(2.dp, scheme.onPrimary.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    draftTitle?.takeIf { it.isNotBlank() } ?: "(Без названия)",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Now: a strong line with a dot on today, a faint one across the other days.
        if (nowMinute != null) {
            val y = hourHeight * (nowMinute / 60f)
            val color = scheme.error
            Box(Modifier.offset(y = y - 1.dp).fillMaxWidth().height(2.dp).background(if (isToday) color else color.copy(alpha = 0.3f)))
            if (isToday) Box(Modifier.offset(x = (-5).dp, y = y - 5.dp).size(10.dp).clip(CircleShape).background(color))
        }
    }
}

/** When the entry's occurrence ends (for fading past ones). */
internal fun entryEnd(e: CalendarEntry): Long {
    val zone = ZoneId.systemDefault()
    val end = e.end ?: MINUTES_PER_DAY
    return e.date.atStartOfDay(zone).plusMinutes(end.toLong()).toInstant().toEpochMilli()
}

/** The block being dragged, where it would land, with the time it would get. */
@Composable
private fun DragPreview(d: Drag, hourHeight: Dp, columnWidth: Dp) {
    val e = d.entry
    val minutes = d.snapped
    val start = if (d.resize) e.start!! else e.start!! + minutes
    val end = (e.end!! + minutes).coerceAtLeast(start + SNAP_MINUTES)
    val color = entryColor(e.task)
    Box(
        Modifier
            .offset(x = columnWidth * (d.day + d.days), y = hourHeight * (start / 60f))
            .width(columnWidth)
            .height((hourHeight * ((end - start) / 60f)).coerceAtLeast(MIN_BLOCK_HEIGHT))
            .padding(1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.9f))
            .border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(6.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Column {
            Text(clockText(start) + "–" + clockText(end), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = textOn(color))
            Text(e.task.title, style = MaterialTheme.typography.labelSmall, color = textOn(color), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}


private fun clockText(minute: Int): String {
    val m = ((minute % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
    return "%02d:%02d".format(m / 60, m % 60)
}

/**
 * An entry on the time scale. Events are filled with their colour and dark or white text to match
 * ("away" ones hatched); tasks are tinted with a solid edge, a checkbox, and crossed out when done.
 */
@Composable
private fun TimeBlockView(
    entry: CalendarEntry,
    height: Dp,
    nested: Boolean,
    compact: Boolean,
    faded: Boolean,
    hidden: Boolean,
    actions: GridActions,
    modifier: Modifier,
) {
    val task = entry.task
    val scheme = MaterialTheme.colorScheme
    val color = entryColor(task)
    val shape = RoundedCornerShape(6.dp)
    val event = task.happens
    val dim = !event && (entry.projected || task.isDone)
    val fill = if (event) color else color.copy(alpha = if (dim) 0.14f else 0.28f)
    val ink = if (event) textOn(color) else if (dim) scheme.onSurfaceVariant else scheme.onSurface
    val style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge
    val lineHeight = if (compact) 13.dp else 17.dp
    val showTime = !compact && height >= 44.dp
    val lines = (((height - 4.dp) / lineHeight).toInt() - if (showTime) 1 else 0).coerceAtLeast(1)
    val away = task.eventType == EventType.AWAY.name
    val check = !event && !entry.projected && !compact && height >= 28.dp
    Row(
        modifier
            .alpha(if (hidden) 0.3f else if (faded) 0.55f else 1f)
            .clip(shape)
            .background(fill)
            // A block drawn over another one gets an outline in the page colour to stand apart.
            .then(if (nested) Modifier.border(1.dp, scheme.background, shape) else Modifier)
            .then(if (event) Modifier else Modifier.drawBehind { drawRect(color.copy(alpha = if (dim) 0.5f else 1f), size = Size(3.dp.toPx(), size.height)) })
            .then(if (away) Modifier.drawWithContent { hatch(ink.copy(alpha = 0.25f)); drawContent() } else Modifier)
            .clickable { actions.onOpen(entry) }
            .padding(start = if (event) 4.dp else if (check) 0.dp else 6.dp, end = 2.dp, top = if (check) 0.dp else 2.dp),
    ) {
        if (check) PriorityCheckbox(task.isDone, task.priority, { actions.onToggleDone(task) }, small = true, modifier = Modifier.size(26.dp))
        Column(Modifier.padding(top = if (check) 4.dp else 0.dp)) {
            Text(
                entryTitle(entry).let { if (task.eventType == EventType.FOCUS.name) "◎ $it" else it },
                style = style,
                fontWeight = FontWeight.Medium,
                maxLines = lines,
                overflow = TextOverflow.Ellipsis,
                color = ink,
                textDecoration = if (task.isDone && !event) TextDecoration.LineThrough else null,
            )
            if (showTime) {
                val place = task.location?.let { " · $it" }.orEmpty()
                Text(entryTimeText(entry) + place, style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Diagonal stripes across the block ("out of office"). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.hatch(color: Color) {
    val step = 10.dp.toPx()
    clipRect {
        var x = -size.height
        while (x < size.width) {
            drawLine(color, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 2.dp.toPx())
            x += step
        }
    }
}

/** An all-day entry above the time scale: one line, filled like the blocks below. */
@Composable
private fun AllDayChip(entry: CalendarEntry, now: Long, dimPast: Boolean, onOpen: (CalendarEntry) -> Unit) {
    val task = entry.task
    val color = entryColor(task)
    val event = task.happens
    val scheme = MaterialTheme.colorScheme
    val past = dimPast && entryEnd(entry) < now
    Text(
        (if (task.id in LocalOverdue.current) "Просрочено: " else "") + entryTitle(entry),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 20.dp)
            .alpha(if (past) 0.55f else 1f)
            .clip(RoundedCornerShape(6.dp))
            .background(if (event) color else color.copy(alpha = 0.25f))
            .then(if (task.eventType == EventType.AWAY.name) Modifier.drawWithContent { hatch(textOn(color).copy(alpha = 0.25f)); drawContent() } else Modifier)
            .clickable { onOpen(entry) }
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = if (event) textOn(color) else scheme.onSurface,
        textDecoration = if (task.isDone && !event) TextDecoration.LineThrough else null,
    )
}

@Composable
private fun DraftChip(draft: QuickDraft) {
    val scheme = MaterialTheme.colorScheme
    Text(
        draft.cleanTitle.ifBlank { "(Без названия)" },
        modifier = Modifier.fillMaxWidth().heightIn(min = 20.dp).clip(RoundedCornerShape(6.dp)).background(scheme.primary).padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = scheme.onPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

