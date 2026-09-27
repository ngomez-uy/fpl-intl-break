package com.fplintbreak.app.engine

import com.fplintbreak.app.data.BreakWindow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

// Fantasy Premier League's public API. Port of backend/src/fpl.ts.

@Serializable
internal data class FplElement(
    val id: Int,
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
)

@Serializable
internal data class FplTeam(val id: Int, val name: String, @SerialName("short_name") val shortName: String)

@Serializable
internal data class ElementType(val id: Int, @SerialName("singular_name_short") val singularNameShort: String)

@Serializable
internal data class Bootstrap(
    val elements: List<FplElement>,
    val teams: List<FplTeam>,
    @SerialName("element_types") val elementTypes: List<ElementType>,
)

@Serializable
internal data class FplFixture(val event: Int? = null, @SerialName("kickoff_time") val kickoffTime: String? = null)

@Serializable
internal data class FplEntry(
    val id: Int,
    val name: String,
    @SerialName("player_first_name") val playerFirstName: String,
    @SerialName("player_last_name") val playerLastName: String,
    @SerialName("current_event") val currentEvent: Int,
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

    private suspend fun fixtures(): List<FplFixture> =
        lenientJson.decodeFromString(f.cached("fpl_fixtures", 6 * HOUR, "$BASE/fixtures/"))

    suspend fun entry(id: Int): FplEntry =
        lenientJson.decodeFromString(f.cached("fpl_entry_$id", 15 * MINUTE, "$BASE/entry/$id/"))

    suspend fun picks(id: Int, event: Int): FplPicks =
        lenientJson.decodeFromString(f.cached("fpl_picks_${id}_$event", 15 * MINUTE, "$BASE/entry/$id/event/$event/picks/"))

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
