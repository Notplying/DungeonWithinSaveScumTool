package com.dungeonwithin.savemanager

import android.app.Application
import com.google.android.material.color.DynamicColors

/** Applies wallpaper-based Material You colors to all activities. */
class SaveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
