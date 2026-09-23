package com.dungeonwithin.savemanager

import android.content.Context

/** Persisted user options. */
object AppSettings {
    private const val PREFS = "settings"
    private const val KEY_RESULT_NOTIFICATIONS = "result_notifications"

    fun areResultNotificationsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_RESULT_NOTIFICATIONS, true)

    fun setResultNotificationsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RESULT_NOTIFICATIONS, enabled)
            .apply()
    }
}
