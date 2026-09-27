package com.fplintbreak.app.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder

// FotMob's public web endpoints. Unofficial and undocumented: if FotMob changes them,
// this is the only file that should need fixing. Port of backend/src/fotmob.ts.

@Serializable
internal data class Suggestion(
    val type: String = "",
    val id: String = "",
    val name: String = "",
    val teamId: Int? = null,
    val teamName: String? = null,
)

@Serializable
internal data class SuggestionGroup(val suggestions: List<Suggestion> = emptyList())

@Serializable
internal data class UtcTime(val utcTime: String)

@Serializable
internal data class RatingProps(val rating: JsonElement? = null) {
    /** FotMob sends a string like "7.9", or the number 0 when the player wasn't rated. */
    val value: String?
        get() = (rating as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() && it.toDoubleOrNull() != 0.0 }
}

@Serializable
internal data class RecentMatch(
    val id: Long,
    val teamId: Int,
    val teamName: String,
    val opponentTeamName: String,
    val isHomeTeam: Boolean,
    val matchDate: UtcTime,
    val leagueName: String = "",
    val homeScore: Int = 0,
    val awayScore: Int = 0,
    val minutesPlayed: Int = 0,
    val goals: Int = 0,
    val assists: Int = 0,
    val yellowCards: Int = 0,
    val redCards: Int = 0,
    val ratingProps: RatingProps? = null,
    val onBench: Boolean = false,
    val playedInMatch: Boolean = false,
)

@Serializable
internal data class ExpectedReturn(val expectedReturnFallback: String? = null)

@Serializable
internal data class InjuryInformation(
    val name: String,
    val expectedReturn: ExpectedReturn? = null,
    val lastUpdated: UtcTime? = null,
)

@Serializable
internal data class InfoValue(val fallback: JsonElement? = null)

@Serializable
internal data class PlayerInfo(val title: String = "", val value: InfoValue = InfoValue())

@Serializable
internal data class CareerEntry(val teamId: Int, val team: String, val startDate: String? = null, val endDate: String? = null)

@Serializable
internal data class CareerGroup(val teamEntries: List<CareerEntry> = emptyList())

@Serializable
internal data class CareerHistory(val careerItems: Map<String, CareerGroup> = emptyMap())

@Serializable
internal data class PrimaryTeam(val teamId: Int, val teamName: String)

@Serializable
internal data class PlayerData(
    val id: Long,
    val name: String,
    val primaryTeam: PrimaryTeam? = null,
    val injuryInformation: InjuryInformation? = null,
    val recentMatches: List<RecentMatch> = emptyList(),
    val playerInformation: List<PlayerInfo> = emptyList(),
    val careerHistory: CareerHistory? = null,
)

@Serializable
internal data class SwapPlayer(val id: String = "")

@Serializable
internal data class MatchEvent(
    val type: String = "",
    val time: Int? = null,
    val injuredPlayerOut: Boolean = false,
    val swap: List<SwapPlayer>? = null, // [player on, player off]
)

@Serializable
internal data class MatchEvents(val events: List<MatchEvent> = emptyList())

@Serializable
internal data class MatchFacts(val events: MatchEvents? = null)

@Serializable
internal data class SubstitutionEvent(val time: Int? = null, val type: String = "", val reason: String? = null)

@Serializable
internal data class Performance(val substitutionEvents: List<SubstitutionEvent> = emptyList())

@Serializable
internal data class LineupPlayer(val id: Long, val performance: Performance? = null)

@Serializable
internal data class LineupTeam(val starters: List<LineupPlayer> = emptyList(), val subs: List<LineupPlayer> = emptyList())

@Serializable
internal data class Lineup(val homeTeam: LineupTeam? = null, val awayTeam: LineupTeam? = null)

@Serializable
internal data class MatchContent(val matchFacts: MatchFacts? = null, val lineup: Lineup? = null)

@Serializable
internal data class MatchGeneral(val finished: Boolean = false)

@Serializable
internal data class MatchDetails(val general: MatchGeneral = MatchGeneral(), val content: MatchContent = MatchContent())

@Serializable
internal data class FixtureSide(val id: Int, val name: String, val score: Int? = null)

@Serializable
internal data class Tournament(val name: String = "")

@Serializable
internal data class FixtureStatus(
    val utcTime: String,
    val finished: Boolean = false,
    val started: Boolean = false,
    val cancelled: Boolean = false,
)

@Serializable
internal data class TeamFixture(
    val id: Long,
    val home: FixtureSide,
    val away: FixtureSide,
    val tournament: Tournament? = null,
    val status: FixtureStatus,
)

@Serializable
internal data class SquadMember(val id: Long, val name: String = "")

@Serializable
internal data class SquadGroup(val title: String = "", val members: List<SquadMember> = emptyList())

@Serializable
private data class SquadWrap(val squad: List<SquadGroup> = emptyList())

@Serializable
private data class AllFixtures(val fixtures: List<TeamFixture> = emptyList())

@Serializable
private data class FixturesWrap(val allFixtures: AllFixtures? = null)

@Serializable
private data class TeamDetails(val id: Int, val name: String)

@Serializable
private data class TeamRaw(val details: TeamDetails, val squad: SquadWrap? = null, val fixtures: FixturesWrap? = null)

/** The slice of FotMob's team page we use; the full payload is ~300 KB. */
@Serializable
internal data class TeamData(val id: Int, val name: String, val squad: List<SquadMember>, val fixtures: List<TeamFixture>)

internal data class SubstitutionInfo(
    val subbedOnMinute: Int? = null,
    val subbedOffMinute: Int? = null,
    val injuredOff: Boolean = false,
    val offReason: String? = null,
)

internal class FotMob(private val f: Fetcher) {
    private suspend fun suggest(term: String, type: String): List<Suggestion> {
        val url = "$BASE/search/suggest?term=${URLEncoder.encode(term, "UTF-8")}&lang=en"
        val groups: List<SuggestionGroup> = lenientJson.decodeFromString(f.cached("fotmob_search_$term", 7 * DAY, url))
        return groups.flatMap { it.suggestions }.filter { it.type == type }.distinctBy { it.id }
    }

    suspend fun searchPlayers(term: String) = suggest(term, "player")

    suspend fun searchTeams(term: String) = suggest(term, "team")

    suspend fun player(id: Long): PlayerData =
        lenientJson.decodeFromString(f.cached("fotmob_player_$id", 20 * MINUTE, "$BASE/playerData?id=$id"))

    /** Finished matches never change, so cache them forever. */
    suspend fun match(id: Long): MatchDetails {
        val body = f.cached(
            "fotmob_match_$id",
            { b: String -> if (lenientJson.decodeFromString<MatchDetails>(b).general.finished) null else 10 * MINUTE },
            "$BASE/matchDetails?matchId=$id",
        )
        return lenientJson.decodeFromString(body)
    }

    /** National squads change when players withdraw, so re-check hourly. Stored slimmed down. */
    suspend fun team(id: Int): TeamData {
        val key = "fotmob_team_$id"
        f.cache.read(key, f.ages)?.let { return lenientJson.decodeFromString(it) }
        val raw: TeamRaw = lenientJson.decodeFromString(f.http.getText("$BASE/teams?id=$id"))
        val team = TeamData(
            id = raw.details.id,
            name = raw.details.name,
            squad = raw.squad?.squad.orEmpty().filter { it.title != "coach" }.flatMap { it.members },
            fixtures = raw.fixtures?.allFixtures?.fixtures.orEmpty(),
        )
        f.cache.write(key, lenientJson.encodeToString(TeamData.serializer(), team), HOUR, f.ages)
        return team
    }

    private companion object {
        const val BASE = "https://www.fotmob.com/api/data"
    }
}

/** How a player's match ended, from match events plus lineup substitution reasons. */
internal fun substitutionInfo(match: MatchDetails, playerId: Long): SubstitutionInfo {
    var on: Int? = null
    var off: Int? = null
    var injured = false
    val pid = playerId.toString()

    for (e in match.content.matchFacts?.events?.events.orEmpty()) {
        if (e.type != "Substitution" || e.swap == null) continue
        if (e.swap.getOrNull(0)?.id == pid) on = e.time
        if (e.swap.getOrNull(1)?.id == pid) {
            off = e.time
            if (e.injuredPlayerOut) injured = true
        }
    }

    val lineup = match.content.lineup
    val me = listOfNotNull(lineup?.homeTeam, lineup?.awayTeam)
        .flatMap { it.starters + it.subs }
        .find { it.id == playerId }
    val subOut = me?.performance?.substitutionEvents?.find { it.type == "subOut" }
    var reason: String? = null
    if (subOut != null) {
        off = off ?: subOut.time
        reason = subOut.reason
        if (reason != null && Regex("injur", RegexOption.IGNORE_CASE).containsMatchIn(reason)) injured = true
    }
    return SubstitutionInfo(on, off, injured, reason)
}
