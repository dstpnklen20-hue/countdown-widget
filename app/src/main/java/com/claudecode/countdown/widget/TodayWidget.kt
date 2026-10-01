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
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.CheckboxDefaults
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.claudecode.countdown.MainActivity
import com.claudecode.countdown.QuickAddActivity
import com.claudecode.countdown.ThemeManager
import com.claudecode.countdown.container
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.domain.TaskFilter
import com.claudecode.countdown.domain.isOverdue
import com.claudecode.countdown.domain.today
import com.claudecode.countdown.ui.formatDue
import com.claudecode.countdown.ui.priorityColor

private val TaskIdKey = ActionParameters.Key<String>("task_id")

/** Home-screen list of today's and overdue tasks with checkboxes. */
class TodayWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = context.container.tasks
        val initial = repo.todayTasks()
        val theme = context.container.widgetTheme
        provideContent {
            // While the session lives, update() only recomposes, so the data must be observed here.
            val tasks by remember { repo.observeTodayTasks() }.collectAsState(initial)
            val themeVersion by theme.collectAsState()
            val palette = remember(themeVersion) { ThemeManager.widgetPalette(context) }
            val now = System.currentTimeMillis()
            val today = today()
            val rows = tasks.map { WidgetRow(it, formatDue(it, today), it.isOverdue(now, today)) }
            TodayContent(context, rows, palette)
        }
    }

    companion object {
        suspend fun refresh(context: Context) = TodayWidget().updateAll(context)
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = TodayWidget()
}

class CompleteTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[TaskIdKey] ?: return
        val repo = context.container.tasks
        repo.get(id)?.takeIf { !it.isDone }?.let { repo.setDone(it, true) }
        TodayWidget().updateAll(context)
    }
}

private data class WidgetRow(val task: Task, val due: String?, val overdue: Boolean)

private fun openTask(context: Context, taskId: String): Intent =
    MainActivity.openTaskIntent(context, taskId).setData(Uri.parse("tiktak://task/$taskId"))

@Composable
private fun TodayContent(context: Context, rows: List<WidgetRow>, p: ThemeManager.Palette) {
    val text = ColorProvider(Color(p.text))
    val secondary = ColorProvider(Color(p.textSecondary))
    val accent = ColorProvider(Color(p.accent))
    Column(
        GlanceModifier.fillMaxSize().background(Color(p.surface)).cornerRadius(20.dp).padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(GlanceModifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Сегодня",
                style = TextStyle(color = text, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.defaultWeight().clickable(
                    actionStartActivity(MainActivity.launchIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ),
            )
            if (rows.isNotEmpty()) Text("${rows.size}", style = TextStyle(color = secondary, fontSize = 13.sp))
            Box(
                GlanceModifier.size(40.dp).clickable(actionStartActivity(QuickAddActivity.intent(context, TaskFilter.Today))),
                contentAlignment = Alignment.Center,
            ) {
                Text("+", style = TextStyle(color = accent, fontSize = 26.sp, fontWeight = FontWeight.Bold))
            }
        }
        if (rows.isEmpty()) {
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Всё сделано 🎉", style = TextStyle(color = secondary, fontSize = 14.sp))
            }
        } else {
            LazyColumn(GlanceModifier.fillMaxSize()) {
                items(rows, itemId = { it.task.id.hashCode().toLong() }) { row ->
                    TaskRowView(context, row, text, secondary, accent)
                }
            }
        }
    }
}

@Composable
private fun TaskRowView(context: Context, row: WidgetRow, text: ColorProvider, secondary: ColorProvider, accent: ColorProvider) {
    val task = row.task
    Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        CheckBox(
            checked = false,
            onCheckedChange = actionRunCallback<CompleteTaskAction>(actionParametersOf(TaskIdKey to task.id)),
            colors = CheckboxDefaults.colors(
                checkedColor = accent,
                uncheckedColor = ColorProvider(priorityColor(task.priority, Color(0xFF9E9E9E))),
            ),
        )
        Column(GlanceModifier.defaultWeight().clickable(actionStartActivity(openTask(context, task.id)))) {
            Text(task.title, maxLines = 1, style = TextStyle(color = text, fontSize = 14.sp))
            if (row.due != null) {
                Text(
                    row.due,
                    maxLines = 1,
                    style = TextStyle(color = if (row.overdue) ColorProvider(Color(0xFFE53935)) else secondary, fontSize = 11.sp),
                )
            }
        }
    }
    Spacer(GlanceModifier.height(2.dp))
}
