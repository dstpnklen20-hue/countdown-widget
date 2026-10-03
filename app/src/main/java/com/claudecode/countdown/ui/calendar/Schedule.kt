package com.claudecode.countdown.ui.calendar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.domain.CalendarEntry
import com.claudecode.countdown.domain.MINUTES_PER_DAY
import com.claudecode.countdown.domain.calendarEntries
import com.claudecode.countdown.domain.localTimeOf
import com.claudecode.countdown.ui.PriorityCheckbox
import com.claudecode.countdown.ui.rememberNow
import com.claudecode.countdown.ui.tasks.TasksViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.random.Random

private sealed interface ScheduleRow {
    data class Banner(val month: YearMonth) : ScheduleRow
    data class Day(val date: LocalDate, val entries: List<CalendarEntry>) : ScheduleRow
}

/**
 * The "Schedule" view of Google Calendar: one long list of the days that have something on them
 * (today always), each month opened by a picture, entries as coloured cards with their times,
 * and a line at the current time. Opens at [target]; [jump] changes when the user asks to go
 * there again (the "today" button).
 */
@Composable
internal fun ScheduleView(
    tasks: List<Task>,
    today: LocalDate,
    target: LocalDate,
    jump: Int,
    range: ClosedRange<LocalDate>,
    vm: TasksViewModel,
    onOpen: (CalendarEntry) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
) {
    // A year of repeats is worth computing off the main thread.
    val rows by produceState<List<ScheduleRow>?>(null, tasks, range, today) {
        value = withContext(Dispatchers.Default) { scheduleRows(calendarEntries(tasks, range.start, range.endInclusive), range, today) }
    }
    val list = rows ?: return
    val state = rememberLazyListState()
    LaunchedEffect(target, jump, list.isNotEmpty()) {
        val index = list.indexOfFirst { it is ScheduleRow.Day && it.date >= target }
        // Show the month picture above the first day when it starts the month.
        if (index >= 0) state.scrollToItem(if (index > 0 && list[index - 1] is ScheduleRow.Banner) index - 1 else index)
    }
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 88.dp)) {
        items(list, key = {
            when (it) {
                is ScheduleRow.Banner -> "m:${it.month}"
                is ScheduleRow.Day -> "d:${it.date}"
            }
        }) { row ->
            when (row) {
                is ScheduleRow.Banner -> MonthBanner(row.month, Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
                is ScheduleRow.Day -> ScheduleDay(row.date, today, row.entries, vm, onOpen, onOpenDay)
            }
        }
    }
}

private fun scheduleRows(entries: Map<LocalDate, List<CalendarEntry>>, range: ClosedRange<LocalDate>, today: LocalDate): List<ScheduleRow> {
    val rows = mutableListOf<ScheduleRow>()
    var month: YearMonth? = null
    var day = range.start
    while (day <= range.endInclusive) {
        if (YearMonth.from(day) != month) {
            month = YearMonth.from(day)
            rows += ScheduleRow.Banner(month)
        }
        val list = entries[day].orEmpty()
        if (list.isNotEmpty() || day == today) rows += ScheduleRow.Day(day, list)
        day = day.plusDays(1)
    }
    return rows
}

/** A day of the schedule: weekday and number on the left (today in a circle), its cards on the right. */
@Composable
private fun ScheduleDay(
    day: LocalDate,
    today: LocalDate,
    entries: List<CalendarEntry>,
    vm: TasksViewModel,
    onOpen: (CalendarEntry) -> Unit,
    onOpenDay: ((LocalDate) -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    val isToday = day == today
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)) {
        Column(
            Modifier
                .width(60.dp)
                .clip(RoundedCornerShape(12.dp))
                .then(if (onOpenDay != null) Modifier.clickable { onOpenDay(day) } else Modifier)
                .padding(vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                WEEK_DAYS[day.dayOfWeek.value - 1],
                style = MaterialTheme.typography.labelMedium,
                color = if (isToday) scheme.primary else scheme.onSurfaceVariant,
            )
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(if (isToday) scheme.primary else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${day.dayOfMonth}",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (isToday) scheme.onPrimary else scheme.onSurface,
                )
            }
        }
        Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (entries.isEmpty()) {
                Text(
                    "Ничего не запланировано",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 14.dp, horizontal = 4.dp),
                )
            }
            // The current time as a line between the cards: before the first one that starts later.
            val now by rememberNow(60_000, enabled = isToday)
            val nowMinute = localTimeOf(now).let { it.hour * 60 + it.minute }
            val lineAt = if (!isToday) -1 else entries.indexOfFirst { it.timed && it.start!! > nowMinute }.let { if (it < 0) entries.size else it }
            entries.forEachIndexed { i, e ->
                if (i == lineAt) NowLine()
                EntryCard(e, vm, onOpen)
            }
            if (isToday && lineAt == entries.size && entries.isNotEmpty()) NowLine()
        }
    }
}

@Composable
private fun NowLine() {
    val color = MaterialTheme.colorScheme.onSurface
    Box(Modifier.fillMaxWidth().height(10.dp), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(color))
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
    }
}

/** "05:45–06:55"; a part of something longer: "С 23:00", "До 05:45"; all-day ones say so. */
internal fun entryTimeText(e: CalendarEntry): String {
    if (!e.timed) return if (e.parts > 1) "Весь день · ${e.part}-й из ${e.parts}" else "Весь день"
    val start = clock(e.start!!)
    val end = clock(e.end!!)
    return when {
        e.parts > 1 && e.part == 1 -> "С $start"
        e.parts > 1 && e.part == e.parts -> "До $end"
        e.parts > 1 -> "Весь день"
        // A task with only a due time has no real length to show.
        e.task.startAt == null -> start
        else -> "$start–$end"
    }
}

private fun clock(minute: Int): String = if (minute >= MINUTES_PER_DAY) "24:00" else "%02d:%02d".format(minute / 60, minute % 60)

internal fun entryTitle(e: CalendarEntry): String =
    if (e.parts > 1 && e.timed) "${e.task.title} (${e.part}-й день из ${e.parts})" else e.task.title

/**
 * An entry of the schedule. Events are cards filled with their colour, like Google Calendar;
 * tasks are lighter cards with a checkbox, so the two kinds are told apart at a glance.
 */
@Composable
private fun EntryCard(e: CalendarEntry, vm: TasksViewModel, onOpen: (CalendarEntry) -> Unit) {
    val task = e.task
    val color = entryColor(task)
    val scheme = MaterialTheme.colorScheme
    if (task.happens) {
        val ink = textOn(color)
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(color)
                .clickable { onOpen(e) }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(entryTitle(e), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(entryTimeText(e), style = MaterialTheme.typography.bodyMedium, color = ink.copy(alpha = 0.85f), maxLines = 1)
        }
    } else {
        val faded = task.isDone || e.projected
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(color.copy(alpha = 0.16f))
                .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                .clickable { onOpen(e) }
                .padding(end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (e.projected) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Repeat, "Будущий повтор", tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            } else {
                PriorityCheckbox(task.isDone, task.priority, { vm.toggleDone(task) })
            }
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(
                    entryTitle(e),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (faded) scheme.onSurfaceVariant else scheme.onSurface,
                    textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val overdue = task.id in LocalOverdue.current
                Text(if (overdue) "Просрочено" else entryTimeText(e), style = MaterialTheme.typography.bodySmall, color = if (overdue) scheme.error else scheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

private enum class Season { WINTER, SPRING, SUMMER, AUTUMN }

private class ScenePalette(
    val skyTop: Color,
    val skyBottom: Color,
    val far: Color,
    val near: Color,
    val light: Color,
    val accent: Color,
    val accent2: Color,
)

private fun paletteOf(season: Season) = when (season) {
    Season.WINTER -> ScenePalette(Color(0xFF1D3557), Color(0xFF5B7FA6), Color(0xFFB8CCE0), Color(0xFFEAF2FA), Color(0xFFF4F1DE), Color(0xFF2F5D50), Color(0xFFFFFFFF))
    Season.SPRING -> ScenePalette(Color(0xFF5FA8D3), Color(0xFFCAE9FF), Color(0xFF8CC084), Color(0xFF5E9E5A), Color(0xFFFFE07A), Color(0xFFF6A6C1), Color(0xFFFFF3B0))
    Season.SUMMER -> ScenePalette(Color(0xFF1E6FB8), Color(0xFF8CC8F2), Color(0xFF7FB069), Color(0xFFE9B949), Color(0xFFFFD54F), Color(0xFF3E7C47), Color(0xFFFFF1C1))
    Season.AUTUMN -> ScenePalette(Color(0xFF1F3B3F), Color(0xFF3F6E6A), Color(0xFF8C4A2F), Color(0xFF5B2E1E), Color(0xFFF2C14E), Color(0xFFE08E36), Color(0xFFC0702F))
}

/**
 * A month's opening picture in the schedule, as Google Calendar has: a small landscape of the
 * season (snow, blossom, fields, falling leaves), varied by month, with the month's name on it.
 */
@Composable
private fun MonthBanner(month: YearMonth, modifier: Modifier = Modifier) {
    val season = when (month.monthValue) {
        12, 1, 2 -> Season.WINTER
        3, 4, 5 -> Season.SPRING
        6, 7, 8 -> Season.SUMMER
        else -> Season.AUTUMN
    }
    val palette = paletteOf(season)
    Box(modifier.fillMaxWidth().height(132.dp).clip(RoundedCornerShape(16.dp))) {
        Canvas(Modifier.fillMaxSize()) { drawScene(season, palette, month.monthValue) }
        Text(
            month.format(DateTimeFormatter.ofPattern("LLLL yyyy", ru)).replaceFirstChar { it.uppercase() } + " г.",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            modifier = Modifier.padding(start = 18.dp, top = 14.dp),
        )
    }
}

private fun DrawScope.drawScene(season: Season, p: ScenePalette, monthValue: Int) {
    val w = size.width
    val h = size.height
    val rnd = Random(monthValue * 31 + season.ordinal)
    drawRect(Brush.verticalGradient(listOf(p.skyTop, p.skyBottom)))
    // Sun (or a pale winter sun), placed differently each month.
    val sunX = w * (0.55f + 0.35f * ((monthValue * 7) % 10) / 10f)
    drawCircle(p.light.copy(alpha = 0.95f), radius = h * 0.13f, center = Offset(sunX, h * 0.34f))
    drawCircle(p.light.copy(alpha = 0.18f), radius = h * 0.24f, center = Offset(sunX, h * 0.34f))
    // Two rows of hills.
    val phase = (monthValue % 3) * 0.12f
    drawPath(hills(w, h, h * 0.58f, h * 0.12f, phase), p.far)
    drawPath(hills(w, h, h * 0.74f, h * 0.10f, phase + 0.4f), p.near)
    when (season) {
        Season.WINTER -> {
            repeat(5) { i ->
                val x = w * (0.08f + i * 0.09f) + rnd.nextFloat() * 10
                tree(x, h * 0.78f, h * 0.22f, p.accent, snow = p.accent2)
            }
            repeat(40) { drawCircle(Color.White.copy(alpha = 0.8f), radius = 1.5f + rnd.nextFloat() * 2.5f, center = Offset(rnd.nextFloat() * w, rnd.nextFloat() * h)) }
        }
        Season.SPRING -> {
            repeat(3) { i -> blossom(w * (0.1f + i * 0.13f), h * 0.80f, h * 0.2f, p) }
            repeat(28) {
                val c = if (it % 2 == 0) p.accent else p.accent2
                drawCircle(c, radius = 2.5f + rnd.nextFloat() * 2f, center = Offset(rnd.nextFloat() * w, h * (0.82f + rnd.nextFloat() * 0.16f)))
            }
        }
        Season.SUMMER -> {
            // Rows of a field, a tree and a few birds.
            repeat(8) { i ->
                val y = h * (0.80f + i * 0.025f)
                drawLine(p.accent2.copy(alpha = 0.5f), Offset(0f, y), Offset(w, y + (i - 4) * 3f), strokeWidth = 2f)
            }
            tree(w * 0.16f, h * 0.76f, h * 0.3f, p.accent)
            repeat(3) { i ->
                val x = w * (0.3f + i * 0.06f)
                val y = h * (0.22f + i * 0.04f)
                drawLine(Color(0xFF263238), Offset(x - 6, y - 4), Offset(x, y), strokeWidth = 2f)
                drawLine(Color(0xFF263238), Offset(x, y), Offset(x + 6, y - 4), strokeWidth = 2f)
            }
        }
        Season.AUTUMN -> {
            // Bales on the field, two orange trees and falling leaves.
            repeat(5) { i ->
                val x = w * (0.42f + i * 0.1f)
                val r = h * 0.045f
                drawCircle(p.light.copy(alpha = 0.85f), radius = r, center = Offset(x, h * 0.9f))
                drawCircle(p.accent2, radius = r * 0.45f, center = Offset(x, h * 0.9f))
            }
            tree(w * 0.12f, h * 0.8f, h * 0.28f, p.accent)
            tree(w * 0.24f, h * 0.83f, h * 0.2f, p.light)
            repeat(14) { drawCircle(p.accent.copy(alpha = 0.9f), radius = 2.5f + rnd.nextFloat() * 2f, center = Offset(rnd.nextFloat() * w, h * (0.2f + rnd.nextFloat() * 0.5f))) }
        }
    }
}

private fun hills(w: Float, h: Float, base: Float, amp: Float, phase: Float): Path = Path().apply {
    moveTo(0f, h)
    lineTo(0f, base)
    val steps = 4
    for (i in 0 until steps) {
        val x0 = w * i / steps
        val x1 = w * (i + 1) / steps
        val up = if ((i + (phase * 10).toInt()) % 2 == 0) -amp else amp * 0.4f
        quadraticTo((x0 + x1) / 2, base + up, x1, base)
    }
    lineTo(w, h)
    close()
}

private fun DrawScope.tree(x: Float, ground: Float, height: Float, crown: Color, snow: Color? = null) {
    drawRect(Color(0xFF4E342E), topLeft = Offset(x - height * 0.04f, ground - height * 0.35f), size = Size(height * 0.08f, height * 0.35f))
    drawCircle(crown, radius = height * 0.3f, center = Offset(x, ground - height * 0.6f))
    drawCircle(crown, radius = height * 0.22f, center = Offset(x - height * 0.18f, ground - height * 0.45f))
    drawCircle(crown, radius = height * 0.22f, center = Offset(x + height * 0.18f, ground - height * 0.45f))
    if (snow != null) drawCircle(snow, radius = height * 0.16f, center = Offset(x, ground - height * 0.78f))
}

private fun DrawScope.blossom(x: Float, ground: Float, height: Float, p: ScenePalette) {
    tree(x, ground, height, p.near)
    repeat(6) { i ->
        val a = i * 1.05f
        drawCircle(p.accent, radius = height * 0.06f, center = Offset(x + kotlin.math.cos(a) * height * 0.22f, ground - height * 0.58f + kotlin.math.sin(a) * height * 0.18f))
    }
}
