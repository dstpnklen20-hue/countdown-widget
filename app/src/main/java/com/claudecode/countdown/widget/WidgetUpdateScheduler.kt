package com.claudecode.countdown.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock

object WidgetUpdateScheduler {
    private const val INTERVAL_MS = 15 * 60 * 1000L

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, CountdownWidgetProvider::class.java).apply {
            action = CountdownWidgetProvider.ACTION_REFRESH
        }
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + INTERVAL_MS,
            INTERVAL_MS,
            pendingIntent(context)
        )
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent(context))
    }
}
