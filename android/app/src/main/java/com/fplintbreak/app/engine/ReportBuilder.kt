package com.fplintbreak.app.engine

import com.fplintbreak.app.data.BreakMatch
import com.fplintbreak.app.data.BreakWindow
import com.fplintbreak.app.data.CallUp
import com.fplintbreak.app.data.CallUpStatus
import com.fplintbreak.app.data.DataSource
import com.fplintbreak.app.data.FotmobLink
import com.fplintbreak.app.data.IntlMatch
import com.fplintbreak.app.data.Involvement
import com.fplintbreak.app.data.Nation
import com.fplintbreak.app.data.PlayerCandidate
import com.fplintbreak.app.data.PlayerReport
import com.fplintbreak.app.data.Risk
import com.fplintbreak.app.data.Severity
import com.fplintbreak.app.data.Signal
import com.fplintbreak.app.data.TeamInfo
import com.fplintbreak.app.data.TeamReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class ReportException(message: String) : Exception(message)

private val dateFormat = DateTimeFormatter.ofPattern("d MMM", Locale.UK).withZone(ZoneOffset.UTC)
internal fun fmtDate(iso: String): String = dateFormat.format(parseInstant(iso))

// A starter replaced before this minute without an injury flag is still worth a look.
private const val EARLY_SUB_MINUTE = 60

private val FPL_STATUS = mapOf(
    "a" to "Available",
    "d" to "Doubtful",
    "i" to "Injured",
    "s" to "Suspended",
    "u" to "Unavailable",
    "n" to "Not in squad",
)

private val SOURCES = listOf(
    DataSource(
        id = "fpl",
        name = "Fantasy Premier League",
        url = "https://fantasy.premierleague.com",
        usedFor = listOf("Your squad and captaincy", "Gameweek dates (break window)", "Injury news and chance of playing"),
        status = "live",
    ),
    DataSource(
        id = "fotmob",
        name = "FotMob",
        url = "https://www.fotmob.com",
        usedFor = listOf(
            "National team squads (call-ups, withdrawals)",
            "Matches played: minutes, subs, goals, cards, ratings",
            "Remaining national-team fixtures",
            "Injury reports",
        ),
        status = "live",
    ),
    DataSource(
        id = "transfermarkt",
        name = "Transfermarkt",
        url = "https://www.transfermarkt.com",
        usedFor = listOf("Independent check of call-ups: current national-team squads, with withdrawn players removed"),
        status = "live",
    ),
)

/**
 * Builds the break report on the phone, straight from FPL and FotMob.
 * Port of backend/src/analyze.ts — keep the two in step.
 */
class ReportBuilder(cacheDir: File, private val overrides: Overrides) {
    private val http = Http()
    private val cache = DiskCache(File(cacheDir, "api"))

    /** FotMob player search, for picking the right player by hand. */
    suspend fun searchPlayers(term: String): List<PlayerCandidate> = try {
        FotMob(Fetcher(http, cache, DataAges())).searchPlayers(term.trim())
            .map { PlayerCandidate(it.id.toLong(), it.name, it.teamName) }
    } catch (e: IOException) {
        throw ReportException("Search failed. Check your connection and try again.")
    }

    suspend fun build(teamId: Int): TeamReport {
        val ages = DataAges()
        val f = Fetcher(http, cache, ages)
        val report = try {
            buildData(teamId, Fpl(f), FotMob(f), Transfermarkt(f))
        } catch (e: HttpException) {
            throw ReportException(if (e.status == 404) "FPL team $teamId not found" else "A data source failed (${e.status}). Try again in a bit.")
        } catch (e: IOException) {
            throw ReportException("No connection. Check your internet and try again.")
        }
        val sources = SOURCES.map { s -> s.copy(oldestDataAt = ages[s.id]?.let { Instant.ofEpochMilli(it).toString() }) }
        return report.copy(sources = sources)
    }

    private suspend fun buildData(teamId: Int, fpl: Fpl, fotmob: FotMob, tm: Transfermarkt): TeamReport = coroutineScope {
        val bootstrapJob = async { fpl.bootstrap() }
        val entryJob = async { fpl.entry(teamId) }
        val window = fpl.findBreakWindow() ?: throw ReportException("No international break found yet this season.")
        val bootstrap = bootstrapJob.await()
        val entry = entryJob.await()

        // Picks from the gameweek before the break: the squad as it went into the break.
        val picksEvent = minOf(window.afterEvent, entry.currentEvent)
        val picks = fpl.picks(teamId, picksEvent)

        val elements = bootstrap.elements.associateBy { it.id }
        val teams = bootstrap.teams.associateBy { it.id }
        val positions = bootstrap.elementTypes.associate { it.id to it.singularNameShort }
        val mapping = Mapping(cache, fotmob, overrides)

        val players = picks.picks.map { pick ->
            async {
                val el = elements.getValue(pick.element)
                val club = teams.getValue(el.team)
                playerReport(pick, el, club, positions[el.elementType].orEmpty(), window, fotmob, tm, mapping)
            }
        }.awaitAll().sortedBy { it.pickPosition } // FPL squad order: XI from GK forwards, then bench

        TeamReport(
            team = TeamInfo(entry.id, entry.name, "${entry.playerFirstName} ${entry.playerLastName}", picksEvent),
            window = window,
            generatedAt = Instant.now().toString(),
            players = players,
        )
    }

    private suspend fun playerReport(
        pick: FplPick,
        el: FplElement,
        club: FplTeam,
        position: String,
        window: BreakWindow,
        fotmob: FotMob,
        tm: Transfermarkt,
        mapping: Mapping,
    ): PlayerReport {
        val w = Working()
        try {
            w.mapping = mapping.mapPlayer(el, club)
            val fotmobId = w.mapping.fotmobId
            if (fotmobId != null) {
                val player = fotmob.player(fotmobId)
                w.matches = internationalMatches(fotmob, player, window)
                w.nationalTeam = w.matches.firstOrNull()?.team
                val (nation, spellEnded) = resolveNation(fotmob, player)
                if (nation != null) {
                    val team = fotmob.team(nation.fotmobTeamId)
                    w.nation = nation
                    w.nationalTeam = nation.name
                    w.fixtures = breakFixtures(team, window).map { withInvolvement(it, w.matches) }
                    val appearedVs = w.matches.filter { it.team == team.name && (it.played || it.onBench) }.map { it.opponent }
                    w.callUp = combineChecks(
                        listOf(
                            fotmobCallUp(player, team, spellEnded, appearedVs, window),
                            tm.callUp(nation.name, el, club, w.mapping.fotmobName ?: player.name),
                        ),
                    )
                }
                w.injury = player.injuryInformation
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // One player's data failing shouldn't sink the whole report.
            w.error = e.message ?: e.javaClass.simpleName
        }

        val (signals, confirmedByBoth) = assess(w, el, window)
        val risk = when {
            signals.any { it.severity == Severity.HIGH } -> Risk.RED
            signals.any { it.severity == Severity.MEDIUM } -> Risk.AMBER
            else -> Risk.GREEN
        }
        return PlayerReport(
            fplId = el.id,
            pickPosition = pick.position,
            name = "${el.firstName} ${el.secondName}",
            webName = el.webName,
            club = club.name,
            position = position,
            squadRole = if (pick.position <= 11) "starter" else "bench",
            isCaptain = pick.isCaptain,
            isViceCaptain = pick.isViceCaptain,
            fotmob = FotmobLink(w.mapping.fotmobId, w.mapping.fotmobName, w.mapping.confidence),
            nationalTeam = w.nationalTeam,
            nation = w.nation,
            callUp = w.callUp,
            breakFixtures = w.fixtures,
            matches = w.matches,
            signals = signals,
            risk = risk,
            confirmedByBoth = confirmedByBoth,
        )
    }

    /** Mutable scratch state while a player's report is assembled. */
    private class Working {
        var mapping = PlayerMapping()
        var matches: List<IntlMatch> = emptyList()
        var nationalTeam: String? = null
        var nation: Nation? = null
        var fixtures: List<BreakMatch> = emptyList()
        var callUp = CallUp(CallUpStatus.UNKNOWN, emptyList(), "single")
        var injury: InjuryInformation? = null
        var error: String? = null
    }

    private fun withInvolvement(f: BreakFixture, matches: List<IntlMatch>): BreakMatch {
        val m = matches.find { it.fotmobMatchId == f.fotmobMatchId }
        val involvement = when {
            !f.finished -> Involvement.UPCOMING
            m == null -> Involvement.ABSENT
            m.played -> if (m.started) Involvement.STARTED else Involvement.SUB
            m.onBench -> Involvement.BENCH
            else -> Involvement.ABSENT
        }
        return BreakMatch(
            fotmobMatchId = f.fotmobMatchId,
            date = f.date,
            opponent = f.opponent,
            home = f.home,
            competition = f.competition,
            finished = f.finished,
            score = f.score,
            involvement = involvement,
            minutes = if (m?.played == true) m.minutes else null,
        )
    }

    private suspend fun internationalMatches(fotmob: FotMob, player: PlayerData, window: BreakWindow): List<IntlMatch> =
        coroutineScope {
            val from = parseMillis(window.from)
            val to = parseMillis(window.to)
            val clubId = player.primaryTeam?.teamId
            player.recentMatches
                .filter { parseMillis(it.matchDate.utcTime) in from until to && it.teamId != clubId }
                .map { m ->
                    async {
                        val sub = if (m.playedInMatch) substitutionInfo(fotmob.match(m.id), player.id) else SubstitutionInfo()
                        val (home, away) = if (m.isHomeTeam) m.teamName to m.opponentTeamName else m.opponentTeamName to m.teamName
                        IntlMatch(
                            fotmobMatchId = m.id,
                            date = m.matchDate.utcTime,
                            competition = m.leagueName,
                            team = m.teamName,
                            opponent = m.opponentTeamName,
                            home = m.isHomeTeam,
                            score = "$home ${m.homeScore}–${m.awayScore} $away",
                            played = m.playedInMatch,
                            started = m.playedInMatch && sub.subbedOnMinute == null,
                            onBench = m.onBench,
                            minutes = m.minutesPlayed,
                            goals = m.goals,
                            assists = m.assists,
                            yellowCards = m.yellowCards,
                            redCards = m.redCards,
                            rating = m.ratingProps?.value,
                            subbedOnMinute = sub.subbedOnMinute,
                            subbedOffMinute = sub.subbedOffMinute,
                            offReason = sub.offReason,
                            injuredOff = sub.injuredOff,
                        )
                    }
                }
                .awaitAll()
                .sortedBy { parseMillis(it.date) }
        }

    private fun assess(w: Working, el: FplElement, window: BreakWindow): Pair<List<Signal>, Boolean> {
        val signals = mutableListOf<Signal>()
        val from = parseMillis(window.from)
        var fotmobInjuryFlag = false

        for (m in w.matches) {
            val vs = "vs ${m.opponent} (${fmtDate(m.date)})"
            if (m.injuredOff) {
                fotmobInjuryFlag = true
                signals += Signal("fotmob", Severity.HIGH, "Came off injured at ${m.subbedOffMinute ?: "?"}' $vs")
            } else if (m.started && m.subbedOffMinute != null && m.subbedOffMinute < EARLY_SUB_MINUTE) {
                val why = m.offReason?.let { " (reason: $it)" } ?: " (no injury flag)"
                signals += Signal("fotmob", Severity.MEDIUM, "Subbed off early at ${m.subbedOffMinute}' $vs$why")
            }
            if (m.redCards > 0) signals += Signal("fotmob", Severity.INFO, "Sent off $vs")
        }

        w.injury?.let { inj ->
            val updated = inj.lastUpdated?.utcTime
            val during = updated != null && parseMillis(updated) >= from - DAY // FotMob dates are day-precision
            val back = inj.expectedReturn?.expectedReturnFallback?.let { ", expected back $it" }.orEmpty()
            if (during) fotmobInjuryFlag = true
            signals += Signal(
                "fotmob",
                if (during) Severity.HIGH else Severity.MEDIUM,
                "${if (during) "Injury reported during the break" else "Existing injury"}: ${inj.name}$back",
            )
        }

        var fplFlag = false
        if (el.status != "a") {
            val newDuringBreak = el.newsAdded != null && parseMillis(el.newsAdded) >= from
            val serious = el.status in setOf("i", "s", "u") || (el.chanceOfPlayingNextRound ?: 100) <= 50
            fplFlag = true
            signals += Signal(
                "fpl",
                if (serious || newDuringBreak) Severity.HIGH else Severity.MEDIUM,
                "${FPL_STATUS[el.status] ?: el.status}${if (newDuringBreak) " (updated during break)" else ""}: ${el.news.ifEmpty { "no details" }}",
            )
        }

        if (w.callUp.status == CallUpStatus.WITHDRAWN) {
            val why = w.callUp.checks.find { it.status == CallUpStatus.WITHDRAWN }?.detail ?: "Withdrew from the squad"
            signals += Signal("fotmob", Severity.MEDIUM, why)
        }
        if (w.callUp.agreement == "conflict") {
            val tmDetail = w.callUp.checks.find { it.source == "Transfermarkt" }?.detail
                ?.replace("Transfermarkt's ", "")
            val why = tmDetail?.let { " (Transfermarkt: $it)" }.orEmpty()
            signals += Signal("fotmob", Severity.MEDIUM, "FotMob and Transfermarkt disagree on the call-up$why — check the latest squad news")
        }

        if (fotmobInjuryFlag && !fplFlag) {
            signals += Signal("fpl", Severity.INFO, "FPL still lists the player as available — FPL often updates a day or two later")
        }
        when {
            w.mapping.fotmobId == null ->
                signals += Signal("fotmob", Severity.INFO, "Couldn't find this player on FotMob — use \"Find on FotMob\" below")
            w.mapping.confidence == "low" ->
                signals += Signal("fotmob", Severity.INFO, "FotMob match is a guess (${w.mapping.fotmobName}) — check it's the right player below")
        }
        w.error?.let { signals += Signal("fotmob", Severity.INFO, "FotMob data unavailable: $it") }

        return signals to (fotmobInjuryFlag && fplFlag)
    }
}
