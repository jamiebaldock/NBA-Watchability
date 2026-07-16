package com.nbawatchability.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nbawatchability.app.data.BACKEND_BASE_URL
import com.nbawatchability.app.data.LeagueGroup
import com.nbawatchability.app.data.NetworkLeagueContentRepository
import com.nbawatchability.app.data.TeamsResponse
import kotlinx.coroutines.launch

sealed interface TeamsUiState {
    data object Loading : TeamsUiState
    data class Error(val message: String) : TeamsUiState
    data class Loaded(val data: TeamsResponse) : TeamsUiState
}

/** Backs the favorite-teams search/browse screen - one league's real roster at a time (switched via its own in-screen league picker, independent of the app's shared league dropdown). */
class TeamsViewModel : ViewModel() {

    var uiState by mutableStateOf<TeamsUiState>(TeamsUiState.Loading)
        private set

    private var currentLeagueGroup: LeagueGroup = LeagueGroup.NBA

    fun load(leagueGroup: LeagueGroup) {
        currentLeagueGroup = leagueGroup
        uiState = TeamsUiState.Loading
        viewModelScope.launch {
            uiState = try {
                TeamsUiState.Loaded(NetworkLeagueContentRepository.teams(BACKEND_BASE_URL, leagueGroup))
            } catch (e: Exception) {
                TeamsUiState.Error(e.message ?: "Couldn't reach the backend")
            }
        }
    }

    fun retry() = load(currentLeagueGroup)
}
