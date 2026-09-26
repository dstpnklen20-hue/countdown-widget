package com.claudecode.countdown

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.claudecode.countdown.widget.CountdownWidgetProvider
import com.claudecode.countdown.widget.WidgetUpdateScheduler

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            WidgetUpdateScheduler.schedule(context)
            CountdownWidgetProvider.updateAllWidgets(context)
        }
    }
}
