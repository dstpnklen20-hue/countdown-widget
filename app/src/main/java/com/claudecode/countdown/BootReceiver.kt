package com.claudecode.countdown

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.claudecode.countdown.widget.CountdownWidgetProvider
import com.claudecode.countdown.widget.WidgetUpdateScheduler

/** Alarms do not survive reboots, and clock or time zone changes move every trigger. */
class BootReceiver : BroadcastReceiver() {
    private val handled = setOf(
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED,
        Intent.ACTION_TIME_CHANGED,
        Intent.ACTION_TIMEZONE_CHANGED,
    )

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in handled) return
        WidgetUpdateScheduler.schedule(context)
        CountdownWidgetProvider.updateAllWidgets(context)
        launchAsync(context) { context.container.reminders.deliverDueAndReschedule() }
    }
}
