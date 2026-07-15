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

    // Which league's slate is currently loaded - set by load(), reused by
    // refresh()/fetchGamesForLocalDate() so callers don't need to keep
    // threading it through on every pull-to-refresh.
    private var currentLeagueGroup: LeagueGroup = LeagueGroup.NBA

    // Center of the currently-loaded +/-7 day window - starts at "today" but
    // moves when jumpToNextGame() re-centers the window on a date outside
    // the previously-loaded range (e.g. Summer League ends and the next
    // real game is weeks away). Kept separate from [today] itself, which
    // stays the real device date for "Yesterday"/"Today"/"Tomorrow" labeling
    // regardless of where the window is currently centered.
    private var windowCenter: LocalDate = today

    /** Full reload for [leagueGroup] - called on first composition and again whenever the league selection changes. */
    fun load(leagueGroup: LeagueGroup) {
        currentLeagueGroup = leagueGroup
        windowCenter = today
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

    // Separate from uiState/isRefreshing, same reasoning as isRefreshing:
    // a failed jump (or one that finds nothing) shouldn't blow away whatever
    // empty day the user was already looking at.
    var isJumping by mutableStateOf(false)
        private set
    var jumpError by mutableStateOf<String?>(null)
        private set

    fun clearJumpError() {
        jumpError = null
    }

    /**
     * Finds and jumps to the next date (possibly outside the currently
     * loaded window, possibly weeks away) that has a real scheduled game,
     * starting from whichever day is currently being viewed - the empty day
     * the user landed on, not necessarily "today".
     */
    fun jumpToNextGame() {
        if (isJumping) return
        val loadedState = uiState as? ScheduleUiState.Loaded ?: return
        val fromDate = loadedState.days.getOrNull(selectedDayIndex)?.date ?: return

        isJumping = true
        jumpError = null
        viewModelScope.launch {
            try {
                val nextDate = NetworkGameRepository.nextGameDate(
                    baseUrl = BACKEND_BASE_URL,
                    after = fromDate,
                    leagueGroup = currentLeagueGroup
                )
                if (nextDate == null) {
                    jumpError = "No upcoming games found yet"
                } else {
                    val existingIndex = loadedState.days.indexOfFirst { it.date == nextDate }
                    if (existingIndex >= 0) {
                        selectedDayIndex = existingIndex
                    } else {
                        windowCenter = nextDate
                        val days = fetchSchedule()
                        selectedDayIndex = days.indexOfFirst { it.date == nextDate }.coerceAtLeast(0)
                        uiState = ScheduleUiState.Loaded(days)
                    }
                }
            } catch (e: Exception) {
                jumpError = e.message ?: "Couldn't reach the backend"
            } finally {
                isJumping = false
            }
        }
    }

    // Only meaningful while jumpToToday() has to reload the window (today
    // fell outside it, e.g. after a big jumpToNextGame) - the common case,
    // today still being in the loaded window, resolves synchronously and
    // never touches this.
    var isJumpingToToday by mutableStateOf(false)
        private set

    /**
     * Jumps straight back to today's tab - instant if today is still within
     * the loaded window (just a selectedDayIndex change), otherwise reloads
     * the window centered back on today (mirrors jumpToNextGame's own
     * outside-the-window case, minus the backend lookup, since the target
     * date here is always just [today]).
     */
    fun jumpToToday() {
        if (isJumpingToToday) return
        val loadedState = uiState as? ScheduleUiState.Loaded ?: return
        val existingIndex = loadedState.days.indexOfFirst { it.date == today }
        if (existingIndex >= 0) {
            selectedDayIndex = existingIndex
            return
        }

        windowCenter = today
        isJumpingToToday = true
        viewModelScope.launch {
            try {
                val days = fetchSchedule()
                selectedDayIndex = days.indexOfFirst { it.date == today }.coerceAtLeast(0)
                uiState = ScheduleUiState.Loaded(days)
            } catch (e: Exception) {
                jumpError = e.message ?: "Couldn't reach the backend"
            } finally {
                isJumpingToToday = false
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
            start = windowCenter.minusDays(DISPLAY_RANGE_DAYS + QUERY_BUFFER_DAYS),
            end = windowCenter.plusDays(DISPLAY_RANGE_DAYS + QUERY_BUFFER_DAYS),
            leagueGroup = currentLeagueGroup
        )
        return rebucketByLocalDate(fetched, windowCenter)
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
}

private fun localDateOf(game: Game): LocalDate =
    OffsetDateTime.parse(game.tipoffUtc).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()

private fun rebucketByLocalDate(fetched: List<DayGames>, windowCenter: LocalDate): List<DayGames> {
    val byLocalDate = fetched.flatMap { it.games }.groupBy(::localDateOf)
    val start = windowCenter.minusDays(DISPLAY_RANGE_DAYS)
    val end = windowCenter.plusDays(DISPLAY_RANGE_DAYS)
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
