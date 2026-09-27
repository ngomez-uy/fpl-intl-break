package com.fplintbreak.app.engine

import com.fplintbreak.app.data.LiveMatch
import com.fplintbreak.app.data.OnPitch

// "Live now": a player's international match in progress, and whether they're on the pitch.
// Port of backend/src/live.ts.

// A match can only be live from kick-off until roughly two and a half hours later.
private const val MAX_MATCH_MS = 150 * MINUTE

internal fun onPitch(match: MatchDetails, playerId: Long): OnPitch {
    val lineup = match.content.lineup
    val teams = listOfNotNull(lineup?.homeTeam, lineup?.awayTeam)
    if (teams.isEmpty()) return OnPitch.UNKNOWN
    val starter = teams.any { t -> t.starters.any { it.id == playerId } }
    val sub = teams.any { t -> t.subs.any { it.id == playerId } }
    val info = substitutionInfo(match, playerId)
    return when {
        info.subbedOffMinute != null -> OnPitch.SUBBED_OFF
        starter || info.subbedOnMinute != null -> OnPitch.PLAYING
        sub -> OnPitch.BENCH
        else -> OnPitch.NOT_IN_SQUAD
    }
}

/** The fixture being played right now, if any. Only fetches match details inside the kick-off window. */
internal suspend fun liveMatch(
    fotmob: FotMob,
    fixtures: List<BreakFixture>,
    playerId: Long,
    now: Long = System.currentTimeMillis(),
): LiveMatch? {
    val candidate = fixtures.firstOrNull {
        val t = parseMillis(it.date)
        !it.finished && t <= now && now - t < MAX_MATCH_MS
    } ?: return null
    val match = fotmob.match(candidate.fotmobMatchId)
    if (!match.general.started || match.general.finished) return null
    val status = match.header?.status
    return LiveMatch(
        fotmobMatchId = candidate.fotmobMatchId,
        opponent = candidate.opponent,
        home = candidate.home,
        minute = status?.liveTime?.short ?: status?.reason?.short ?: "Live",
        score = status?.scoreStr,
        onPitch = onPitch(match, playerId),
    )
}
