package com.claudecode.countdown.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.claudecode.countdown.MainActivity
import com.claudecode.countdown.QuickAddActivity
import com.claudecode.countdown.ThemeManager
import com.claudecode.countdown.container
import com.claudecode.countdown.data.db.CalendarLayer
import com.claudecode.countdown.data.db.DisplayMode
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.data.db.TaskList
import com.claudecode.countdown.domain.CalendarEntry
import com.claudecode.countdown.domain.TaskFilter
import com.claudecode.countdown.domain.calendarEntries
import com.claudecode.countdown.domain.today
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private val ru = Locale("ru")
private val SHORT_DAYS = listOf("П", "В", "С", "Ч", "П", "С", "В")

/** What both calendar widgets read: tasks, lists and calendars (for colours). */
private data class CalendarData(val tasks: List<Task>, val lists: List<TaskList>, val calendars: List<CalendarLayer>)

private fun calendarData(context: Context) = context.container.tasks.let { repo ->
    combine(repo.observeTopLevel(), repo.observeLists(), repo.observeCalendars()) { t, l, c -> CalendarData(t, l, c) }
}

/** Entries shown on the widgets: events of shown calendars, open tasks of live lists. */
private fun CalendarData.visible(hidden: Set<String>): List<Task> {
    val listIds = lists.mapTo(HashSet()) { it.id }
    return tasks.filter { t ->
        !t.deleted && t.listId in listIds &&
            if (t.isEvent) (t.calendarId ?: CalendarLayer.PERSONAL_ID) !in hidden else !t.isDone
    }
}

private fun CalendarData.colorOf(t: Task, fallback: Int): Int =
    t.color ?: (if (t.isEvent) calendars.firstOrNull { it.id == (t.calendarId ?: CalendarLayer.PERSONAL_ID) }?.color else lists.firstOrNull { it.id == t.listId }?.color) ?: fallback

/** Opens the calendar on [day] (Day view). */
fun openCalendarDay(context: Context, day: LocalDate): Intent =
    MainActivity.launchIntent(context)
        .putExtra(MainActivity.EXTRA_OPEN_TOOL, "CALENDAR")
        .putExtra(MainActivity.EXTRA_CALENDAR_DAY, day.toEpochDay())
        // A distinct data URI per day keeps the PendingIntents apart.
        .setData(Uri.parse("tiktak://calendar/$day"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

private val MONTH_OFFSET = intPreferencesKey("monthOffset")

/**
 * Google Calendar's "Month" widget: the month as a grid with coloured bars for what is on each
 * day; arrows page through the months, a day opens it in the app.
 */
class MonthWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val source = calendarData(context)
        val initial = source.first()
        val theme = context.container.widgetTheme
        val settings = context.container.settings
        provideContent {
            val data by remember { source }.collectAsState(initial)
            val themeVersion by theme.collectAsState()
            val s by settings.state.collectAsState()
            val palette = remember(themeVersion) { ThemeManager.widgetPalette(context) }
            val offset = currentState<Preferences>()[MONTH_OFFSET] ?: 0
            val today = today()
            val month = YearMonth.from(today).plusMonths(offset.toLong())
            MonthContent(context, month, today, data, s.hiddenCalendars, s.firstDay, palette)
        }
    }

    companion object {
        suspend fun refresh(context: Context) = MonthWidget().updateAll(context)
    }
}

class MonthWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = MonthWidget()
}

private val DELTA = ActionParameters.Key<Int>("delta")

/** Pages the month widget by the "delta" parameter (0 goes back to this month). */
class MonthPageAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val delta = parameters[DELTA] ?: 0
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[MONTH_OFFSET] = if (delta == 0) 0 else (prefs[MONTH_OFFSET] ?: 0) + delta
        }
        MonthWidget().update(context, glanceId)
    }
}

@Composable
private fun MonthContent(
    context: Context,
    month: YearMonth,
    today: LocalDate,
    data: CalendarData,
    hidden: Set<String>,
    first: DayOfWeek,
    p: ThemeManager.Palette,
) {
    val text = ColorProvider(Color(p.text))
    val secondary = ColorProvider(Color(p.textSecondary))
    val start = month.atDay(1).with(TemporalAdjusters.previousOrSame(first))
    val weeks = generateSequence(start) { it.plusWeeks(1) }.takeWhile { it <= month.atEndOfMonth() }.toList()
    val entries = remember(data, month, hidden) { calendarEntries(data.visible(hidden), start, weeks.last().plusDays(6)) }
    Column(GlanceModifier.fillMaxSize().background(Color(p.surface)).cornerRadius(20.dp).padding(8.dp)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                month.format(DateTimeFormatter.ofPattern("LLLL yyyy", ru)).replaceFirstChar { it.uppercase() },
                style = TextStyle(color = text, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.defaultWeight().padding(start = 6.dp)
                    .clickable(actionRunCallback<MonthPageAction>(actionParametersOf(DELTA to 0))),
            )
            Arrow("‹", p) { actionRunCallback<MonthPageAction>(actionParametersOf(DELTA to -1)) }
            Spacer(GlanceModifier.width(4.dp))
            Arrow("›", p) { actionRunCallback<MonthPageAction>(actionParametersOf(DELTA to 1)) }
            Spacer(GlanceModifier.width(4.dp))
            Box(
                GlanceModifier.size(32.dp).cornerRadius(16.dp).background(Color(p.accent))
                    .clickable(actionStartActivity(QuickAddActivity.intent(context, TaskFilter.Today))),
                contentAlignment = Alignment.Center,
            ) {
                Text("+", style = TextStyle(color = ColorProvider(Color(p.onAccent)), fontSize = 20.sp, fontWeight = FontWeight.Bold))
            }
        }
        Row(GlanceModifier.fillMaxWidth().padding(top = 4.dp)) {
            for (i in 0 until 7) {
                val d = first.plus(i.toLong())
                Text(
                    SHORT_DAYS[d.value - 1],
                    style = TextStyle(color = secondary, fontSize = 11.sp, textAlign = TextAlign.Center),
                    modifier = GlanceModifier.defaultWeight(),
                )
            }
        }
        for (week in weeks) {
            Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                for (i in 0 until 7) {
                    val day = week.plusDays(i.toLong())
                    DayCell(context, day, month, today, entries[day].orEmpty(), data, p, text, secondary)
                }
            }
        }
    }
}

@Composable
private fun Arrow(label: String, p: ThemeManager.Palette, action: () -> androidx.glance.action.Action) {
    Box(
        GlanceModifier.size(32.dp).cornerRadius(16.dp).background(Color(p.textSecondary).copy(alpha = 0.15f)).clickable(action()),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = TextStyle(color = ColorProvider(Color(p.text)), fontSize = 18.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun androidx.glance.layout.RowScope.DayCell(
    context: Context,
    day: LocalDate,
    month: YearMonth,
    today: LocalDate,
    items: List<CalendarEntry>,
    data: CalendarData,
    p: ThemeManager.Palette,
    text: ColorProvider,
    secondary: ColorProvider,
) {
    val isToday = day == today
    val inMonth = YearMonth.from(day) == month
    Column(
        GlanceModifier.defaultWeight().fillMaxHeight().padding(1.dp).clickable(actionStartActivity(openCalendarDay(context, day))),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            GlanceModifier.size(20.dp).cornerRadius(10.dp).background(if (isToday) Color(p.accent) else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${day.dayOfMonth}",
                style = TextStyle(
                    color = when {
                        isToday -> ColorProvider(Color(p.onAccent))
                        inMonth -> text
                        else -> secondary
                    },
                    fontSize = 11.sp,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                ),
            )
        }
        // Up to three bars, one per entry, in its colour.
        for (e in items.distinctBy { it.task.id }.take(3)) {
            val color = Color(data.colorOf(e.task, p.accent))
            val event = e.task.isEvent || e.task.displayMode == DisplayMode.COUNTDOWN
            Spacer(GlanceModifier.height(2.dp))
            Box(GlanceModifier.fillMaxWidth().height(4.dp).cornerRadius(2.dp).background(if (event) color else color.copy(alpha = 0.5f))) {}
        }
    }
}

/**
 * A small widget for the day: the date large, the weekday, and what comes next today (or the
 * next thing in the days ahead), as Google Calendar's "Сегодня" widget.
 */
class DateWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val source = calendarData(context)
        val initial = source.first()
        val theme = context.container.widgetTheme
        val settings = context.container.settings
        provideContent {
            val data by remember { source }.collectAsState(initial)
            val themeVersion by theme.collectAsState()
            val s by settings.state.collectAsState()
            val palette = remember(themeVersion) { ThemeManager.widgetPalette(context) }
            val today = today()
            val next = remember(data, today, s.hiddenCalendars) { nextEntry(data.visible(s.hiddenCalendars), today) }
            DateContent(context, today, next, palette, next?.let { data.colorOf(it.task, palette.accent) })
        }
    }

    companion object {
        suspend fun refresh(context: Context) = DateWidget().updateAll(context)
    }
}

class DateWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = DateWidget()
}

/** The first entry that has not ended yet, within a week. */
private fun nextEntry(tasks: List<Task>, today: LocalDate): CalendarEntry? {
    val now = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
    val entries = calendarEntries(tasks, today, today.plusDays(7))
    return entries.keys.sorted().asSequence().flatMap { day -> entries.getValue(day).asSequence() }
        .firstOrNull { e -> e.date > today || (e.timed && e.end!! > now) || (!e.timed && e.task.isEvent) }
}

@Composable
private fun DateContent(context: Context, today: LocalDate, next: CalendarEntry?, p: ThemeManager.Palette, color: Int?) {
    val text = ColorProvider(Color(p.text))
    val secondary = ColorProvider(Color(p.textSecondary))
    Column(
        GlanceModifier.fillMaxSize().background(Color(p.surface)).cornerRadius(20.dp).padding(12.dp)
            .clickable(actionStartActivity(openCalendarDay(context, today))),
    ) {
        Text(
            today.format(DateTimeFormatter.ofPattern("EEEE", ru)).replaceFirstChar { it.uppercase() },
            style = TextStyle(color = ColorProvider(Color(p.accent)), fontSize = 13.sp, fontWeight = FontWeight.Medium),
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${today.dayOfMonth}", style = TextStyle(color = text, fontSize = 36.sp, fontWeight = FontWeight.Bold))
            Spacer(GlanceModifier.width(6.dp))
            Text(today.format(DateTimeFormatter.ofPattern("MMMM", ru)), style = TextStyle(color = secondary, fontSize = 14.sp), modifier = GlanceModifier.padding(bottom = 6.dp))
        }
        Spacer(GlanceModifier.height(4.dp))
        if (next == null) {
            Text("Ничего не запланировано", style = TextStyle(color = secondary, fontSize = 12.sp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(GlanceModifier.width(4.dp).height(30.dp).cornerRadius(2.dp).background(Color(color ?: p.accent))) {}
                Spacer(GlanceModifier.width(8.dp))
                Column {
                    Text(next.task.title, maxLines = 1, style = TextStyle(color = text, fontSize = 13.sp, fontWeight = FontWeight.Medium))
                    val start = next.start
                    val whenText = when {
                        next.date != today -> next.date.format(DateTimeFormatter.ofPattern("EE, d MMM", ru)) + (start?.let { ", %02d:%02d".format(it / 60, it % 60) } ?: "")
                        start != null -> "%02d:%02d".format(start / 60, start % 60) + (next.end?.let { "–%02d:%02d".format(it / 60 % 24, it % 60) } ?: "")
                        else -> "Весь день"
                    }
                    Text(whenText, maxLines = 1, style = TextStyle(color = secondary, fontSize = 11.sp))
                }
            }
        }
    }
}
