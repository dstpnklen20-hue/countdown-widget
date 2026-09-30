package com.claudecode.countdown.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.claudecode.countdown.MainActivity
import com.claudecode.countdown.R
import com.claudecode.countdown.ThemeManager
import com.claudecode.countdown.container
import com.claudecode.countdown.launchAsync
import com.claudecode.countdown.data.CountdownRepository
import com.claudecode.countdown.pluralRu
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class CountdownWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.claudecode.countdown.ACTION_REFRESH_WIDGETS"
        private const val SUBTITLE_MIN_HEIGHT_DP = 110
        private const val COMPACT_MAX_WIDTH_DP = 100

        private suspend fun renderWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val countdown = CountdownRepository.getWidgetCountdown(context, appWidgetId)
            val countdownId = countdown?.id
            val views = RemoteViews(context.packageName, R.layout.widget_countdown)
            val palette = ThemeManager.widgetPalette(context)

            var value = "–"
            var unit = ""
            var subtitle = ""
            val title: String

            if (countdown == null) {
                title = context.getString(R.string.widget_not_configured)
            } else {
                title = countdown.title
                val remaining = countdown.targetMillis - System.currentTimeMillis()

                if (remaining <= 0) {
                    value = "🎉"
                    unit = context.getString(R.string.widget_arrived)
                } else {
                    val days = TimeUnit.MILLISECONDS.toDays(remaining)
                    val hours = TimeUnit.MILLISECONDS.toHours(remaining) % 24
                    val minutes = TimeUnit.MILLISECONDS.toMinutes(remaining) % 60

                    when {
                        days > 0 -> {
                            value = days.toString()
                            unit = pluralRu(days, "день", "дня", "дней")
                            subtitle = "$hours ч $minutes мин"
                        }
                        hours > 0 -> {
                            value = hours.toString()
                            unit = pluralRu(hours, "час", "часа", "часов")
                            subtitle = "$minutes мин"
                        }
                        else -> {
                            value = minutes.toString()
                            unit = pluralRu(minutes, "минута", "минуты", "минут")
                        }
                    }
                }
            }

            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            val minHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)

            // Narrow (1x1) widget, only reachable on tablets: number on top, unit underneath.
            val compact = minWidthDp in 1 until COMPACT_MAX_WIDTH_DP

            val valueText = SpannableString(if (unit.isEmpty() || compact) value else "$value $unit")
            if (unit.isNotEmpty() && !compact) {
                // Number and unit share one auto-sized TextView, so they always fit the widget cell.
                val unitStart = value.length + 1
                valueText.setSpan(RelativeSizeSpan(0.5f), unitStart, valueText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                valueText.setSpan(ForegroundColorSpan(palette.textSecondary), unitStart, valueText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            // The extra line only fits when the widget is taller than a single row.
            val extraLine = if (compact) unit else subtitle
            val showSubtitle = extraLine.isNotEmpty() && (compact || minHeightDp >= SUBTITLE_MIN_HEIGHT_DP)
            subtitle = extraLine

            views.setInt(R.id.widget_bg, "setColorFilter", palette.surface)
            views.setTextViewTextSize(R.id.widget_title, TypedValue.COMPLEX_UNIT_SP, if (compact) 10f else 12f)
            views.setTextViewText(R.id.widget_title, title)
            views.setTextColor(R.id.widget_title, palette.textSecondary)
            views.setTextViewText(R.id.widget_value, valueText)
            views.setTextColor(R.id.widget_value, palette.accent)
            views.setTextViewText(R.id.widget_subtitle, subtitle)
            views.setTextColor(R.id.widget_subtitle, palette.textSecondary)
            views.setViewVisibility(R.id.widget_subtitle, if (showSubtitle) View.VISIBLE else View.GONE)

            val clickIntent = if (countdownId != null) {
                MainActivity.openTaskIntent(context, countdownId)
            } else {
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pendingIntent = PendingIntent.getActivity(
                context, appWidgetId, clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private suspend fun renderAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, CountdownWidgetProvider::class.java))
            for (id in ids) renderWidget(context, manager, id)
        }

        fun updateWidget(context: Context, appWidgetId: Int) {
            context.container.appScope.launch {
                renderWidget(context, AppWidgetManager.getInstance(context), appWidgetId)
            }
        }

        fun updateAllWidgets(context: Context) {
            context.container.appScope.launch { renderAll(context) }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        launchAsync(context) {
            for (id in appWidgetIds) renderWidget(context, appWidgetManager, id)
        }
        WidgetUpdateScheduler.schedule(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        launchAsync(context) { renderWidget(context, appWidgetManager, appWidgetId) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            launchAsync(context) {
                renderAll(context)
                TodayWidget.refresh(context)
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        launchAsync(context) {
            for (id in appWidgetIds) CountdownRepository.removeWidgetBinding(context, id)
        }
    }

    override fun onDisabled(context: Context) {
        WidgetUpdateScheduler.cancel(context)
    }

    override fun onEnabled(context: Context) {
        WidgetUpdateScheduler.schedule(context)
    }
}
