package com.fplintbreak.app.engine

import com.fplintbreak.app.data.BreakWindow
import com.fplintbreak.app.data.CallUp
import com.fplintbreak.app.data.CallUpStatus
import com.fplintbreak.app.data.Nation
import com.fplintbreak.app.data.SourceCheck
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// A player's senior national team, whether they're in its squad for the break, and which of
// its fixtures fall inside the break. Port of backend/src/national.ts.

internal data class BreakFixture(
    val fotmobMatchId: Long,
    val date: String,
    val opponent: String,
    val home: Boolean,
    val competition: String,
    val finished: Boolean,
    val score: String?,
)

private val YOUTH_OR_OTHER = Regex("\\bU\\s?\\d{2}\\b|under\\s?\\d{2}|\\(W\\)|olympic", RegexOption.IGNORE_CASE)

// A national-team spell FotMob closed around the break usually means the player left the squad.
private const val LEFT_SQUAD_GRACE_MS = 3 * DAY

private fun seniorEntry(player: PlayerData): CareerEntry? =
    player.careerHistory?.careerItems?.get("national team")?.teamEntries.orEmpty()
        .find { it.teamId > 0 && !YOUTH_OR_OTHER.containsMatchIn(it.team) }

private fun country(player: PlayerData): String? =
    (player.playerInformation.find { it.title == "Country" }?.value?.fallback as? JsonPrimitive)
        ?.takeIf { it.isString }?.contentOrNull

internal suspend fun resolveNation(fotmob: FotMob, player: PlayerData): Pair<Nation?, String?> {
    val senior = seniorEntry(player)
    if (senior != null) return Nation(senior.teamId, senior.team, capped = true) to senior.endDate
    val name = country(player) ?: return null to null
    val team = fotmob.searchTeams(name).find { it.name == name }
    return (team?.let { Nation(it.id.toInt(), name, capped = false) }) to null
}

internal fun breakFixtures(team: TeamData, window: BreakWindow): List<BreakFixture> {
    val from = parseMillis(window.from)
    val to = parseMillis(window.to)
    return team.fixtures
        .filter {
            val t = parseMillis(it.status.utcTime)
            t in from until to && !it.status.cancelled
        }
        .map { f ->
            val home = f.home.id == team.id
            BreakFixture(
                fotmobMatchId = f.id,
                date = f.status.utcTime,
                opponent = if (home) f.away.name else f.home.name,
                home = home,
                competition = f.tournament?.name.orEmpty(),
                finished = f.status.finished,
                score = if (f.status.finished) "${f.home.name} ${f.home.score ?: "?"}–${f.away.score ?: "?"} ${f.away.name}" else null,
            )
        }
        .sortedBy { parseMillis(it.date) }
}

/** FotMob's verdict, from its squad list, the player's matchday appearances and career dates. */
internal fun fotmobCallUp(
    player: PlayerData,
    team: TeamData,
    spellEnded: String?,
    appearedVs: List<String>,
    window: BreakWindow,
): SourceCheck {
    val inSquad = team.squad.any { it.id == player.id }
    val ended = spellEnded != null && parseMillis(spellEnded) >= parseMillis(window.from) - LEFT_SQUAD_GRACE_MS
    val url = "https://www.fotmob.com/teams/${team.id}/squad"

    return when {
        ended && (inSquad || appearedVs.isNotEmpty()) ->
            SourceCheck("FotMob", CallUpStatus.WITHDRAWN, "Left the ${team.name} squad on ${fmtDate(spellEnded!!)}", url)
        inSquad || appearedVs.isNotEmpty() -> {
            val how = if (appearedVs.isNotEmpty()) "in the matchday squad vs ${appearedVs.joinToString(", ")}" else "listed in the ${team.name} squad"
            SourceCheck("FotMob", CallUpStatus.CALLED, "Called up — $how", url)
        }
        else -> SourceCheck("FotMob", CallUpStatus.NOT_CALLED, "Not in the current ${team.name} squad", url)
    }
}

internal fun combineChecks(checks: List<SourceCheck>): CallUp {
    val known = checks.filter { it.status != CallUpStatus.UNKNOWN }
    if (known.isEmpty()) return CallUp(CallUpStatus.UNKNOWN, checks, "single")
    val agree = known.all { it.status == known[0].status }
    // Until more sources are in, FotMob's is the headline answer; conflicts are flagged, not hidden.
    return CallUp(known[0].status, checks, if (known.size == 1) "single" else if (agree) "agree" else "conflict")
}
