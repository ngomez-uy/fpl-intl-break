package com.fplintbreak.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fplintbreak.app.data.Settings
import com.fplintbreak.app.data.TeamReport
import com.fplintbreak.app.data.H2HReport
import com.fplintbreak.app.data.LeagueOption
import com.fplintbreak.app.data.LeagueReport
import com.fplintbreak.app.data.PlayerCandidate
import com.fplintbreak.app.data.PlayerReport
import com.fplintbreak.app.engine.Overrides
import com.fplintbreak.app.engine.PlayerOverride
import com.fplintbreak.app.engine.ReportBuilder
import com.fplintbreak.app.engine.ReportException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val LIVE_REFRESH_MS = 90_000L

/** The "wrong player? pick the right one" dialog for one FPL player. */
data class MatchFix(
    val player: PlayerReport,
    val query: String,
    val searching: Boolean = false,
    val results: List<PlayerCandidate>? = null,
    val error: String? = null,
)

enum class View { SQUAD, LEAGUE, H2H }

data class H2HUiState(val loading: Boolean = false, val report: H2HReport? = null, val error: String? = null)

data class LeagueUiState(
    val options: List<LeagueOption>? = null,
    val selectedId: Int? = null,
    val limit: Int = 10,
    val loading: Boolean = false,
    val report: LeagueReport? = null,
    val error: String? = null,
)

data class BreakUiState(
    val loading: Boolean = false,
    val report: TeamReport? = null,
    val error: String? = null,
    val matchFix: MatchFix? = null,
    val view: View = View.SQUAD,
    val league: LeagueUiState = LeagueUiState(),
    val h2h: H2HUiState = H2HUiState(),
)

class BreakViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)
    private val overrides = Overrides(app)
    private val builder = ReportBuilder(app.cacheDir, overrides)

    private val _state = MutableStateFlow(BreakUiState())
    val state: StateFlow<BreakUiState> = _state.asStateFlow()

    // Text field state must update synchronously, or fast typing and the keyboard get out of
    // sync (a StateFlow round-trip duplicated input), so it lives in Compose state, not the flow.
    var teamId by mutableStateOf(settings.teamId)
        private set

    init {
        if (teamId.isNotEmpty()) load()
    }

    fun onTeamIdChange(value: String) {
        teamId = value.filter(Char::isDigit)
    }

    // While a match is live, refresh this often (live match data is re-fetched every minute).
    private fun scheduleLiveRefresh(report: TeamReport) {
        liveRefresh?.cancel()
        if (report.players.none { it.live != null }) return
        liveRefresh = viewModelScope.launch {
            delay(LIVE_REFRESH_MS)
            load(quiet = true)
        }
    }

    fun showView(view: View) {
        _state.update { it.copy(view = view) }
        if (view == View.LEAGUE && _state.value.league.options == null) loadLeagues()
        if (view == View.H2H && _state.value.h2h.report == null) loadHeadToHead()
    }

    fun loadHeadToHead() {
        val teamId = _state.value.report?.team?.id ?: return
        if (_state.value.h2h.loading) return
        _state.update { it.copy(h2h = H2HUiState(loading = true)) }
        viewModelScope.launch {
            try {
                val report = builder.headToHead(teamId)
                _state.update { it.copy(h2h = H2HUiState(report = report)) }
            } catch (e: ReportException) {
                _state.update { it.copy(h2h = H2HUiState(error = e.message)) }
            }
        }
    }

    private fun loadLeagues() {
        val teamId = _state.value.report?.team?.id ?: return
        viewModelScope.launch {
            try {
                val options = builder.teamLeagues(teamId)
                val remembered = settings.leagueId
                val selected = options.find { it.id == remembered }?.id ?: options.firstOrNull()?.id
                _state.update { it.copy(league = it.league.copy(options = options, selectedId = selected)) }
            } catch (e: ReportException) {
                _state.update { it.copy(league = it.league.copy(options = emptyList(), error = e.message)) }
            }
        }
    }

    fun selectLeague(id: Int) = _state.update { it.copy(league = it.league.copy(selectedId = id)) }

    fun selectLimit(limit: Int) = _state.update { it.copy(league = it.league.copy(limit = limit)) }

    fun compareLeague() {
        val s = _state.value
        val leagueId = s.league.selectedId ?: return
        val you = s.report?.team?.id ?: return
        if (s.league.loading) return
        _state.update { it.copy(league = it.league.copy(loading = true, error = null)) }
        viewModelScope.launch {
            try {
                val report = builder.leagueReport(leagueId, you, s.league.limit)
                settings.leagueId = leagueId
                _state.update { it.copy(league = it.league.copy(loading = false, report = report)) }
            } catch (e: ReportException) {
                _state.update { it.copy(league = it.league.copy(loading = false, error = e.message)) }
            }
        }
    }

    fun openMatchFix(player: PlayerReport) {
        _state.update { it.copy(matchFix = MatchFix(player, query = player.name)) }
        searchMatch()
    }

    fun onMatchQueryChange(value: String) = _state.update { s -> s.copy(matchFix = s.matchFix?.copy(query = value)) }

    fun searchMatch() {
        val fix = _state.value.matchFix ?: return
        if (fix.query.isBlank()) return
        _state.update { it.copy(matchFix = fix.copy(searching = true, error = null)) }
        viewModelScope.launch {
            val (results, error) = try {
                builder.searchPlayers(fix.query) to null
            } catch (e: ReportException) {
                null to e.message
            }
            _state.update { s ->
                val current = s.matchFix
                // Ignore results for a dialog that was closed or reopened meanwhile.
                if (current?.player?.fplId != fix.player.fplId) s
                else s.copy(matchFix = current.copy(searching = false, results = results ?: current.results, error = error))
            }
        }
    }

    fun pickMatch(candidate: PlayerCandidate) {
        val fix = _state.value.matchFix ?: return
        overrides.set(fix.player.fplId, PlayerOverride(candidate.fotmobId, candidate.name))
        closeMatchFix()
        load()
    }

    fun resetMatch() {
        val fix = _state.value.matchFix ?: return
        overrides.clear(fix.player.fplId)
        closeMatchFix()
        load()
    }

    fun closeMatchFix() = _state.update { it.copy(matchFix = null) }

    private var liveRefresh: Job? = null

    fun load() = load(quiet = false)

    /** `quiet` refreshes (while a match is live) keep the current report on screen and hide errors. */
    private fun load(quiet: Boolean) {
        val id = teamId
        // FPL IDs are well under Int.MAX_VALUE; anything longer is a typo.
        if (id.isEmpty() || id.length > 9 || _state.value.loading) return
        if (!quiet) _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val report = builder.build(id.toInt())
                settings.teamId = id
                val newTeam = _state.value.report?.team?.id != report.team.id
                _state.update {
                    it.copy(
                        loading = false,
                        report = report,
                        league = if (newTeam) LeagueUiState() else it.league,
                        h2h = if (newTeam) H2HUiState() else it.h2h,
                    )
                }
                scheduleLiveRefresh(report)
            } catch (e: ReportException) {
                // Keep showing the last good report if a refresh fails.
                _state.update { it.copy(loading = false, error = if (quiet) it.error else e.message) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "Something went wrong building the report: ${e.message}") }
            }
        }
    }
}
