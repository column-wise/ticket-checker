package com.ticketchecker.notification

import android.content.Context

object AlertSettings {
    private const val PREF_NAME = "alert_settings"
    private const val KEY_OVERRIDE_SILENT = "override_silent_mode"

    fun isOverrideSilentMode(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_OVERRIDE_SILENT, false)

    fun setOverrideSilentMode(context: Context, value: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_OVERRIDE_SILENT, value).apply()
    }
}
