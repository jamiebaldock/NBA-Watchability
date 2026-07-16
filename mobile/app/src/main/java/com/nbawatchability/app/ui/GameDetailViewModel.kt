package com.nbawatchability.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nbawatchability.app.data.BACKEND_BASE_URL
import com.nbawatchability.app.data.GameDetail
import com.nbawatchability.app.data.NetworkLeagueContentRepository
import kotlinx.coroutines.launch

sealed interface GameDetailUiState {
    data object Loading : GameDetailUiState
    data class Error(val message: String) : GameDetailUiState
    data class Loaded(val data: GameDetail) : GameDetailUiState
}

/** Backs the game-detail popup - one specific game's top-performers/head-to-head/standings context, fetched fresh every time the popup opens (never cached client-side, matching the backend's own on-demand design). */
class GameDetailViewModel : ViewModel() {

    var uiState by mutableStateOf<GameDetailUiState>(GameDetailUiState.Loading)
        private set

    private var currentEventId: String? = null

    fun load(eventId: String) {
        currentEventId = eventId
        uiState = GameDetailUiState.Loading
        viewModelScope.launch {
            uiState = try {
                GameDetailUiState.Loaded(NetworkLeagueContentRepository.gameDetail(BACKEND_BASE_URL, eventId))
            } catch (e: Exception) {
                GameDetailUiState.Error(e.message ?: "Couldn't reach the backend")
            }
        }
    }

    fun retry() = currentEventId?.let { load(it) }
}
