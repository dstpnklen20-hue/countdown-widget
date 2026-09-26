package com.claudecode.countdown.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.claudecode.countdown.EditCountdownActivity
import com.claudecode.countdown.R
import com.claudecode.countdown.data.CountdownRepository
import com.claudecode.countdown.pluralRu
import java.util.concurrent.TimeUnit

class CountdownWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.claudecode.countdown.ACTION_REFRESH_WIDGETS"

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val countdownId = CountdownRepository.getWidgetCountdownId(context, appWidgetId)
            val countdown = countdownId?.let { CountdownRepository.get(context, it) }
            val views = RemoteViews(context.packageName, R.layout.widget_countdown)

            if (countdown == null) {
                views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_not_configured))
                views.setTextViewText(R.id.widget_value, "–")
                views.setTextViewText(R.id.widget_unit, "")
                views.setTextViewText(R.id.widget_subtitle, "")
            } else {
                views.setTextViewText(R.id.widget_title, countdown.title)
                val remaining = countdown.targetMillis - System.currentTimeMillis()

                if (remaining <= 0) {
                    views.setTextViewText(R.id.widget_value, "🎉")
                    views.setTextViewText(R.id.widget_unit, context.getString(R.string.widget_arrived))
                    views.setTextViewText(R.id.widget_subtitle, "")
                } else {
                    val days = TimeUnit.MILLISECONDS.toDays(remaining)
                    val hours = TimeUnit.MILLISECONDS.toHours(remaining) % 24
                    val minutes = TimeUnit.MILLISECONDS.toMinutes(remaining) % 60

                    when {
                        days > 0 -> {
                            views.setTextViewText(R.id.widget_value, days.toString())
                            views.setTextViewText(R.id.widget_unit, pluralRu(days, "день", "дня", "дней"))
                            views.setTextViewText(R.id.widget_subtitle, "$hours ч $minutes мин")
                        }
                        hours > 0 -> {
                            views.setTextViewText(R.id.widget_value, hours.toString())
                            views.setTextViewText(R.id.widget_unit, pluralRu(hours, "час", "часа", "часов"))
                            views.setTextViewText(R.id.widget_subtitle, "$minutes мин")
                        }
                        else -> {
                            views.setTextViewText(R.id.widget_value, minutes.toString())
                            views.setTextViewText(R.id.widget_unit, pluralRu(minutes, "минута", "минуты", "минут"))
                            views.setTextViewText(R.id.widget_subtitle, "")
                        }
                    }
                }
            }

            val clickIntent = Intent(context, EditCountdownActivity::class.java).apply {
                countdownId?.let { putExtra(EditCountdownActivity.EXTRA_COUNTDOWN_ID, it) }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val pendingIntent = PendingIntent.getActivity(
                context, appWidgetId, clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, CountdownWidgetProvider::class.java))
            for (id in ids) updateWidget(context, manager, id)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateWidget(context, appWidgetManager, id)
        WidgetUpdateScheduler.schedule(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            updateAllWidgets(context)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) CountdownRepository.removeWidgetMapping(context, id)
    }

    override fun onDisabled(context: Context) {
        WidgetUpdateScheduler.cancel(context)
    }

    override fun onEnabled(context: Context) {
        WidgetUpdateScheduler.schedule(context)
    }
}
