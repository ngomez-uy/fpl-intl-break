package com.fplintbreak.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The report the on-phone engine (../engine) builds. Same shape as the web backend's
// TeamReport in backend/src/analyze.ts, so the two stay easy to compare.

@Serializable
enum class Risk {
    @SerialName("red") RED,
    @SerialName("amber") AMBER,
    @SerialName("green") GREEN,
}

@Serializable
enum class CallUpStatus {
    @SerialName("called") CALLED,
    @SerialName("withdrawn") WITHDRAWN,
    @SerialName("not_called") NOT_CALLED,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
enum class Involvement {
    @SerialName("started") STARTED,
    @SerialName("sub") SUB,
    @SerialName("bench") BENCH,
    @SerialName("absent") ABSENT,
    @SerialName("upcoming") UPCOMING,
}

@Serializable
enum class Severity {
    @SerialName("high") HIGH,
    @SerialName("medium") MEDIUM,
    @SerialName("info") INFO,
}

@Serializable
data class TeamReport(
    val team: TeamInfo,
    val window: BreakWindow,
    val generatedAt: String,
    val players: List<PlayerReport>,
    val sources: List<DataSource> = emptyList(),
)

@Serializable
data class TeamInfo(val id: Int, val name: String, val manager: String, val picksFromEvent: Int)

@Serializable
data class BreakWindow(val from: String, val to: String, val afterEvent: Int, val beforeEvent: Int)

@Serializable
data class PlayerReport(
    val fplId: Int,
    val pickPosition: Int,
    val name: String,
    val webName: String,
    val club: String,
    val position: String,
    val squadRole: String,
    val isCaptain: Boolean,
    val isViceCaptain: Boolean,
    val fotmob: FotmobLink = FotmobLink(),
    val nationalTeam: String? = null,
    val nation: Nation? = null,
    val callUp: CallUp,
    val breakFixtures: List<BreakMatch> = emptyList(),
    val matches: List<IntlMatch> = emptyList(),
    val nextFixtures: List<NextFixture> = emptyList(),
    val price: Price = Price(0.0, 0.0, 0),
    val rest: Rest = Rest(),
    val live: LiveMatch? = null,
    val fplStatus: String = "a",
    val fplChance: Int? = null,
    val hasFotmobInjury: Boolean = false,
    val signals: List<Signal> = emptyList(),
    val risk: Risk,
    val confirmedByBoth: Boolean = false,
) {
    val isBench get() = squadRole == "bench"
    val isAway get() = callUp.status == CallUpStatus.CALLED
    val upcoming get() = breakFixtures.filter { !it.finished && !it.live }

    /** "Out" only means something for a player who was in the squad. */
    val played get() = breakFixtures.filter { it.finished && (isAway || it.involvement != Involvement.ABSENT) }
}

/** Which FotMob player this FPL player was matched to, and how. */
@Serializable
data class FotmobLink(
    val id: Long? = null,
    val name: String? = null,
    val confidence: String = "none", // "override" (picked by hand) | "high" | "low" | "none"
)

/** A FotMob search result offered when fixing a player's match. */
data class PlayerCandidate(val fotmobId: Long, val name: String, val team: String?)

@Serializable
data class Nation(val fotmobTeamId: Int, val name: String, val capped: Boolean)

@Serializable
data class CallUp(val status: CallUpStatus, val checks: List<SourceCheck> = emptyList(), val agreement: String)

@Serializable
data class SourceCheck(val source: String, val status: CallUpStatus, val detail: String, val url: String? = null)

@Serializable
data class BreakMatch(
    val fotmobMatchId: Long,
    val date: String,
    val opponent: String,
    val home: Boolean,
    val competition: String,
    val finished: Boolean,
    val score: String? = null,
    val involvement: Involvement,
    val minutes: Int? = null,
    val live: Boolean = false,
)

@Serializable
data class NextFixture(val opponent: String, val home: Boolean, val kickoff: String, val difficulty: Int)

@Serializable
data class Price(val now: Double, val changeThisGw: Double, val netTransfers: Int)

@Serializable
enum class RestLevel {
    @SerialName("ok") OK,
    @SerialName("watch") WATCH,
    @SerialName("tight") TIGHT,
    @SerialName("n/a") NA,
}

@Serializable
data class Rest(
    val level: RestLevel = RestLevel.NA,
    val lastIntlMatch: String? = null,
    val clubKickoff: String? = null,
    val restDays: Double? = null,
    val breakMinutes: Int = 0,
    val longTrip: String? = null,
    val note: String = "",
)

@Serializable
enum class OnPitch {
    @SerialName("playing") PLAYING,
    @SerialName("subbed_off") SUBBED_OFF,
    @SerialName("bench") BENCH,
    @SerialName("not_in_squad") NOT_IN_SQUAD,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
data class LiveMatch(
    val fotmobMatchId: Long,
    val opponent: String,
    val home: Boolean,
    val minute: String,
    val score: String?,
    val onPitch: OnPitch,
)

/** One of the user's private mini-leagues. */
data class LeagueOption(val id: Int, val name: String)

data class FlaggedPlayer(val name: String, val reason: String, val bench: Boolean)

data class RivalImpact(
    val entry: Int,
    val teamName: String,
    val manager: String,
    val rank: Int,
    val isYou: Boolean,
    val score: Double, // "break damage": higher = hit harder
    val injured: Int,
    val watch: Int,
    val withdrew: Int,
    val tightRest: Int,
    val away: Int,
    val flagged: List<FlaggedPlayer>,
    val error: String? = null,
)

data class LeagueReport(val leagueName: String, val generatedAt: String, val rivals: List<RivalImpact>, val limit: Int)

@Serializable
data class IntlMatch(
    val fotmobMatchId: Long,
    val date: String,
    val competition: String,
    val team: String = "",
    val opponent: String = "",
    val home: Boolean = false,
    val score: String,
    val played: Boolean,
    val started: Boolean,
    val onBench: Boolean,
    val minutes: Int = 0,
    val goals: Int = 0,
    val assists: Int = 0,
    val yellowCards: Int = 0,
    val redCards: Int = 0,
    val rating: String? = null,
    val subbedOnMinute: Int? = null,
    val subbedOffMinute: Int? = null,
    val offReason: String? = null,
    val injuredOff: Boolean = false,
)

@Serializable
data class Signal(val source: String, val severity: Severity, val text: String)

@Serializable
data class DataSource(
    val id: String,
    val name: String,
    val url: String,
    val usedFor: List<String>,
    val status: String,
    val oldestDataAt: String? = null,
)
