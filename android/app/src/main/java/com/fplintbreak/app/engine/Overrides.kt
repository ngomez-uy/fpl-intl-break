package com.fplintbreak.app.engine

import android.content.Context

/** A FotMob player the user picked by hand for an FPL player, when the automatic match was wrong. */
data class PlayerOverride(val fotmobId: Long, val fotmobName: String)

/**
 * Hand-picked FPL -> FotMob player matches, stored on the phone. The app's equivalent of
 * backend/data/overrides.json.
 */
class Overrides(context: Context) {
    private val prefs = context.getSharedPreferences("player_overrides", Context.MODE_PRIVATE)

    operator fun get(fplId: Int): PlayerOverride? {
        val raw = prefs.getString(fplId.toString(), null) ?: return null
        val id = raw.substringBefore('|').toLongOrNull() ?: return null
        return PlayerOverride(id, raw.substringAfter('|', ""))
    }

    fun set(fplId: Int, override: PlayerOverride) =
        prefs.edit().putString(fplId.toString(), "${override.fotmobId}|${override.fotmobName}").apply()

    fun clear(fplId: Int) = prefs.edit().remove(fplId.toString()).apply()
}
