package com.nbawatchability.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nbawatchability.app.data.BACKEND_BASE_URL
import com.nbawatchability.app.data.LeagueGroup
import com.nbawatchability.app.data.NetworkLeagueContentRepository
import com.nbawatchability.app.data.RosterResponse
import kotlinx.coroutines.launch

sealed interface RosterUiState {
    data object Loading : RosterUiState
    data class Error(val message: String) : RosterUiState
    data class Loaded(val data: RosterResponse) : RosterUiState
}

/** Backs the favorite-players search/browse screen's team -> player step, once a team has been picked (TeamsViewModel's job). */
class RosterViewModel : ViewModel() {

    var uiState by mutableStateOf<RosterUiState>(RosterUiState.Loading)
        private set

    private var currentLeagueGroup: LeagueGroup = LeagueGroup.NBA
    private var currentTeamId: String = ""

    fun load(leagueGroup: LeagueGroup, teamId: String) {
        currentLeagueGroup = leagueGroup
        currentTeamId = teamId
        uiState = RosterUiState.Loading
        viewModelScope.launch {
            val result = try {
                RosterUiState.Loaded(NetworkLeagueContentRepository.roster(BACKEND_BASE_URL, leagueGroup, teamId))
            } catch (e: Exception) {
                RosterUiState.Error(e.message ?: "Couldn't reach the backend")
            }
            // Same stale-response guard as LeagueRostersViewModel - discard
            // a result for a team/league the caller has since navigated
            // away from, so a slow-to-resolve earlier pick can't overwrite
            // a faster-resolving later one.
            if (currentLeagueGroup == leagueGroup && currentTeamId == teamId) {
                uiState = result
            }
        }
    }

    fun retry() = load(currentLeagueGroup, currentTeamId)
}
