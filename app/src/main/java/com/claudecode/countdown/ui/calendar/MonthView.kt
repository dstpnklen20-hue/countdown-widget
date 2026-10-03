package com.claudecode.countdown.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudecode.countdown.domain.CalendarEntry
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

/** The days of the week in display order, starting at [first]. */
internal fun weekDays(first: DayOfWeek): List<DayOfWeek> = List(7) { first.plus(it.toLong()) }

/** The first day of the week (as set by the user) on or before [day]. */
internal fun weekStart(day: LocalDate, first: DayOfWeek): LocalDate = day.with(TemporalAdjusters.previousOrSame(first))

/** The weeks a month view shows: from the week of the 1st to the week of the last day. */
internal fun monthWeeks(month: YearMonth, first: DayOfWeek): List<LocalDate> {
    val start = weekStart(month.atDay(1), first)
    val end = month.atEndOfMonth()
    return generateSequence(start) { it.plusWeeks(1) }.takeWhile { it <= end }.toList()
}

private const val CHIP_HEIGHT = 17

/**
 * Google Calendar's month: a grid of 5–6 weeks, each day with as many coloured chips as fit and
 * "+N" for the rest; days of the neighbouring months are greyed. Tapping a day opens it.
 */
@Composable
internal fun MonthChips(
    month: YearMonth,
    today: LocalDate,
    entries: Map<LocalDate, List<CalendarEntry>>,
    first: DayOfWeek,
    weekNumbers: Boolean,
    dimPast: Boolean,
    onOpenDay: (LocalDate) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val weeks = monthWeeks(month, first)
    Column(Modifier.fillMaxSize().padding(horizontal = 2.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            if (weekNumbers) Box(Modifier.width(22.dp))
            for (d in weekDays(first)) {
                Text(
                    WEEK_DAYS[d.value - 1].take(2).uppercase(),
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (month == YearMonth.from(today) && today.dayOfWeek == d) scheme.primary else scheme.onSurfaceVariant,
                )
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val rowHeight = maxHeight / weeks.size
            // Room for chips below the day number.
            val fits = (((rowHeight - 24.dp) / (CHIP_HEIGHT + 2).dp).toInt()).coerceAtLeast(1)
            Column(Modifier.fillMaxSize()) {
                for (week in weeks) {
                    Row(Modifier.fillMaxWidth().height(rowHeight)) {
                        if (weekNumbers) {
                            Text(
                                "${week.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}",
                                Modifier.width(22.dp).padding(top = 6.dp),
                                textAlign = TextAlign.Center,
                                fontSize = 10.sp,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        for (i in 0 until 7) {
                            val day = week.plusDays(i.toLong())
                            DayCell(
                                day = day,
                                inMonth = YearMonth.from(day) == month,
                                today = today,
                                items = entries[day].orEmpty(),
                                fits = fits,
                                dimPast = dimPast,
                                onClick = { onOpenDay(day) },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: LocalDate,
    inMonth: Boolean,
    today: LocalDate,
    items: List<CalendarEntry>,
    fits: Int,
    dimPast: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val isToday = day == today
    Column(
        modifier
            .border(0.5.dp, scheme.outlineVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 1.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            Modifier.size(20.dp).clip(CircleShape).background(if (isToday) scheme.primary else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${day.dayOfMonth}",
                fontSize = 12.sp,
                fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    isToday -> scheme.onPrimary
                    inMonth -> scheme.onSurface
                    else -> scheme.onSurfaceVariant.copy(alpha = 0.5f)
                },
            )
        }
        // When everything does not fit, the last line says how many more there are.
        val shown = if (items.size > fits) (fits - 1).coerceAtLeast(0) else items.size
        for (e in items.take(shown)) MonthChip(e, faded = !inMonth || (dimPast && e.date < today && e.task.happens))
        if (items.size > shown) {
            Text(
                "+${items.size - shown}",
                fontSize = 10.sp,
                lineHeight = 11.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(start = 3.dp),
            )
        }
    }
}

@Composable
private fun MonthChip(e: CalendarEntry, faded: Boolean) {
    val task = e.task
    val color = entryColor(task)
    val event = task.happens
    val scheme = MaterialTheme.colorScheme
    Text(
        // Only the title: in a phone-wide grid a time would leave no room for it.
        (if (task.id in LocalOverdue.current) "! " else "") + task.title,
        modifier = Modifier
            .fillMaxWidth()
            .height(CHIP_HEIGHT.dp)
            .alpha(if (faded || (task.isDone && !event)) 0.5f else 1f)
            .clip(RoundedCornerShape(4.dp))
            .background(if (event) color else color.copy(alpha = 0.22f))
            .padding(horizontal = 3.dp),
        fontSize = 10.sp,
        lineHeight = CHIP_HEIGHT.sp,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        color = if (event) textOn(color) else scheme.onSurface,
        textDecoration = if (task.isDone && !event) TextDecoration.LineThrough else null,
    )
}
