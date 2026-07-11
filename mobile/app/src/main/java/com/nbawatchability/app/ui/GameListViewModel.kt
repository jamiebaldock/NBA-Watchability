package com.nbawatchability.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nbawatchability.app.data.BACKEND_BASE_URL
import com.nbawatchability.app.data.DayGames
import com.nbawatchability.app.data.Game
import com.nbawatchability.app.data.LeagueGroup
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

    // Pull-to-refresh spinner for re-fetching on top of already-loaded data.
    // Kept separate from uiState so a refresh failure doesn't blow away what's
    // already on screen - it just stops spinning and leaves the old data.
    var isRefreshing by mutableStateOf(false)
        private set

    var selectedDayIndex by mutableStateOf(0)
        private set

    var showNumericScore by mutableStateOf(false)
        private set

    var sortBestFirst by mutableStateOf(false)
        private set

    // Which league's slate is currently loaded - set by load(), reused by
    // refresh()/fetchGamesForLocalDate() so callers don't need to keep
    // threading it through on every pull-to-refresh.
    private var currentLeagueGroup: LeagueGroup = LeagueGroup.NBA

    /** Full reload for [leagueGroup] - called on first composition and again whenever the league selection changes. */
    fun load(leagueGroup: LeagueGroup) {
        currentLeagueGroup = leagueGroup
        uiState = ScheduleUiState.Loading
        viewModelScope.launch {
            uiState = try {
                val days = fetchSchedule()
                selectedDayIndex = days.indexOfFirst { it.date == today }.coerceAtLeast(0)
                ScheduleUiState.Loaded(days)
            } catch (e: Exception) {
                ScheduleUiState.Error(e.message ?: "Couldn't reach the backend")
            }
        }
    }

    /**
     * Pull-to-refresh: re-fetches only the currently-viewed day (plus a small
     * buffer so the cross-timezone rebucketing stays correct), not the whole
     * +/-9 day window - there's no reason a refresh of "today" should also
     * re-hit the backend for the other 18 days nobody's looking at. Live
     * games are never cached stale server-side, so this alone is enough to
     * pick up score/period/clock updates for the visible day. Leaves the
     * current view untouched on failure rather than replacing it with an
     * error screen.
     */
    fun refresh() {
        if (isRefreshing) return
        val loadedState = uiState as? ScheduleUiState.Loaded ?: return
        val targetDate = loadedState.days.getOrNull(selectedDayIndex)?.date ?: return

        isRefreshing = true
        viewModelScope.launch {
            try {
                val refreshedGames = fetchGamesForLocalDate(targetDate)
                val updatedDays = loadedState.days.map { day ->
                    if (day.date == targetDate) day.copy(games = refreshedGames) else day
                }
                uiState = ScheduleUiState.Loaded(updatedDays)
            } catch (e: Exception) {
                // Swallow it: keep showing whatever was already loaded, the
                // spinner stopping is signal enough that it didn't land.
            } finally {
                isRefreshing = false
            }
        }
    }

    private suspend fun fetchSchedule(): List<DayGames> {
        val fetched = NetworkGameRepository.schedule(
            baseUrl = BACKEND_BASE_URL,
            start = today.minusDays(DISPLAY_RANGE_DAYS + QUERY_BUFFER_DAYS),
            end = today.plusDays(DISPLAY_RANGE_DAYS + QUERY_BUFFER_DAYS),
            leagueGroup = currentLeagueGroup
        )
        return rebucketByLocalDate(fetched, today)
    }

    private suspend fun fetchGamesForLocalDate(date: LocalDate): List<Game> {
        val fetched = NetworkGameRepository.schedule(
            baseUrl = BACKEND_BASE_URL,
            start = date.minusDays(QUERY_BUFFER_DAYS),
            end = date.plusDays(QUERY_BUFFER_DAYS),
            leagueGroup = currentLeagueGroup
        )
        return fetched.flatMap { it.games }
            .filter { localDateOf(it) == date }
            .sortedBy { OffsetDateTime.parse(it.tipoffUtc) }
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
