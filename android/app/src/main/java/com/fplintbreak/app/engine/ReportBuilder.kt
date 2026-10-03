package com.fplintbreak.app.engine

import com.fplintbreak.app.data.BreakMatch
import com.fplintbreak.app.data.BreakWindow
import com.fplintbreak.app.data.CallUp
import com.fplintbreak.app.data.CallUpStatus
import com.fplintbreak.app.data.DataSource
import com.fplintbreak.app.data.FotmobLink
import com.fplintbreak.app.data.IntlMatch
import com.fplintbreak.app.data.Involvement
import com.fplintbreak.app.data.FlaggedPlayer
import com.fplintbreak.app.data.H2HReport
import com.fplintbreak.app.data.LeagueOption
import com.fplintbreak.app.data.LeagueReport
import com.fplintbreak.app.data.LiveMatch
import com.fplintbreak.app.data.RestLevel
import com.fplintbreak.app.data.RivalImpact
import com.fplintbreak.app.data.Nation
import com.fplintbreak.app.data.NextFixture
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.roundToInt
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

    /** The user's private mini-leagues. Public ones (Overall, country, club) are far too big to compare. */
    suspend fun teamLeagues(teamId: Int): List<LeagueOption> = wrapErrors(teamId) {
        Fpl(Fetcher(http, cache, DataAges())).entry(teamId).leagues?.classic.orEmpty()
            .filter { it.leagueType == "x" }
            .map { LeagueOption(it.id, it.name) }
    }

    /** Squad players' Premier League record against their next opponent. */
    suspend fun headToHead(teamId: Int): H2HReport = wrapErrors(teamId) {
        val f = Fetcher(http, cache, DataAges())
        HeadToHead(Fpl(f), f).build(teamId)
    }

    /** Mini-league break impact: which rivals the break hit hardest. Port of backend/src/league.ts. */
    suspend fun leagueReport(leagueId: Int, you: Int, limit: Int): LeagueReport {
        val standings = wrapErrors(null) { Fpl(Fetcher(http, cache, DataAges())).league(leagueId) }
        val picked = standings.standings.results.take(limit).toMutableList()
        standings.standings.results.find { it.entry == you }?.let { if (it !in picked) picked += it }

        // Rivals share many players, so later reports mostly hit the cache. Keep bursts small anyway.
        val permits = Semaphore(3)
        val rivals = coroutineScope {
            picked.map { r ->
                async {
                    permits.withPermit {
                        try {
                            summarise(r, you, build(r.entry))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            RivalImpact(r.entry, r.entryName, r.playerName, r.rank, r.entry == you, 0.0, 0, 0, 0, 0, 0, emptyList(), e.message)
                        }
                    }
                }
            }.awaitAll()
        }.sortedWith(compareByDescending<RivalImpact> { it.score }.thenBy { it.rank })
        return LeagueReport(standings.league.name, Instant.now().toString(), rivals, limit)
    }

    private fun summarise(r: Standing, you: Int, report: TeamReport): RivalImpact {
        val ps = report.players
        val score = ps.sumOf { damage(it) }
        return RivalImpact(
            entry = r.entry,
            teamName = report.team.name,
            manager = report.team.manager,
            rank = r.rank,
            isYou = r.entry == you,
            score = (score * 10).roundToInt() / 10.0,
            injured = ps.count { it.risk == Risk.RED },
            watch = ps.count { it.risk == Risk.AMBER },
            withdrew = ps.count { it.callUp.status == CallUpStatus.WITHDRAWN },
            tightRest = ps.count { it.rest.level == RestLevel.TIGHT },
            away = ps.count { it.callUp.status == CallUpStatus.CALLED },
            flagged = ps.mapNotNull { p -> flagReason(p)?.let { FlaggedPlayer(p.webName, it, p.isBench) } },
        )
    }

    // Starters matter more than the bench; injuries matter more than tiredness.
    private fun damage(p: PlayerReport): Double {
        var points = when (p.risk) {
            Risk.RED -> 3.0
            Risk.AMBER -> 1.5
            Risk.GREEN -> 0.0
        }
        points += when (p.rest.level) {
            RestLevel.TIGHT -> 1.0
            RestLevel.WATCH -> 0.5
            else -> 0.0
        }
        return if (p.isBench) points / 2 else points
    }

    private fun flagReason(p: PlayerReport): String? = when {
        p.callUp.status == CallUpStatus.WITHDRAWN -> "withdrew"
        p.risk == Risk.RED -> "injury concern"
        p.risk == Risk.AMBER -> when {
            p.callUp.agreement == "conflict" -> "call-up unclear"
            p.hasFotmobInjury -> "existing injury"
            p.fplStatus != "a" -> p.fplChance?.let { "FPL $it%" } ?: "FPL flag"
            p.matches.any { it.started && (it.subbedOffMinute ?: 99) < 60 } -> "subbed off early"
            else -> "keep an eye"
        }
        p.rest.level == RestLevel.TIGHT -> "${p.rest.restDays}d rest"
        else -> null
    }

    private suspend fun <T> wrapErrors(teamId: Int?, block: suspend () -> T): T = try {
        block()
    } catch (e: HttpException) {
        throw ReportException(
            when {
                e.status == 404 && teamId != null -> "FPL team $teamId not found"
                e.status == 404 -> "League not found"
                else -> "A data source failed (${e.status}). Try again in a bit."
            },
        )
    } catch (e: IOException) {
        throw ReportException("No connection. Check your internet and try again.")
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
        val fixturesJob = async { fpl.fixtures() }
        val window = fpl.findBreakWindow() ?: throw ReportException("No international break found yet this season.")
        val bootstrap = bootstrapJob.await()
        val entry = entryJob.await()
        val fixtures = fixturesJob.await()

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
                val next = nextFixtures(fixtures, club, window.beforeEvent, teams)
                playerReport(pick, el, club, positions[el.elementType].orEmpty(), window, next, fotmob, tm, mapping)
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
        next: List<NextFixture>,
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
                    val fixturesInBreak = breakFixtures(team, window)
                    w.fixtures = fixturesInBreak.map { withInvolvement(it, w.matches) }
                    val appearedVs = w.matches.filter { it.team == team.name && (it.played || it.onBench) }.map { it.opponent }
                    w.callUp = combineChecks(
                        listOf(
                            fotmobCallUp(player, team, spellEnded, appearedVs, window),
                            tm.callUp(nation.name, el, club, w.mapping.fotmobName ?: player.name),
                        ),
                    )
                    if (w.callUp.status == CallUpStatus.CALLED) {
                        w.live = liveMatch(fotmob, fixturesInBreak, player.id)
                        val liveId = w.live?.fotmobMatchId
                        w.fixtures = w.fixtures.map { it.copy(live = it.fotmobMatchId == liveId) }
                    }
                }
                w.injury = player.injuryInformation
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // One player's data failing shouldn't sink the whole report.
            w.error = e.message ?: e.javaClass.simpleName
        }

        val restInfo = rest(
            withSquad = w.callUp.status == CallUpStatus.CALLED,
            fixtures = w.fixtures.map {
                RestFixture(
                    it.date,
                    it.finished,
                    it.involvement in setOf(Involvement.STARTED, Involvement.SUB, Involvement.BENCH),
                    it.competition,
                )
            },
            breakMinutes = w.fixtures.sumOf { it.minutes ?: 0 },
            clubKickoff = next.firstOrNull()?.kickoff,
        )
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
            nextFixtures = next,
            price = price(el),
            rest = restInfo,
            live = w.live,
            fplStatus = el.status,
            fplChance = el.chanceOfPlayingNextRound,
            hasFotmobInjury = w.injury != null,
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
        var live: LiveMatch? = null
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
