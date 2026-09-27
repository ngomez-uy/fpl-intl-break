package com.fplintbreak.app.data

import android.content.Context

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var teamId: String
        get() = prefs.getString(KEY_TEAM, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_TEAM, value).apply()

    private companion object {
        const val KEY_TEAM = "teamId"
    }
}
