package com.fplintbreak.app.engine

import com.fplintbreak.app.data.BreakWindow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

// Fantasy Premier League's public API. Port of backend/src/fpl.ts.

@Serializable
internal data class FplElement(
    val id: Int,
    val code: Int = 0, // stable across seasons, unlike id
    @SerialName("first_name") val firstName: String,
    @SerialName("second_name") val secondName: String,
    @SerialName("web_name") val webName: String,
    @SerialName("known_name") val knownName: String? = null,
    val team: Int,
    @SerialName("element_type") val elementType: Int,
    val status: String, // a=available, d=doubtful, i=injured, s=suspended, u=unavailable, n=not in squad
    val news: String = "",
    @SerialName("news_added") val newsAdded: String? = null,
    @SerialName("chance_of_playing_next_round") val chanceOfPlayingNextRound: Int? = null,
    @SerialName("now_cost") val nowCost: Int = 0, // tenths of £m
    @SerialName("cost_change_event") val costChangeEvent: Int = 0,
    @SerialName("transfers_in_event") val transfersInEvent: Int = 0,
    @SerialName("transfers_out_event") val transfersOutEvent: Int = 0,
)

@Serializable
internal data class FplTeam(
    val id: Int,
    val name: String,
    @SerialName("short_name") val shortName: String,
    val code: Int = 0, // stable across seasons, unlike id
)

@Serializable
internal data class FplEvent(val id: Int, @SerialName("deadline_time") val deadlineTime: String, val finished: Boolean = false)

@Serializable
internal data class ElementType(val id: Int, @SerialName("singular_name_short") val singularNameShort: String)

@Serializable
internal data class Bootstrap(
    val elements: List<FplElement>,
    val teams: List<FplTeam>,
    @SerialName("element_types") val elementTypes: List<ElementType>,
    val events: List<FplEvent> = emptyList(),
)

@Serializable
internal data class FplFixture(
    val id: Int = 0,
    val event: Int? = null,
    @SerialName("kickoff_time") val kickoffTime: String? = null,
    @SerialName("team_h") val teamH: Int = 0,
    @SerialName("team_a") val teamA: Int = 0,
    @SerialName("team_h_difficulty") val teamHDifficulty: Int = 3,
    @SerialName("team_a_difficulty") val teamADifficulty: Int = 3,
    val finished: Boolean = false,
    @SerialName("team_h_score") val teamHScore: Int? = null,
    @SerialName("team_a_score") val teamAScore: Int? = null,
    val stats: List<FixtureStat> = emptyList(),
)

@Serializable
internal data class FixtureStat(val identifier: String, val h: List<StatCredit> = emptyList(), val a: List<StatCredit> = emptyList())

@Serializable
internal data class StatCredit(val element: Int, val value: Int)

@Serializable
internal data class FplEntry(
    val id: Int,
    val name: String,
    @SerialName("player_first_name") val playerFirstName: String,
    @SerialName("player_last_name") val playerLastName: String,
    @SerialName("current_event") val currentEvent: Int,
    val leagues: EntryLeagues? = null,
)

@Serializable
internal data class EntryLeagues(val classic: List<EntryLeague> = emptyList())

@Serializable
internal data class EntryLeague(
    val id: Int,
    val name: String,
    @SerialName("entry_rank") val entryRank: Int? = null,
    @SerialName("league_type") val leagueType: String = "",
)

@Serializable
internal data class LeagueStandings(val league: LeagueInfo, val standings: StandingsPage)

@Serializable
internal data class LeagueInfo(val id: Int, val name: String)

@Serializable
internal data class StandingsPage(val results: List<Standing> = emptyList())

@Serializable
internal data class Standing(
    val entry: Int,
    @SerialName("entry_name") val entryName: String,
    @SerialName("player_name") val playerName: String,
    val rank: Int,
    val total: Int,
)

@Serializable
internal data class FplPick(
    val element: Int,
    val position: Int,
    @SerialName("is_captain") val isCaptain: Boolean,
    @SerialName("is_vice_captain") val isViceCaptain: Boolean,
)

@Serializable
internal data class FplPicks(val picks: List<FplPick>)

internal class Fpl(private val f: Fetcher) {
    // Player status/news changes a lot during a break, so keep this short.
    suspend fun bootstrap(): Bootstrap =
        lenientJson.decodeFromString(f.cached("fpl_bootstrap", 15 * MINUTE, "$BASE/bootstrap-static/"))

    suspend fun fixtures(): List<FplFixture> =
        lenientJson.decodeFromString(f.cached("fpl_fixtures", 6 * HOUR, "$BASE/fixtures/"))

    suspend fun entry(id: Int): FplEntry =
        lenientJson.decodeFromString(f.cached("fpl_entry_$id", 15 * MINUTE, "$BASE/entry/$id/"))

    suspend fun picks(id: Int, event: Int): FplPicks =
        lenientJson.decodeFromString(f.cached("fpl_picks_${id}_$event", 15 * MINUTE, "$BASE/entry/$id/event/$event/picks/"))

    // A player's match-by-match history this season; changes after every match they play.
    suspend fun elementSummary(id: Int): String = f.cached("fpl_summary_$id", HOUR, "$BASE/element-summary/$id/")

    // Rivals' ranks only move at gameweek end, so an hour is plenty.
    suspend fun league(id: Int): LeagueStandings =
        lenientJson.decodeFromString(f.cached("fpl_league_$id", HOUR, "$BASE/leagues-classic/$id/standings/"))

    /**
     * The most recent gap of 9+ days between consecutive gameweeks that has already
     * started. Normal weeks have ~3–7 day gaps.
     */
    suspend fun findBreakWindow(now: Long = System.currentTimeMillis()): BreakWindow? {
        val byEvent = fixtures()
            .filter { it.event != null && it.kickoffTime != null }
            .groupBy({ it.event!! }, { parseMillis(it.kickoffTime!!) })
        val events = byEvent.keys.sorted()
        var found: BreakWindow? = null
        for (i in 0 until events.size - 1) {
            val lastKickoff = byEvent.getValue(events[i]).max()
            val nextKickoff = byEvent.getValue(events[i + 1]).min()
            val from = lastKickoff + MATCH_LENGTH_MS
            if (from > now) break
            if (nextKickoff - lastKickoff >= MIN_BREAK_DAYS * DAY) {
                found = BreakWindow(
                    from = Instant.ofEpochMilli(from).toString(),
                    to = Instant.ofEpochMilli(nextKickoff).toString(),
                    afterEvent = events[i],
                    beforeEvent = events[i + 1],
                )
            }
        }
        return found
    }

    private companion object {
        const val BASE = "https://fantasy.premierleague.com/api"
        const val MIN_BREAK_DAYS = 9
        const val MATCH_LENGTH_MS = 150 * MINUTE
    }
}
