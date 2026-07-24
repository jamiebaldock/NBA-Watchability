package com.nbawatchability.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nbawatchability.app.data.BACKEND_BASE_URL
import com.nbawatchability.app.data.LeagueGroup
import com.nbawatchability.app.data.NetworkLeagueContentRepository
import com.nbawatchability.app.data.Player
import com.nbawatchability.app.data.Team
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

sealed interface LeagueRostersUiState {
    data object Loading : LeagueRostersUiState
    data class Error(val message: String) : LeagueRostersUiState
    data class Loaded(val playersByTeam: List<Pair<Team, Player>>) : LeagueRostersUiState
}

/**
 * Every team's roster in one league, fetched in parallel and flattened -
 * backs FavoritePlayersScreen's "search players by name without picking a
 * team first" box. Same one-call-per-team/merge/partial-failure-resilient
 * shape as FavoriteGamesViewModel's per-team schedule fetch, just applied to
 * rosters instead of schedules. Kicked off once teams for the currently
 * selected league have loaded, so results are usually already warm by the
 * time the user finishes typing rather than triggered per keystroke.
 */
class LeagueRostersViewModel : ViewModel() {

    var uiState by mutableStateOf<LeagueRostersUiState>(LeagueRostersUiState.Loading)
        private set

    private var currentLeagueGroup: LeagueGroup? = null
    private var currentTeams: List<Team> = emptyList()

    fun load(leagueGroup: LeagueGroup, teams: List<Team>) {
        currentLeagueGroup = leagueGroup
        currentTeams = teams
        uiState = LeagueRostersUiState.Loading
        viewModelScope.launch {
            val result = try {
                LeagueRostersUiState.Loaded(fetchAll(leagueGroup, teams))
            } catch (e: Exception) {
                LeagueRostersUiState.Error(e.message ?: "Couldn't reach the backend")
            }
            // Discard a stale response for a league the caller has since
            // moved away from (e.g. quickly switching NBA -> WNBA tabs
            // before the slower, 30-team NBA fetch finishes) - without this
            // guard, whichever request happens to resolve last simply wins
            // regardless of which league is actually selected now, which is
            // exactly how a WNBA search ended up showing NBA team names
            // (James's report, 2026-07-24: Angela Dugalic/Nell Angloma
            // shown with Timberwolves/Knicks).
            if (currentLeagueGroup == leagueGroup) {
                uiState = result
            }
        }
    }

    fun retry() {
        val leagueGroup = currentLeagueGroup ?: return
        load(leagueGroup, currentTeams)
    }

    // Each team's roster fetch is independent - one team's failure doesn't
    // sink every other team's already-fetched players. Only surfaces a
    // page-level Error if literally every fetch attempted failed.
    private suspend fun fetchAll(leagueGroup: LeagueGroup, teams: List<Team>): List<Pair<Team, Player>> = coroutineScope {
        val results = teams.map { team ->
            async {
                runCatching {
                    NetworkLeagueContentRepository.roster(BACKEND_BASE_URL, leagueGroup, team.id).players.map { team to it }
                }
            }
        }.awaitAll()

        if (results.isNotEmpty() && results.none { it.isSuccess }) {
            throw results.first().exceptionOrNull() ?: Exception("Couldn't reach the backend")
        }

        results.flatMap { it.getOrDefault(emptyList()) }
    }
}
