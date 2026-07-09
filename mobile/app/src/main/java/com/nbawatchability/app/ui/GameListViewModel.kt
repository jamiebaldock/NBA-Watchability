package com.nbawatchability.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nbawatchability.app.data.BACKEND_BASE_URL
import com.nbawatchability.app.data.DayGames
import com.nbawatchability.app.data.Game
import com.nbawatchability.app.data.NetworkGameRepository
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

sealed interface ScheduleUiState {
    data object Loading : ScheduleUiState
    data class Error(val message: String) : ScheduleUiState
    data class Loaded(val days: List<DayGames>) : ScheduleUiState
}

private const val DISPLAY_RANGE_DAYS = 7L

// The backend buckets each game under whichever date its scoreboard fetch
// used (ESPN's own US-Eastern-based scoreboard day), not the viewer's local
// calendar date. For anyone outside the Americas that's routinely off by a
// day, so this queries a couple of days of buffer beyond what's displayed
// and re-buckets every game itself by the local date its tipoff actually
// falls on, before slicing back down to the requested display window.
private const val QUERY_BUFFER_DAYS = 2L

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
                val fetched = NetworkGameRepository.schedule(
                    baseUrl = BACKEND_BASE_URL,
                    start = today.minusDays(DISPLAY_RANGE_DAYS + QUERY_BUFFER_DAYS),
                    end = today.plusDays(DISPLAY_RANGE_DAYS + QUERY_BUFFER_DAYS)
                )
                val days = rebucketByLocalDate(fetched, today)
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

private fun localDateOf(game: Game): LocalDate =
    OffsetDateTime.parse(game.tipoffUtc).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()

private fun rebucketByLocalDate(fetched: List<DayGames>, today: LocalDate): List<DayGames> {
    val byLocalDate = fetched.flatMap { it.games }.groupBy(::localDateOf)
    val start = today.minusDays(DISPLAY_RANGE_DAYS)
    val end = today.plusDays(DISPLAY_RANGE_DAYS)
    return generateSequence(start) { it.plusDays(1) }
        .takeWhile { !it.isAfter(end) }
        .map { date ->
            DayGames(
                date = date,
                games = byLocalDate[date].orEmpty().sortedBy { OffsetDateTime.parse(it.tipoffUtc) }
            )
        }
        .toList()
}
