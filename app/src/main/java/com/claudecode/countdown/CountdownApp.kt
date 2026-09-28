package com.claudecode.countdown

import android.app.Application

class CountdownApp : Application() {
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        ThemeManager.applyNightMode(this)
    }
}
