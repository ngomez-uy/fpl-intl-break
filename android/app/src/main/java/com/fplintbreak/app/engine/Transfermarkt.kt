package com.fplintbreak.app.engine

import com.fplintbreak.app.data.CallUpStatus
import com.fplintbreak.app.data.SourceCheck
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.net.URLEncoder

// Transfermarkt's current national-team squads, as an independent call-up check. Players
// who withdraw are removed from these pages, unlike FotMob's squad lists. Transfermarkt's
// terms forbid scraping, so this makes one request per nation and caches it for hours.
// Port of backend/src/transfermarkt.ts.

@Serializable
internal data class TmTeam(val slug: String, val id: Int)

@Serializable
internal data class TmPlayer(val id: Long, val name: String, val club: String?)

// Nations already looked up, to save a search request each.
private val KNOWN = mapOf(
    "england" to TmTeam("england", 3299),
    "germany" to TmTeam("deutschland", 3262),
    "netherlands" to TmTeam("niederlande", 3379),
    "ireland" to TmTeam("irland", 3509),
    "italy" to TmTeam("italien", 3376),
    "belgium" to TmTeam("belgien", 3382),
    "norway" to TmTeam("norwegen", 3440),
    "dr congo" to TmTeam("dr-kongo", 3854),
)

private val SEARCH_HIT = Regex("""<a title="([^"]+)" href="/([a-z0-9-]+)/startseite/verein/(\d+)"""")
private val ROW_SPLIT = Regex("""<tr class="(?:odd|even)">""")
private val PLAYER_LINK = Regex("""<td class="hauptlink">\s*<a href="/[^/"]+/profil/spieler/(\d+)">\s*([^<]+?)\s*</a>""")
private val CLUB_LINK = Regex("""<a title="([^"]*)" href="/[^"]+/startseite/verein/\d+"""")

private fun decode(s: String) = s
    .replace(Regex("&#0?39;|&apos;"), "'")
    .replace("&amp;", "&")
    .replace("&quot;", "\"")
    .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
    .trim()

private fun lastWord(s: String) = normalize(s).split(" ").last()

internal class Transfermarkt(private val f: Fetcher) {
    private suspend fun findTeam(nation: String): TmTeam? {
        val key = normalize(nation)
        KNOWN[key]?.let { return it }
        val cacheKey = "transfermarkt_search_$key"
        f.cache.read(cacheKey, f.ages)?.let { return if (it == "null") null else lenientJson.decodeFromString(it) }
        val page = f.http.getText("$BASE/schnellsuche/ergebnis/schnellsuche?query=${URLEncoder.encode(nation, "UTF-8")}", "text/html")
        val team = SEARCH_HIT.findAll(page)
            .firstOrNull { normalize(decode(it.groupValues[1])) == key }
            ?.let { TmTeam(it.groupValues[2], it.groupValues[3].toInt()) }
        val body = team?.let { lenientJson.encodeToString(TmTeam.serializer(), it) } ?: "null"
        f.cache.write(cacheKey, body, 30 * DAY, f.ages)
        return team
    }

    // Squads change when players withdraw, but rarely more than once a day.
    private suspend fun squad(team: TmTeam): List<TmPlayer> {
        val cacheKey = "transfermarkt_squad_${team.id}"
        val serializer = ListSerializer(TmPlayer.serializer())
        f.cache.read(cacheKey, f.ages)?.let { return lenientJson.decodeFromString(serializer, it) }
        val page = f.http.getText("$BASE/${team.slug}/kader/verein/${team.id}", "text/html")
        // One <tr class="odd|even"> per player; the nested position table's rows have no class.
        val players = ROW_SPLIT.split(page).drop(1).mapNotNull { row ->
            val link = PLAYER_LINK.find(row) ?: return@mapNotNull null
            TmPlayer(link.groupValues[1].toLong(), decode(link.groupValues[2]), CLUB_LINK.find(row)?.let { decode(it.groupValues[1]) })
        }.distinctBy { it.id }
        f.cache.write(cacheKey, lenientJson.encodeToString(serializer, players), 3 * HOUR, f.ages)
        return players
    }

    private fun findPlayer(squad: List<TmPlayer>, el: FplElement, club: FplTeam, fotmobName: String): TmPlayer? {
        val names = listOf(fotmobName, "${el.firstName} ${el.secondName}", el.webName).map(::normalize)
        squad.find { normalize(it.name) in names }?.let { return it }
        // Name spelt differently (e.g. dropped middle name): same surname and same club.
        return squad.find { lastWord(it.name) == lastWord(el.secondName) && it.club != null && sameClub(club, it.club) }
    }

    suspend fun callUp(nation: String, el: FplElement, club: FplTeam, fotmobName: String): SourceCheck = try {
        val team = findTeam(nation)
        if (team == null) {
            SourceCheck(SOURCE, CallUpStatus.UNKNOWN, "Couldn't find $nation on Transfermarkt")
        } else {
            val url = "$BASE/${team.slug}/kader/verein/${team.id}"
            val squad = squad(team)
            when {
                squad.isEmpty() -> SourceCheck(SOURCE, CallUpStatus.UNKNOWN, "Transfermarkt's $nation squad page couldn't be read", url)
                findPlayer(squad, el, club, fotmobName) != null ->
                    SourceCheck(SOURCE, CallUpStatus.CALLED, "In Transfermarkt's current $nation squad", url)
                else -> SourceCheck(SOURCE, CallUpStatus.NOT_CALLED, "Not in Transfermarkt's current $nation squad", url)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SourceCheck(SOURCE, CallUpStatus.UNKNOWN, "Transfermarkt unavailable: ${e.message}")
    }

    private companion object {
        const val BASE = "https://www.transfermarkt.com"
        const val SOURCE = "Transfermarkt"
    }
}
