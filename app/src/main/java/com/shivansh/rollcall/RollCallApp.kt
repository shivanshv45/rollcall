package com.shivansh.rollcall

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class RollCallApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
