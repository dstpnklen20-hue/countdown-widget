package com.claudecode.countdown.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudecode.countdown.domain.CalendarEntry
import com.claudecode.countdown.domain.MINUTES_PER_DAY
import com.claudecode.countdown.domain.TimeBlock
import com.claudecode.countdown.domain.layoutBlocks
import com.claudecode.countdown.domain.localTimeOf
import com.claudecode.countdown.ui.rememberNow
import com.claudecode.countdown.ui.tasks.TasksViewModel
import java.time.LocalDate
import java.time.LocalTime

private val HOUR_HEIGHT = 52.dp
private val MIN_BLOCK_HEIGHT = 20.dp
private val HOUR_LABEL_WIDTH = 50.dp
/** How far a block drawn over another one is moved in. */
private val NEST_INDENT = 10.dp

/**
 * A time scale for one, three or seven days, like Google Calendar: hours down the side, each
 * hour a rounded tile, events as blocks filled with their colour (one that starts later than an
 * overlapping one is drawn over it, moved in), tasks as lighter blocks, all-day entries in a strip
 * above and a line at the current time. Tapping free space adds an entry at that half hour.
 */
@Composable
internal fun TimeGrid(
    days: List<LocalDate>,
    today: LocalDate,
    entries: Map<LocalDate, List<CalendarEntry>>,
    vm: TasksViewModel,
    onOpenTask: (String) -> Unit,
    onOpenDay: ((LocalDate) -> Unit)?,
    onAddAt: (LocalDate, LocalTime) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val allDay = days.map { d -> entries[d].orEmpty().filter { !it.timed } }
    val timed = days.map { d -> entries[d].orEmpty().filter { it.timed } }
    val scroll = rememberScrollState()
    val hourPx = with(LocalDensity.current) { HOUR_HEIGHT.toPx() }
    LaunchedEffect(days.first(), days.size) {
        // Open at the current hour on today's page, else at the first entry or the morning, an hour above.
        val first = timed.flatten().minOfOrNull { it.start!! }?.div(60)
        val hour = if (today in days) LocalTime.now().hour else first ?: 8
        scroll.scrollTo(((hour - 1).coerceAtLeast(0) * hourPx).toInt())
    }
    val now by rememberNow(60_000)
    val nowMinute = localTimeOf(now).let { it.hour * 60 + it.minute }
    val compact = days.size > 3

    Column(Modifier.fillMaxSize().padding(end = 6.dp)) {
        // Day captions: weekday over a big number, today's in a filled circle.
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Spacer(Modifier.width(HOUR_LABEL_WIDTH))
            for (d in days) {
                val isToday = d == today
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .then(if (onOpenDay != null) Modifier.clickable { onOpenDay(d) } else Modifier)
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
        if (allDay.any { it.isNotEmpty() }) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Text(
                    "весь\nдень",
                    modifier = Modifier.width(HOUR_LABEL_WIDTH).padding(start = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
                val shown = if (days.size == 1) 4 else 2
                for (list in allDay) {
                    Column(Modifier.weight(1f).padding(horizontal = 1.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (e in list.take(shown)) AllDayChip(e, onOpenTask)
                        if (list.size > shown) Text("+${list.size - shown}", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scroll)
                .height(HOUR_HEIGHT * 24),
        ) {
            // Hour captions sit level with the lines they name.
            Box(Modifier.width(HOUR_LABEL_WIDTH).fillMaxHeight()) {
                for (h in 1..23) {
                    Text(
                        "%02d:00".format(h),
                        modifier = Modifier.offset(y = HOUR_HEIGHT * h - 8.dp).padding(start = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            val todayShown = today in days
            days.forEachIndexed { i, day ->
                DayColumn(
                    day = day,
                    entries = timed[i],
                    nowMinute = if (todayShown) nowMinute else null,
                    isToday = day == today,
                    onOpenTask = onOpenTask,
                    onAddAt = onAddAt,
                    compact = compact,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun DayColumn(
    day: LocalDate,
    entries: List<CalendarEntry>,
    nowMinute: Int?,
    isToday: Boolean,
    onOpenTask: (String) -> Unit,
    onAddAt: (LocalDate, LocalTime) -> Unit,
    compact: Boolean,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val tile = scheme.surfaceContainerLow
    val placed = remember(entries) {
        // Deeper blocks last, so a block drawn over another really is on top.
        layoutBlocks(entries.map { TimeBlock(it, it.start!!, it.end!!) }).sortedBy { it.depth }
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
            }
            // Free space: a new entry at that half hour. Blocks take their own taps.
            .pointerInput(day) {
                detectTapGestures { offset ->
                    val minute = ((offset.y / (size.height / 24f)) * 60).toInt().coerceIn(0, MINUTES_PER_DAY - 1) / 30 * 30
                    onAddAt(day, LocalTime.of(minute / 60, minute % 60))
                }
            },
    ) {
        for (p in placed) {
            val top = HOUR_HEIGHT * (p.start / 60f)
            val height = (HOUR_HEIGHT * ((p.end - p.start) / 60f)).coerceAtLeast(MIN_BLOCK_HEIGHT)
            val lane = maxWidth / p.lanes
            val indent = (NEST_INDENT * p.depth).coerceAtMost(lane / 2)
            TimeBlockView(
                entry = p.item,
                height = height,
                nested = p.depth > 0,
                compact = compact,
                onOpenTask = onOpenTask,
                modifier = Modifier
                    .offset(x = lane * p.lane + indent, y = top)
                    .width(lane - indent)
                    .height(height)
                    .padding(horizontal = 1.dp, vertical = 1.dp),
            )
        }
        // Now: a strong line with a dot on today, a faint one across the other days.
        if (nowMinute != null) {
            val y = HOUR_HEIGHT * (nowMinute / 60f)
            val color = scheme.onSurface
            Box(Modifier.offset(y = y - 1.dp).fillMaxWidth().height(2.dp).background(if (isToday) color else color.copy(alpha = 0.3f)))
            if (isToday) Box(Modifier.offset(x = (-5).dp, y = y - 5.dp).size(10.dp).clip(CircleShape).background(color))
        }
    }
}

/**
 * An entry on the time scale. Events are filled with their colour and dark or white text to match;
 * tasks are tinted with a solid edge and crossed out when done.
 */
@Composable
private fun TimeBlockView(entry: CalendarEntry, height: Dp, nested: Boolean, compact: Boolean, onOpenTask: (String) -> Unit, modifier: Modifier) {
    val task = entry.task
    val scheme = MaterialTheme.colorScheme
    val color = entryColor(task)
    val shape = RoundedCornerShape(6.dp)
    val event = task.happens
    val faded = !event && (entry.projected || task.isDone)
    val fill = if (event) color else color.copy(alpha = if (faded) 0.14f else 0.28f)
    val ink = if (event) textOn(color) else if (faded) scheme.onSurfaceVariant else scheme.onSurface
    val style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge
    val lineHeight = if (compact) 13.dp else 17.dp
    val showTime = !compact && height >= 44.dp
    val lines = (((height - 4.dp) / lineHeight).toInt() - if (showTime) 1 else 0).coerceAtLeast(1)
    Column(
        modifier
            .clip(shape)
            .background(fill)
            // A block drawn over another one gets an outline in the page colour to stand apart.
            .then(if (nested) Modifier.border(1.dp, scheme.background, shape) else Modifier)
            .then(if (event) Modifier else Modifier.drawBehind { drawRect(color.copy(alpha = if (faded) 0.5f else 1f), size = Size(3.dp.toPx(), size.height)) })
            .clickable { onOpenTask(task.id) }
            .padding(start = if (event) 4.dp else 6.dp, end = 2.dp, top = 2.dp),
    ) {
        Text(
            task.title,
            style = style,
            fontWeight = FontWeight.Medium,
            maxLines = lines,
            overflow = TextOverflow.Ellipsis,
            color = ink,
            textDecoration = if (task.isDone && !event) TextDecoration.LineThrough else null,
        )
        if (showTime) Text(entryTimeText(entry), style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = 0.85f), maxLines = 1)
    }
}

/** An all-day entry above the time scale: one line, filled like the blocks below. */
@Composable
private fun AllDayChip(entry: CalendarEntry, onOpenTask: (String) -> Unit) {
    val task = entry.task
    val color = entryColor(task)
    val event = task.happens
    val scheme = MaterialTheme.colorScheme
    Text(
        task.title,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 20.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (event) color else color.copy(alpha = 0.25f))
            .clickable { onOpenTask(task.id) }
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = if (event) textOn(color) else scheme.onSurface,
        textDecoration = if (task.isDone && !event) TextDecoration.LineThrough else null,
    )
}
