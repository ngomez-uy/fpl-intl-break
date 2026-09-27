package com.fplintbreak.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fplintbreak.app.data.Settings
import com.fplintbreak.app.data.TeamReport
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
import kotlinx.coroutines.launch

/** The "wrong player? pick the right one" dialog for one FPL player. */
data class MatchFix(
    val player: PlayerReport,
    val query: String,
    val searching: Boolean = false,
    val results: List<PlayerCandidate>? = null,
    val error: String? = null,
)

data class BreakUiState(
    val loading: Boolean = false,
    val report: TeamReport? = null,
    val error: String? = null,
    val matchFix: MatchFix? = null,
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

    fun load() {
        val id = teamId
        // FPL IDs are well under Int.MAX_VALUE; anything longer is a typo.
        if (id.isEmpty() || id.length > 9 || _state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val report = builder.build(id.toInt())
                settings.teamId = id
                _state.update { it.copy(loading = false, report = report) }
            } catch (e: ReportException) {
                // Keep showing the last good report if a refresh fails.
                _state.update { it.copy(loading = false, error = e.message) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "Something went wrong building the report: ${e.message}") }
            }
        }
    }
}
