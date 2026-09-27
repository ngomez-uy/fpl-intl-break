package com.fplintbreak.app.engine

import com.fplintbreak.app.data.NextFixture
import com.fplintbreak.app.data.Price
import com.fplintbreak.app.data.Rest
import com.fplintbreak.app.data.RestLevel
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// Getting back to the club: the next gameweek's fixture(s), price movement, and how much rest
// a player gets between their last international match and their club's kick-off.
// Port of backend/src/club.ts.

private const val MATCH_MS = 2 * HOUR

// Confederations whose matches mean a long flight back to England.
private val LONG_TRIP = listOf(
    Regex("africa|\\bcaf\\b|afcon", RegexOption.IGNORE_CASE) to "Africa",
    Regex("conmebol|south america|copa am", RegexOption.IGNORE_CASE) to "South America",
    Regex("concacaf|gold cup|north.*america|central america", RegexOption.IGNORE_CASE) to "North & Central America",
    Regex("\\bafc\\b|asia", RegexOption.IGNORE_CASE) to "Asia",
    Regex("\\bofc\\b|oceania", RegexOption.IGNORE_CASE) to "Oceania",
)

private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK).withZone(ZoneOffset.UTC)

internal fun nextFixtures(fixtures: List<FplFixture>, club: FplTeam, event: Int, teams: Map<Int, FplTeam>): List<NextFixture> =
    fixtures
        .filter { it.event == event && it.kickoffTime != null && (it.teamH == club.id || it.teamA == club.id) }
        .map { f ->
            val home = f.teamH == club.id
            NextFixture(
                opponent = teams[if (home) f.teamA else f.teamH]?.shortName ?: "?",
                home = home,
                kickoff = f.kickoffTime!!,
                difficulty = if (home) f.teamHDifficulty else f.teamADifficulty,
            )
        }
        .sortedBy { parseMillis(it.kickoff) }

internal fun price(el: FplElement) =
    Price(el.nowCost / 10.0, el.costChangeEvent / 10.0, el.transfersInEvent - el.transfersOutEvent)

internal data class RestFixture(val date: String, val finished: Boolean, val involved: Boolean, val competition: String)

internal fun rest(withSquad: Boolean, fixtures: List<RestFixture>, breakMinutes: Int, clubKickoff: String?): Rest {
    // Still with the squad: assume they're in every remaining match. Otherwise only count
    // matches they actually featured in (e.g. played, then withdrew).
    val relevant = fixtures.filter { (withSquad && !it.finished) || it.involved }
    val last = relevant.maxByOrNull { parseMillis(it.date) }?.date
    val longTrip = LONG_TRIP.firstOrNull { (re, _) -> relevant.any { re.containsMatchIn(it.competition) } }?.second

    if (last == null || clubKickoff == null) return Rest(RestLevel.NA, last, clubKickoff, null, breakMinutes, null, "")

    val days = ((parseMillis(clubKickoff) - parseMillis(last) - MATCH_MS).toDouble() / DAY * 2).roundToInt() / 2.0
    val level = when {
        days < 3 || (longTrip != null && days < 4) -> RestLevel.TIGHT
        days < 4 || breakMinutes >= 270 || (longTrip != null && days < 5) -> RestLevel.WATCH
        else -> RestLevel.OK
    }
    val shown = if (days % 1.0 == 0.0) days.toInt().toString() else days.toString()
    val trip = longTrip?.let { ", flying back from $it" }.orEmpty()
    val heavy = if (breakMinutes >= 270) " after $breakMinutes' this break" else ""
    val note = "Last international ${dayFormat.format(parseInstant(last))}, club game " +
        "${dayFormat.format(parseInstant(clubKickoff))}: $shown days' rest$trip$heavy"
    return Rest(level, last, clubKickoff, days, breakMinutes, longTrip, note)
}
