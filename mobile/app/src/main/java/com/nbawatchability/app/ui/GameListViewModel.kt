package com.nbawatchability.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nbawatchability.app.data.BACKEND_BASE_URL
import com.nbawatchability.app.data.DayGames
import com.nbawatchability.app.data.NetworkGameRepository
import kotlinx.coroutines.launch
import java.time.LocalDate

sealed interface ScheduleUiState {
    data object Loading : ScheduleUiState
    data class Error(val message: String) : ScheduleUiState
    data class Loaded(val days: List<DayGames>) : ScheduleUiState
}

class GameListViewModel : ViewModel() {

    val today: LocalDate = LocalDate.now()

    var uiState by mutableStateOf<ScheduleUiState>(ScheduleUiState.Loading)
        private set

    var selectedDayIndex by mutableStateOf(0)
        private set

    var showNumericScore by mutableStateOf(false)
        private set

    var sortBestFirst by mutableStateOf(false)
        private set

    init {
        load()
    }

    fun load() {
        uiState = ScheduleUiState.Loading
        viewModelScope.launch {
            uiState = try {
                val days = NetworkGameRepository.schedule(
                    baseUrl = BACKEND_BASE_URL,
                    start = today.minusDays(3),
                    end = today.plusDays(3)
                )
                selectedDayIndex = days.indexOfFirst { it.date == today }.coerceAtLeast(0)
                ScheduleUiState.Loaded(days)
            } catch (e: Exception) {
                ScheduleUiState.Error(e.message ?: "Couldn't reach the backend")
            }
        }
    }

    fun selectDay(index: Int) {
        selectedDayIndex = index
    }

    fun toggleNumericScore() {
        showNumericScore = !showNumericScore
    }

    fun toggleSortBestFirst() {
        sortBestFirst = !sortBestFirst
    }
}
