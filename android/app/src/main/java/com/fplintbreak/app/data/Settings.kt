package com.fplintbreak.app.data

import android.content.Context

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var teamId: String
        get() = prefs.getString(KEY_TEAM, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_TEAM, value).apply()

    var leagueId: Int?
        get() = prefs.getInt(KEY_LEAGUE, -1).takeIf { it > 0 }
        set(value) = prefs.edit().putInt(KEY_LEAGUE, value ?: -1).apply()

    private companion object {
        const val KEY_TEAM = "teamId"
        const val KEY_LEAGUE = "leagueId"
    }
}
