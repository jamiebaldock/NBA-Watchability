package com.nbawatchability.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nbawatchability.app.data.BACKEND_BASE_URL
import com.nbawatchability.app.data.Game
import com.nbawatchability.app.data.NetworkLeagueContentRepository
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class HistoryRangePreset(val label: String) {
    LAST_30_DAYS("Last 30 days"),
    LAST_3_MONTHS("Last 3 months"),
    THIS_SEASON("This season"),
    ALL_TIME("All time")
}

sealed interface HistoryUiState {
    data object Loading : HistoryUiState
    data class Error(val message: String) : HistoryUiState
    data class Loaded(val games: List<Game>) : HistoryUiState
}

/**
 * NBA-only for now (the backfill this reads from is NBA-only) - no league
 * group parameter, unlike GameListViewModel/StandingsViewModel/etc.
 */
class HistoryViewModel : ViewModel() {

    var uiState by mutableStateOf<HistoryUiState>(HistoryUiState.Loading)
        private set

    var selectedPreset by mutableStateOf(HistoryRangePreset.LAST_30_DAYS)
        private set

    // Server clamps the actual query to this regardless (getHistoryForRange),
    // this is just so the empty-state message can name a real date instead
    // of a vague "try a wider range" - request it once so a repeat "All
    // time" pick without a change doesn't need a fresh response first.
    var earliestDate: LocalDate? = null
        private set

    fun load(preset: HistoryRangePreset = selectedPreset) {
        selectedPreset = preset
        uiState = HistoryUiState.Loading
        viewModelScope.launch {
            uiState = try {
                val today = LocalDate.now()
                val response = NetworkLeagueContentRepository.history(
                    baseUrl = BACKEND_BASE_URL,
                    start = startDateFor(preset, today),
                    end = today
                )
                earliestDate = LocalDate.parse(response.earliestDate)
                HistoryUiState.Loaded(response.games)
            } catch (e: Exception) {
                HistoryUiState.Error(e.message ?: "Couldn't reach the backend")
            }
        }
    }

    fun retry() = load(selectedPreset)
}

private fun startDateFor(preset: HistoryRangePreset, today: LocalDate): LocalDate = when (preset) {
    HistoryRangePreset.LAST_30_DAYS -> today.minusDays(30)
    HistoryRangePreset.LAST_3_MONTHS -> today.minusMonths(3)
    HistoryRangePreset.THIS_SEASON -> currentSeasonStart(today)
    // Comfortably before the backfill's own earliest date - the server
    // clamps this to whatever that actually is (historyService.ts).
    HistoryRangePreset.ALL_TIME -> LocalDate.of(2000, 1, 1)
}

// Matches backfillHistoricalWatchability.ts's own "ends in" season convention
// (a season starting this Oct or last Oct, whichever is more recent).
private fun currentSeasonStart(today: LocalDate): LocalDate {
    val octoberFirstThisYear = LocalDate.of(today.year, 10, 1)
    return if (!today.isBefore(octoberFirstThisYear)) octoberFirstThisYear else LocalDate.of(today.year - 1, 10, 1)
}
