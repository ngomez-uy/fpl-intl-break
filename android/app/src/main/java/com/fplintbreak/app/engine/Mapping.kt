package com.fplintbreak.app.engine

import kotlinx.serialization.Serializable
import java.text.Normalizer

// Maps FPL players to FotMob player IDs, cached on the phone. Port of backend/src/mapping.ts.

@Serializable
internal data class PlayerMapping(
    val fotmobId: Long? = null,
    val fotmobName: String? = null,
    val confidence: String = "none", // "override" | "high" | "low" | "none"
)

// FPL short club names -> FotMob club names, where they differ.
private val CLUB_ALIASES = mapOf(
    "man city" to "manchester city",
    "man utd" to "manchester united",
    "nott'm forest" to "nottingham forest",
    "spurs" to "tottenham hotspur",
    "wolves" to "wolverhampton wanderers",
    "newcastle" to "newcastle united",
    "leeds" to "leeds united",
    "brighton" to "brighton hove albion",
    "west ham" to "west ham united",
    "bournemouth" to "afc bournemouth",
    "leicester" to "leicester city",
    "sheffield utd" to "sheffield united",
)

private val DIACRITICS = Regex("\\p{Mn}+")

internal fun normalize(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
    .replace(DIACRITICS, "")
    .lowercase()
    .replace("&", " ")
    .replace(Regex("[^a-z0-9' ]"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

internal fun sameClub(fplTeam: FplTeam, fotmobTeamName: String?): Boolean {
    if (fotmobTeamName == null) return false
    val a = normalize(CLUB_ALIASES[normalize(fplTeam.name)] ?: fplTeam.name)
    val b = normalize(fotmobTeamName)
    return a == b || b.startsWith(a) || a.startsWith(b)
}

internal class Mapping(private val cache: DiskCache, private val fotmob: FotMob, private val overrides: Overrides) {
    suspend fun mapPlayer(el: FplElement, team: FplTeam): PlayerMapping {
        overrides[el.id]?.let { return PlayerMapping(it.fotmobId, it.fotmobName, "override") }

        val key = "map_${el.id}"
        cache.read(key)?.let { return lenientJson.decodeFromString(it) }

        val terms = listOfNotNull("${el.firstName} ${el.secondName}", el.knownName, el.webName, el.secondName)
            .filter { it.isNotBlank() }
            .distinct()

        var fallback: Suggestion? = null
        var result = PlayerMapping()
        for (term in terms) {
            val suggestions = fotmob.searchPlayers(term)
            val hit = suggestions.find { sameClub(team, it.teamName) }
            if (hit != null) {
                result = PlayerMapping(hit.id.toLong(), hit.name, "high")
                break
            }
            if (fallback == null && term == terms.first() && suggestions.size == 1) fallback = suggestions[0]
        }
        if (result.fotmobId == null && fallback != null) {
            result = PlayerMapping(fallback.id.toLong(), fallback.name, "low")
        }

        // Unmatched players get retried after a day; matches are kept for a month.
        cache.write(key, lenientJson.encodeToString(PlayerMapping.serializer(), result), if (result.fotmobId != null) 30 * DAY else DAY)
        return result
    }
}
