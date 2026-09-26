package com.claudecode.countdown

import android.app.Application

class CountdownApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeManager.applyNightMode(this)
    }
}
