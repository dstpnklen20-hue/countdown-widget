package com.claudecode.countdown.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.claudecode.countdown.MainActivity
import com.claudecode.countdown.QuickAddActivity
import com.claudecode.countdown.ThemeManager
import com.claudecode.countdown.container
import com.claudecode.countdown.data.db.DisplayMode
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.data.db.TaskList
import com.claudecode.countdown.domain.CalendarEntry
import com.claudecode.countdown.domain.MINUTES_PER_DAY
import com.claudecode.countdown.domain.TaskFilter
import com.claudecode.countdown.domain.calendarEntries
import com.claudecode.countdown.domain.today
import com.claudecode.countdown.ui.priorityColor
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ru = Locale("ru")
private val WEEK_DAYS = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")

/** How far ahead the widget looks. */
private const val DAYS_AHEAD = 14L

/**
 * Home-screen schedule like Google Calendar's widget: today's date on top, then the next two
 * weeks day by day, each event a card filled with its colour and each task a lighter one.
 */
class ScheduleWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = context.container.tasks
        val source = combine(repo.observeTopLevel(), repo.observeLists()) { tasks, lists -> tasks to lists }
        val initial = source.first()
        val theme = context.container.widgetTheme
        provideContent {
            // While the session lives, update() only recomposes, so the data must be observed here.
            val data by remember { source }.collectAsState(initial)
            val themeVersion by theme.collectAsState()
            val palette = remember(themeVersion) { ThemeManager.widgetPalette(context) }
            val today = today()
            val rows = remember(data, today) { scheduleRows(data.first, data.second, today) }
            ScheduleContent(context, today, rows, palette)
        }
    }

    companion object {
        suspend fun refresh(context: Context) = ScheduleWidget().updateAll(context)
    }
}

class ScheduleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = ScheduleWidget()
}

/** One line of the list; [first] marks the first entry of its day, which carries the date. */
private data class ScheduleLine(val entry: CalendarEntry, val first: Boolean, val color: Int, val event: Boolean)

private fun scheduleRows(tasks: List<Task>, lists: List<TaskList>, today: LocalDate): List<ScheduleLine> {
    val listColors = lists.associate { it.id to it.color }
    val live = tasks.filter { !it.deleted && it.listId in listColors && (!it.isDone || it.isEvent) }
    val entries = calendarEntries(live, today, today.plusDays(DAYS_AHEAD))
    return entries.keys.sorted().flatMap { day ->
        entries.getValue(day).mapIndexed { i, e ->
            val t = e.task
            val event = t.isEvent || t.displayMode == DisplayMode.COUNTDOWN
            val own = t.color ?: listColors[t.listId]
            val color = own ?: if (event) 0 else priorityColor(t.priority, Color(0xFF9E9E9E)).toArgb()
            ScheduleLine(e, i == 0, color, event)
        }
    }
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(alpha, red, green, blue)

private fun openTask(context: Context, taskId: String): Intent =
    MainActivity.openTaskIntent(context, taskId).setData(Uri.parse("tiktak://task/$taskId"))

@Composable
private fun ScheduleContent(context: Context, today: LocalDate, rows: List<ScheduleLine>, p: ThemeManager.Palette) {
    val text = ColorProvider(Color(p.text))
    val secondary = ColorProvider(Color(p.textSecondary))
    val accent = ColorProvider(Color(p.accent))
    Column(GlanceModifier.fillMaxSize().background(Color(p.surface)).cornerRadius(20.dp).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(GlanceModifier.fillMaxWidth().padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(
                GlanceModifier.defaultWeight().clickable(
                    actionStartActivity(MainActivity.launchIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ),
            ) {
                Text(
                    today.format(DateTimeFormatter.ofPattern("EEEE", ru)).replaceFirstChar { it.uppercase() },
                    style = TextStyle(color = accent, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                )
                Text(
                    today.format(DateTimeFormatter.ofPattern("d MMMM", ru)),
                    style = TextStyle(color = text, fontSize = 20.sp, fontWeight = FontWeight.Bold),
                )
            }
            Box(
                GlanceModifier.size(44.dp).cornerRadius(22.dp).background(Color(p.accent))
                    .clickable(actionStartActivity(QuickAddActivity.intent(context, TaskFilter.Today))),
                contentAlignment = Alignment.Center,
            ) {
                Text("+", style = TextStyle(color = ColorProvider(Color(p.onAccent)), fontSize = 24.sp, fontWeight = FontWeight.Bold))
            }
        }
        Spacer(GlanceModifier.height(6.dp))
        if (rows.isEmpty()) {
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Ничего не запланировано на две недели", style = TextStyle(color = secondary, fontSize = 13.sp))
            }
        } else {
            LazyColumn(GlanceModifier.fillMaxSize()) {
                items(rows, itemId = { "${it.entry.task.id}:${it.entry.date}:${it.entry.part}".hashCode().toLong() }) { line ->
                    LineView(context, line, today, p, text, secondary)
                }
            }
        }
    }
}

@Composable
private fun LineView(context: Context, line: ScheduleLine, today: LocalDate, p: ThemeManager.Palette, text: ColorProvider, secondary: ColorProvider) {
    val e = line.entry
    val isToday = e.date == today
    Column(GlanceModifier.fillMaxWidth()) {
        if (line.first) Spacer(GlanceModifier.height(6.dp))
        Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
            // The date sits beside the first entry of its day only.
            Column(GlanceModifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (line.first) {
                    Text(
                        "${e.date.dayOfMonth}",
                        style = TextStyle(color = if (isToday) ColorProvider(Color(p.accent)) else text, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                    )
                    Text(WEEK_DAYS[e.date.dayOfWeek.value - 1], style = TextStyle(color = secondary, fontSize = 11.sp))
                }
            }
            val fill = if (line.color == 0) Color(p.accent) else Color(line.color)
            val chip = if (line.event) fill else fill.copy(alpha = 0.22f)
            val ink = if (line.event) ColorProvider(if (fill.luminance() > 0.36f) Color(0xFF1C1B1F) else Color.White) else text
            Column(
                GlanceModifier
                    .defaultWeight()
                    .cornerRadius(10.dp)
                    .background(chip)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .clickable(actionStartActivity(openTask(context, e.task.id))),
            ) {
                Text((if (line.event) "" else "○ ") + e.task.title, maxLines = 1, style = TextStyle(color = ink, fontSize = 13.sp, fontWeight = FontWeight.Medium))
                Text(timeText(e), maxLines = 1, style = TextStyle(color = ink, fontSize = 11.sp))
            }
        }
    }
}

private fun clock(minute: Int): String = if (minute >= MINUTES_PER_DAY) "24:00" else "%02d:%02d".format(minute / 60, minute % 60)

private fun timeText(e: CalendarEntry): String {
    val start = e.start ?: return "Весь день"
    val end = e.end ?: start
    return when {
        e.parts > 1 && e.part == 1 -> "С ${clock(start)}"
        e.parts > 1 && e.part == e.parts -> "До ${clock(end)}"
        e.parts > 1 -> "Весь день"
        e.task.startAt == null -> clock(start)
        else -> "${clock(start)}–${clock(end)}"
    }
}
