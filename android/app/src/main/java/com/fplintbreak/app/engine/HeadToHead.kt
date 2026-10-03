package com.fplintbreak.app.engine

import com.fplintbreak.app.data.BlankPlayer
import com.fplintbreak.app.data.Contributor
import com.fplintbreak.app.data.H2HMatch
import com.fplintbreak.app.data.H2HPlayer
import com.fplintbreak.app.data.H2HReport
import com.fplintbreak.app.data.H2HSeason
import com.fplintbreak.app.data.H2HStatus
import com.fplintbreak.app.data.Matchup
import com.fplintbreak.app.data.MatchupSide
import com.fplintbreak.app.data.Meeting
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.time.ZoneOffset

// The next gameweek's matches, seen through history: the last meetings between the two clubs,
// who did the damage in them, and how each of your squad players has done against that
// opponent, season by season. Port of backend/src/h2h.ts. The current season comes from FPL.
// FPL only keeps season totals for earlier seasons, so those come from
// vaastav/Fantasy-Premier-League on GitHub, a long-running archive of the same FPL API data,
// saved every gameweek.

private const val PAST_SEASONS = 5
private const val LAST_MEETINGS = 5
private const val TOP_N = 3
private const val ARCHIVE = "https://raw.githubusercontent.com/vaastav/Fantasy-Premier-League/master/data"

private data class HistoryRow(
    val opponent: Int, // that season's team id
    val kickoff: String,
    val home: Boolean,
    val teamH: Int,
    val teamA: Int,
    val minutes: Int,
    val goals: Int,
    val assists: Int,
    val points: Int,
)

private data class ArchivePlayer(val id: Int, val code: Int, val folder: String, val team: Int)

private data class ArchiveTeam(val id: Int, val code: Int, val short: String)

/** (element, value) credits for one side. */
private typealias Tally = List<Pair<Int, Int>>

/** A finished match with goal and assist credits, in one season's ids. */
private data class PlayedFixture(
    val kickoff: String,
    val teamH: Int,
    val teamA: Int,
    val homeGoals: Int,
    val awayGoals: Int,
    val goalsH: Tally,
    val goalsA: Tally,
    val assistsH: Tally,
    val assistsA: Tally,
)

/** Minimal CSV parser: quoted fields, doubled quotes, no newlines inside fields. */
private fun parseCsv(text: String): List<Map<String, String>> {
    fun split(line: String): List<String> {
        val out = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                quoted && ch == '"' && line.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                quoted && ch == '"' -> quoted = false
                quoted -> field.append(ch)
                ch == '"' -> quoted = true
                ch == ',' -> { out += field.toString(); field.clear() }
                else -> field.append(ch)
            }
            i++
        }
        out += field.toString()
        return out
    }
    val lines = text.lineSequence().filter { it.isNotEmpty() }.map(::split).toList()
    if (lines.isEmpty()) return emptyList()
    val header = lines.first()
    return lines.drop(1).map { r -> header.mapIndexed { i, h -> h to r.getOrElse(i) { "" } }.toMap() }
}

private fun Map<String, String>.int(key: String) = this[key]?.toDoubleOrNull()?.toInt() ?: 0

private val CREDIT = Regex("""'value': (\d+), 'element': (\d+)""")

/**
 * One stat's credits for one side from the archive's `stats` column, which is a Python repr:
 * "[{'identifier': 'goals_scored', 'a': [{'value': 1, 'element': 82}], 'h': [...]}, ...]".
 */
private fun reprTally(stats: String, identifier: String, side: Char): Tally {
    val start = stats.indexOf("'identifier': '$identifier'")
    if (start < 0) return emptyList()
    val end = stats.indexOf("'identifier'", start + 1)
    val section = if (end < 0) stats.substring(start) else stats.substring(start, end)
    val from = section.indexOf("'$side': [")
    if (from < 0) return emptyList()
    val list = section.substring(from, section.indexOf(']', from))
    return CREDIT.findAll(list).map { it.groupValues[2].toInt() to it.groupValues[1].toInt() }.toList()
}

private fun Map<String, String>.toRow() = HistoryRow(
    opponent = int("opponent_team"),
    kickoff = this["kickoff_time"].orEmpty(),
    home = this["was_home"].equals("true", ignoreCase = true),
    teamH = int("team_h_score"),
    teamA = int("team_a_score"),
    minutes = int("minutes"),
    goals = int("goals_scored"),
    assists = int("assists"),
    points = int("total_points"),
)

private data class SeasonName(val folder: String, val label: String)

/** "2025-26" folder name and "2025/26" label for the season starting in `year`. */
private fun seasonNames(year: Int): SeasonName {
    val end = ((year + 1) % 100).toString().padStart(2, '0')
    return SeasonName("$year-$end", "$year/$end")
}

private fun seasonStats(season: String, club: String?, rows: List<HistoryRow>): H2HSeason {
    if (rows.isEmpty()) return H2HSeason(season, H2HStatus.NOT_MET, club)
    val played = rows.filter { it.minutes > 0 }
    return H2HSeason(
        season = season,
        status = H2HStatus.PLAYED,
        club = club,
        apps = played.size,
        minutes = played.sumOf { it.minutes },
        goals = played.sumOf { it.goals },
        assists = played.sumOf { it.assists },
        points = played.sumOf { it.points },
        matches = played.map { r ->
            val (us, them) = if (r.home) r.teamH to r.teamA else r.teamA to r.teamH
            val outcome = if (us > them) "W" else if (us < them) "L" else "D"
            H2HMatch(r.kickoff, r.home, "$outcome $us-$them", r.minutes, r.goals, r.assists, r.points)
        },
    )
}

/** One past season of the archive, indexed for lookups. */
private class ArchiveSeason(
    val label: String,
    val folder: String,
    players: List<ArchivePlayer>,
    teams: List<ArchiveTeam>,
    val fixtures: List<PlayedFixture>,
) {
    val byCode = players.associateBy { it.code }
    val codeById = players.associate { it.id to it.code }
    val teams = teams.associateBy { it.id }
    val teamIdByCode = teams.associate { it.code to it.id }
}

private enum class Side { HOME, AWAY }

private data class Credit(val code: Int, val side: Side, val goals: Int, val assists: Int)

/** A finished meeting, with credits translated to stable player codes. */
private class CodedMeeting(val meeting: Meeting, val credits: List<Credit>)

private fun codedMeeting(f: PlayedFixture, season: String, shortOf: (Int) -> String, codeOf: (Int) -> Int?): CodedMeeting {
    val credits = mutableMapOf<Pair<Side, Int>, Credit>()
    fun add(tally: Tally, side: Side, goals: Boolean) {
        for ((element, value) in tally) {
            val code = codeOf(element) ?: continue
            val c = credits[side to code] ?: Credit(code, side, 0, 0)
            credits[side to code] = if (goals) c.copy(goals = c.goals + value) else c.copy(assists = c.assists + value)
        }
    }
    add(f.goalsH, Side.HOME, true)
    add(f.goalsA, Side.AWAY, true)
    add(f.assistsH, Side.HOME, false)
    add(f.assistsA, Side.AWAY, false)
    return CodedMeeting(
        Meeting(season, f.kickoff, shortOf(f.teamH), shortOf(f.teamA), f.homeGoals, f.awayGoals),
        credits.values.toList(),
    )
}

internal class HeadToHead(private val fpl: Fpl, private val f: Fetcher) {
    // Past seasons never change, so archive files are cached for good.
    private suspend fun archivePlayers(season: String) =
        parseCsv(f.cached("archive_squad_$season", null, "$ARCHIVE/$season/players_raw.csv")).map { r ->
            ArchivePlayer(r.int("id"), r.int("code"), "${r["first_name"]}_${r["second_name"]}_${r["id"]}", r.int("team"))
        }

    private suspend fun archiveTeams(season: String) =
        parseCsv(f.cached("archive_clubs_$season", null, "$ARCHIVE/$season/teams.csv")).map { r ->
            ArchiveTeam(r.int("id"), r.int("code"), r["short_name"].orEmpty())
        }

    private suspend fun archiveFixtures(season: String) =
        parseCsv(f.cached("archive_fixtures_$season", null, "$ARCHIVE/$season/fixtures.csv"))
            .filter { it["finished"] == "True" }
            .map { r ->
                val stats = r["stats"].orEmpty()
                PlayedFixture(
                    kickoff = r["kickoff_time"].orEmpty(),
                    teamH = r.int("team_h"),
                    teamA = r.int("team_a"),
                    homeGoals = r.int("team_h_score"),
                    awayGoals = r.int("team_a_score"),
                    goalsH = reprTally(stats, "goals_scored", 'h'),
                    goalsA = reprTally(stats, "goals_scored", 'a'),
                    assistsH = reprTally(stats, "assists", 'h'),
                    assistsA = reprTally(stats, "assists", 'a'),
                )
            }

    private suspend fun archiveHistory(season: String, folder: String): List<HistoryRow> {
        val path = URLEncoder.encode(folder, "UTF-8").replace("+", "%20")
        return parseCsv(f.cached("archive_history_${season}_$folder", null, "$ARCHIVE/$season/players/$path/gw.csv")).map { it.toRow() }
    }

    private suspend fun currentHistory(id: Int): List<HistoryRow> =
        lenientJson.parseToJsonElement(fpl.elementSummary(id)).jsonObject["history"]!!.jsonArray.map { el ->
            (el as JsonObject).mapValues { it.value.jsonPrimitive.content }.toRow()
        }

    private suspend fun loadSeason(year: Int): ArchiveSeason? {
        val (folder, label) = seasonNames(year)
        return try {
            coroutineScope {
                val players = async { archivePlayers(folder) }
                val teams = async { archiveTeams(folder) }
                val fixtures = async { archiveFixtures(folder) }
                ArchiveSeason(label, folder, players.await(), teams.await(), fixtures.await())
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun pastSeason(year: Int, s: ArchiveSeason?, playerCode: Int, opponentCode: Int): H2HSeason {
        val label = seasonNames(year).label
        if (s == null) return H2HSeason(label, H2HStatus.UNAVAILABLE, null)
        val player = s.byCode[playerCode] ?: return H2HSeason(label, H2HStatus.NO_PLAYER, null)
        val club = s.teams[player.team]
        if (club?.code == opponentCode) return H2HSeason(label, H2HStatus.OWN_CLUB, club.short)
        val opponentId = s.teamIdByCode[opponentCode] ?: return H2HSeason(label, H2HStatus.NO_OPPONENT, club?.short)
        return try {
            seasonStats(label, club?.short, archiveHistory(s.folder, player.folder).filter { it.opponent == opponentId })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            H2HSeason(label, H2HStatus.UNAVAILABLE, club?.short)
        }
    }

    suspend fun build(teamId: Int): H2HReport = coroutineScope {
        val bootstrapJob = async { fpl.bootstrap() }
        val entryJob = async { fpl.entry(teamId) }
        val fixturesJob = async { fpl.fixtures() }
        val bootstrap = bootstrapJob.await()
        val entry = entryJob.await()
        val fixtures = fixturesJob.await()
        val nextEvent = bootstrap.events.firstOrNull { !it.finished }?.id
            ?: throw ReportException("The season is over: there's no next gameweek.")
        val picks = fpl.picks(teamId, entry.currentEvent).picks

        val teams = bootstrap.teams.associateBy { it.id }
        val teamByCode = bootstrap.teams.associateBy { it.code }
        val elements = bootstrap.elements.associateBy { it.id }
        val elementByCode = bootstrap.elements.associateBy { it.code }
        val positions = bootstrap.elementTypes.associate { it.id to it.singularNameShort }
        val startYear = parseInstant(bootstrap.events.first().deadlineTime).atZone(ZoneOffset.UTC).year
        val current = seasonNames(startYear).label
        val pastYears = (1..PAST_SEASONS).map { startYear - it }
        val archive = pastYears.map { y -> async { loadSeason(y) } }.awaitAll()
        val squadCodes = picks.map { elements.getValue(it.element).code }.toSet()

        /** Every finished meeting between two clubs, this season and the archived ones, newest first. */
        fun meetingsBetween(codeA: Int, codeB: Int): List<CodedMeeting> {
            fun pair(h: Int, a: Int, x: Int?, y: Int?) = x != null && y != null && ((h == x && a == y) || (h == y && a == x))
            val a = teamByCode.getValue(codeA).id
            val b = teamByCode.getValue(codeB).id
            fun tally(fx: com.fplintbreak.app.engine.FplFixture, id: String, home: Boolean): Tally =
                fx.stats.find { it.identifier == id }?.let { s -> (if (home) s.h else s.a).map { it.element to it.value } }.orEmpty()
            val thisSeason = fixtures.filter { it.finished && pair(it.teamH, it.teamA, a, b) }.map { fx ->
                codedMeeting(
                    PlayedFixture(
                        fx.kickoffTime.orEmpty(), fx.teamH, fx.teamA, fx.teamHScore ?: 0, fx.teamAScore ?: 0,
                        tally(fx, "goals_scored", true), tally(fx, "goals_scored", false),
                        tally(fx, "assists", true), tally(fx, "assists", false),
                    ),
                    current,
                    { teams.getValue(it).shortName },
                    { elements[it]?.code },
                )
            }
            val past = archive.filterNotNull().flatMap { s ->
                val x = s.teamIdByCode[codeA]
                val y = s.teamIdByCode[codeB]
                s.fixtures.filter { pair(it.teamH, it.teamA, x, y) }
                    .map { fx -> codedMeeting(fx, s.label, { s.teams[it]?.short ?: "?" }, { s.codeById[it] }) }
            }
            return (thisSeason + past).sortedByDescending { it.meeting.kickoff }
        }

        /** A club's top contributors in these meetings, among players still at that club. */
        fun topFor(clubCode: Int, meetings: List<CodedMeeting>): List<Contributor> {
            val club = teamByCode.getValue(clubCode)
            val totals = mutableMapOf<Int, Pair<Int, Int>>()
            for (m in meetings) {
                val side = if (m.meeting.home == club.shortName) Side.HOME else Side.AWAY
                for (c in m.credits) {
                    if (c.side != side || elementByCode[c.code]?.team != club.id) continue
                    val (g, a) = totals[c.code] ?: (0 to 0)
                    totals[c.code] = (g + c.goals) to (a + c.assists)
                }
            }
            return totals.entries
                .map { (code, t) -> Contributor(elementByCode.getValue(code).webName, t.first, t.second, code in squadCodes) }
                .sortedWith(compareByDescending<Contributor> { it.goals + it.assists }.thenByDescending { it.goals })
                .take(TOP_N)
        }

        suspend fun playerRecord(pickPosition: Int, elementId: Int, opponentCode: Int): H2HPlayer = coroutineScope {
            val el = elements.getValue(elementId)
            val club = teams.getValue(el.team)
            val opponent = teamByCode.getValue(opponentCode)
            val history = currentHistory(el.id)
            val past = pastYears.mapIndexed { i, y -> async { pastSeason(y, archive[i], el.code, opponentCode) } }.awaitAll()
            H2HPlayer(
                id = el.id,
                name = el.webName,
                position = positions[el.elementType].orEmpty(),
                club = club.shortName,
                bench = pickPosition > 11,
                seasons = listOf(seasonStats(current, club.shortName, history.filter { it.opponent == opponent.id })) + past,
            )
        }

        val next = fixtures.filter { it.event == nextEvent }.sortedBy { it.kickoffTime.orEmpty() }
        val matchups = next.mapNotNull { fx ->
            val mine = picks.filter { elements.getValue(it.element).team.let { t -> t == fx.teamH || t == fx.teamA } }
            if (mine.isEmpty()) return@mapNotNull null
            val home = teams.getValue(fx.teamH)
            val away = teams.getValue(fx.teamA)
            async {
                val meetings = meetingsBetween(home.code, away.code).take(LAST_MEETINGS)
                val players = mine.map { p ->
                    async {
                        val isHome = elements.getValue(p.element).team == fx.teamH
                        playerRecord(p.position, p.element, (if (isHome) away else home).code)
                    }
                }.awaitAll()
                Matchup(
                    kickoff = fx.kickoffTime,
                    home = MatchupSide(home.name, home.shortName, topFor(home.code, meetings)),
                    away = MatchupSide(away.name, away.shortName, topFor(away.code, meetings)),
                    meetings = meetings.map { it.meeting },
                    players = players,
                )
            }
        }.awaitAll()

        val playing = next.flatMap { listOf(it.teamH, it.teamA) }.toSet()
        val blanks = picks.map { elements.getValue(it.element) }.filter { it.team !in playing }
            .map { BlankPlayer(it.id, it.webName, teams.getValue(it.team).shortName) }

        H2HReport(nextEvent, seasonNames(pastYears.last()).label, matchups, blanks)
    }
}
