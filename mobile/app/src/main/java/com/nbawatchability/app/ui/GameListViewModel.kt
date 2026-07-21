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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
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

// The backend's own abuse guard on /schedule (httpHandler.ts's
// MAX_RANGE_DAYS) - a full-season fetch (seasonRange below) has to be split
// into chunks no wider than this and merged, rather than requested in one
// call. The fixed +/-DISPLAY_RANGE_DAYS fallback window already fits in a
// single chunk, so this only ever actually splits anything for a league
// with a full season range loaded.
private const val MAX_FETCH_CHUNK_DAYS = 21L

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

    // Which league(s)' slate is currently loaded - set by load(), reused by
    // refresh()/fetchGamesForLocalDate() so callers don't need to keep
    // threading it through on every pull-to-refresh. More than one entry
    // only when the "All Leagues" dropdown option is active (AppRoot.kt's
    // GamesTab) - each league is fetched/chunked independently and merged,
    // see fetchScheduleChunked below.
    private var currentLeagueGroups: List<LeagueGroup> = listOf(LeagueGroup.NBA)

    // Center of the currently-loaded +/-7 day window - only used when
    // [seasonRange] is null. Starts at "today" but moves when jumpToDate()
    // re-centers the window on a date outside the previously-loaded range.
    // Kept separate from [today] itself, which stays the real device date
    // for "Yesterday"/"Today"/"Tomorrow" labeling regardless of where the
    // window is currently centered.
    private var windowCenter: LocalDate = today

    // The real start-of-season through latest-known-date range, whenever the
    // backend can derive one - null falls back to the fixed
    // +/-DISPLAY_RANGE_DAYS window above. Fetched once per load() for every
    // league (not gated to a specific one - this is meant to be the standard
    // schedule-navigation pattern for any league's Games tab, current or
    // future), not re-queried on every jump (the backend itself caches it
    // once a day, so there's little to gain from asking again more often
    // within a session). NetworkGameRepository.seasonWindow already returns
    // null (not a thrown error) whenever the backend can't derive a window
    // yet, so calling this unconditionally is safe even for a league ESPN
    // doesn't have enough schedule data for right now.
    private var seasonRange by mutableStateOf<Pair<LocalDate, LocalDate>?>(null)

    /**
     * Null until a full-season range loads for the current league - this
     * still only exists to let fetchSchedule() below eagerly load the whole
     * season's day-tabs instead of a narrow +/-7 day window (WNBA/MLB/NFL/
     * NHL/NBA all get this once their own season is real and current). It no
     * longer gates the calendar-picker button itself - that's always shown
     * now (see monthCounts below), since the calendar works for any month of
     * any year regardless of whether a "current season" is loaded.
     */
    val fullSeasonRange: Pair<LocalDate, LocalDate>? get() = seasonRange

    // Counts for whichever single month the calendar dialog currently has
    // open - fetched lazily per visible month (loadMonthCounts below) rather
    // than for the whole year up front, since the backend's own
    // scheduleCountsService.ts is deliberately cheap per month but there's
    // still no reason to fetch months nobody's scrolled to yet. Keyed by the
    // month itself (not just a flat date set) so a stale response from a
    // month the user has since scrolled away from can be told apart from
    // "this month's real counts, still loading."
    var monthCounts by mutableStateOf<Map<LocalDate, Int>>(emptyMap())
        private set
    var monthCountsMonth by mutableStateOf<YearMonth?>(null)
        private set
    var isLoadingMonthCounts by mutableStateOf(false)
        private set

    /**
     * Fetches real per-day game counts for [month] across every league in
     * [leagueGroups] (more than one only in "All Leagues" mode - each
     * league's counts are fetched in parallel and summed per date, same
     * "merge independently" shape as FavoriteGamesViewModel's per-team
     * fetches). Safe to call again for the same month that's already loaded
     * (e.g. the calendar re-opening) - cheap since the backend caches each
     * (league, year, month) once per day itself.
     */
    fun loadMonthCounts(month: YearMonth, leagueGroups: List<LeagueGroup>) {
        isLoadingMonthCounts = true
        viewModelScope.launch {
            try {
                val perLeague = leagueGroups.map { leagueGroup ->
                    async {
                        runCatching {
                            NetworkGameRepository.scheduleCounts(BACKEND_BASE_URL, month.year, month.monthValue, leagueGroup)
                        }.getOrDefault(emptyMap())
                    }
                }.awaitAll()

                val merged = mutableMapOf<LocalDate, Int>()
                for (counts in perLeague) {
                    for ((date, count) in counts) {
                        merged[date] = (merged[date] ?: 0) + count
                    }
                }
                monthCounts = merged
                monthCountsMonth = month
            } catch (e: Exception) {
                // Leave whatever was already loaded (likely the previous
                // month) rather than blanking the calendar over one failed
                // fetch - same "don't discard good state over a transient
                // error" reasoning as refresh() above.
            } finally {
                isLoadingMonthCounts = false
            }
        }
    }

    /**
     * Full reload for [leagueGroups] - called on first composition and
     * again whenever the league selection changes (a single-element list
     * for a normal league pick, more than one when "All Leagues" is
     * active). The season-range/calendar-picker fast path (below) only
     * applies to a single league - merging multiple leagues' own season
     * ranges isn't well-defined, so multi-league mode always falls back to
     * the fixed +/-[DISPLAY_RANGE_DAYS] window instead.
     */
    fun load(leagueGroups: List<LeagueGroup>) {
        currentLeagueGroups = leagueGroups
        windowCenter = today
        seasonRange = null
        uiState = ScheduleUiState.Loading
        viewModelScope.launch {
            uiState = try {
                seasonRange = leagueGroups.singleOrNull()?.let { NetworkGameRepository.seasonWindow(BACKEND_BASE_URL, it) }
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
                // Nearest next game across every currently-loaded league,
                // not just the first - a multi-league "All Leagues" jump
                // should land on whichever league's next game comes first.
                val nextDate = currentLeagueGroups.mapNotNull { league ->
                    NetworkGameRepository.nextGameDate(baseUrl = BACKEND_BASE_URL, after = fromDate, leagueGroup = league)
                }.minOrNull()
                if (nextDate == null) {
                    jumpError = "No upcoming games found yet"
                } else {
                    jumpToDateInternal(nextDate, loadedState) { jumpError = it }
                }
            } catch (e: Exception) {
                jumpError = e.message ?: "Couldn't reach the backend"
            } finally {
                isJumping = false
            }
        }
    }

    // Only meaningful when a jump has to reload the window (the target date
    // fell outside what's currently loaded) - the common case, the date
    // already being in the loaded window, resolves synchronously and never
    // touches this.
    var isJumpingToToday by mutableStateOf(false)
        private set

    /**
     * Jumps straight back to today's tab - instant if today is still within
     * the loaded window (just a selectedDayIndex change), otherwise reloads
     * the window/range to include it.
     */
    fun jumpToToday() {
        if (isJumpingToToday) return
        val loadedState = uiState as? ScheduleUiState.Loaded ?: return
        val existingIndex = loadedState.days.indexOfFirst { it.date == today }
        if (existingIndex >= 0) {
            selectedDayIndex = existingIndex
            return
        }

        isJumpingToToday = true
        viewModelScope.launch {
            try {
                jumpToDateInternal(today, loadedState) { jumpError = it }
            } finally {
                isJumpingToToday = false
            }
        }
    }

    var isJumpingToDate by mutableStateOf(false)
        private set

    /**
     * Jumps to an arbitrary date - backs the calendar picker. With a full
     * season range loaded (WNBA), any in-season date is already present, so
     * this almost always resolves instantly; it only reaches the network
     * for the rare case ESPN has since published dates beyond what was
     * cached at load() time (e.g. playoffs added after the regular season
     * was already loaded).
     */
    fun jumpToDate(date: LocalDate) {
        if (isJumpingToDate) return
        val loadedState = uiState as? ScheduleUiState.Loaded ?: return
        val existingIndex = loadedState.days.indexOfFirst { it.date == date }
        if (existingIndex >= 0) {
            selectedDayIndex = existingIndex
            return
        }

        isJumpingToDate = true
        viewModelScope.launch {
            try {
                jumpToDateInternal(date, loadedState) { jumpError = it }
            } finally {
                isJumpingToDate = false
            }
        }
    }

    private suspend fun jumpToDateInternal(date: LocalDate, loadedState: ScheduleUiState.Loaded, onError: (String) -> Unit) {
        try {
            if (seasonRange == null) windowCenter = date
            val days = fetchSchedule()
            selectedDayIndex = days.indexOfFirst { it.date == date }.coerceAtLeast(0)
            uiState = ScheduleUiState.Loaded(days)
        } catch (e: Exception) {
            onError(e.message ?: "Couldn't reach the backend")
        }
    }

    /**
     * Pull-to-refresh: re-fetches only the currently-viewed day (plus a small
     * buffer so the cross-timezone rebucketing stays correct), not the whole
     * loaded range - there's no reason a refresh of "today" should also
     * re-hit the backend for every other day nobody's looking at. Live
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
        val (start, end) = seasonRange ?: (windowCenter.minusDays(DISPLAY_RANGE_DAYS) to windowCenter.plusDays(DISPLAY_RANGE_DAYS))
        val fetched = fetchScheduleChunked(
            start.minusDays(QUERY_BUFFER_DAYS),
            end.plusDays(QUERY_BUFFER_DAYS),
            currentLeagueGroups
        )
        return rebucketByLocalDate(fetched, start, end)
    }

    // Merges every league in [leagueGroups] into one flat DayGames list -
    // rebucketByLocalDate below flattens every league's games together
    // per date anyway, so there's no need to keep each league's chunks
    // separate past this point.
    private suspend fun fetchScheduleChunked(start: LocalDate, end: LocalDate, leagueGroups: List<LeagueGroup>): List<DayGames> {
        val merged = mutableListOf<DayGames>()
        for (leagueGroup in leagueGroups) {
            var chunkStart = start
            while (!chunkStart.isAfter(end)) {
                val chunkEnd = minOf(chunkStart.plusDays(MAX_FETCH_CHUNK_DAYS - 1), end)
                merged += NetworkGameRepository.schedule(
                    baseUrl = BACKEND_BASE_URL,
                    start = chunkStart,
                    end = chunkEnd,
                    leagueGroup = leagueGroup
                )
                chunkStart = chunkEnd.plusDays(1)
            }
        }
        return merged
    }

    private suspend fun fetchGamesForLocalDate(date: LocalDate): List<Game> {
        val fetched = currentLeagueGroups.flatMap { leagueGroup ->
            NetworkGameRepository.schedule(
                baseUrl = BACKEND_BASE_URL,
                start = date.minusDays(QUERY_BUFFER_DAYS),
                end = date.plusDays(QUERY_BUFFER_DAYS),
                leagueGroup = leagueGroup
            )
        }
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

private fun rebucketByLocalDate(fetched: List<DayGames>, start: LocalDate, end: LocalDate): List<DayGames> {
    val byLocalDate = fetched.flatMap { it.games }.groupBy(::localDateOf)
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
