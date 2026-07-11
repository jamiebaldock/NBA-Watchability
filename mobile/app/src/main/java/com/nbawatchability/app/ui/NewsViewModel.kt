package com.nbawatchability.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nbawatchability.app.data.BACKEND_BASE_URL
import com.nbawatchability.app.data.LeagueGroup
import com.nbawatchability.app.data.NetworkLeagueContentRepository
import com.nbawatchability.app.data.NewsResponse
import kotlinx.coroutines.launch

sealed interface NewsUiState {
    data object Loading : NewsUiState
    data class Error(val message: String) : NewsUiState
    data class Loaded(val data: NewsResponse) : NewsUiState
}

class NewsViewModel : ViewModel() {

    var uiState by mutableStateOf<NewsUiState>(NewsUiState.Loading)
        private set

    private var currentLeagueGroup: LeagueGroup = LeagueGroup.NBA

    fun load(leagueGroup: LeagueGroup) {
        currentLeagueGroup = leagueGroup
        uiState = NewsUiState.Loading
        viewModelScope.launch {
            uiState = try {
                NewsUiState.Loaded(NetworkLeagueContentRepository.news(BACKEND_BASE_URL, leagueGroup))
            } catch (e: Exception) {
                NewsUiState.Error(e.message ?: "Couldn't reach the backend")
            }
        }
    }

    fun retry() = load(currentLeagueGroup)
}
