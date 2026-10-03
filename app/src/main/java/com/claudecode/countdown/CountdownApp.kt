package com.claudecode.countdown

import android.app.Application
import com.claudecode.countdown.data.sync.SyncManager

class CountdownApp : Application() {
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        ThemeManager.applyNightMode(this)
        // Before anything computes a date: the app may run in a zone of its own.
        container.settings
        // Signed in: start watching the database now, so changes from widgets and reminders get synced too.
        if (SyncManager.isSignedIn(this)) container.sync
    }
}
