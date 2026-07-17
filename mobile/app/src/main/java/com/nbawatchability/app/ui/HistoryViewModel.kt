package com.nbawatchability.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nbawatchability.app.data.BACKEND_BASE_URL
import com.nbawatchability.app.data.Game
import com.nbawatchability.app.data.LeagueGroup
import com.nbawatchability.app.data.NetworkLeagueContentRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * "This season" and "All time" always exist; [NamedSeason] entries are
 * built dynamically from whatever distinct seasons the backend reports
 * (`HistoryResponse.seasons`), newest first - a season that hasn't been
 * backfilled yet just doesn't have a chip, no code change needed once it is.
 * [NamedSeason.seasonLabel] is "2024-25"-style for NBA but a plain "2024"
 * for WNBA (gameStore.ts's seasonLabelForTipoff) - WNBA never plays across
 * a year boundary, so the label needs no "ends in" convention.
 */
sealed class HistoryRangePreset(val label: String) {
    data object ThisSeason : HistoryRangePreset("This season")
    data class NamedSeason(val seasonLabel: String) : HistoryRangePreset(seasonLabel)
    // Fire emoji flags that this range holds itself to a much higher bar
    // than every other preset (HistoryScreen.kt's ALL_TIME_MIN_SCORE) - not
    // just decoration, since the reader should expect a shorter, more
    // exceptional list here than "This season" or a named season show.
    data object AllTime : HistoryRangePreset("🔥 All time")
}

sealed interface HistoryUiState {
    data object Loading : HistoryUiState
    data class Error(val message: String) : HistoryUiState
    data class Loaded(val games: List<Game>) : HistoryUiState
}

class HistoryViewModel : ViewModel() {

    var uiState by mutableStateOf<HistoryUiState>(HistoryUiState.Loading)
        private set

    var selectedPreset by mutableStateOf<HistoryRangePreset>(HistoryRangePreset.ThisSeason)
        private set

    // Starts as just This season/All time - the named-season chips in
    // between only exist once a response tells us which seasons the
    // backend actually has (HistoryResponse.seasons), so the first load
    // (always "This season", which needs no season list to compute its own
    // range) fills these in for every load after. Rebuilt fresh on every
    // load, so switching league (which always reloads with preset reset to
    // ThisSeason - see AppRoot.kt) naturally replaces NBA's season chips
    // with WNBA's and vice versa, never showing a stale mix.
    var presets by mutableStateOf<List<HistoryRangePreset>>(
        listOf(HistoryRangePreset.ThisSeason, HistoryRangePreset.AllTime)
    )
        private set

    // Server clamps the actual query to this regardless (getHistoryForRange),
    // this is just so the empty-state message can name a real date instead
    // of a vague "try a wider range" - request it once so a repeat "All
    // time" pick without a change doesn't need a fresh response first.
    var earliestDate: LocalDate? = null
        private set

    private var leagueGroup: LeagueGroup = LeagueGroup.NBA

    // Already-fetched presets' games for the current [leagueGroup] only -
    // cleared on every league switch (below), since a league switch always
    // means an entirely different set of games/date ranges under the exact
    // same preset objects (HistoryRangePreset.ThisSeason is a single shared
    // instance across every league, not one per league). Lives only as long
    // as this ViewModel does - no persistence needed, just avoiding
    // redundant refetches within a session, per James's ask.
    private val cache = mutableMapOf<HistoryRangePreset, List<Game>>()

    // Presets with a fetch already in flight, keyed the same way - lets an
    // explicit selection (swipe settle or chip tap) for a preset that's
    // already being prefetched in the background just ride along with that
    // existing request instead of firing a second, redundant one; also
    // covers the reverse (a prefetch request for a preset the user already
    // explicitly jumped to and is loading). Cleared as each request
    // finishes, so a failed/cancelled fetch doesn't permanently block retries.
    private val inFlight = mutableMapOf<HistoryRangePreset, Job>()

    fun load(leagueGroup: LeagueGroup, preset: HistoryRangePreset = selectedPreset) {
        if (leagueGroup != this.leagueGroup) {
            // Every in-flight request (explicit or prefetched) belongs to
            // the league being left - their eventual results are for
            // presets/date-ranges that no longer mean anything under the
            // new league, so both the cache and any pending jobs are
            // dropped rather than risking a stale cross-league write later.
            cache.clear()
            inFlight.values.forEach { it.cancel() }
            inFlight.clear()
        }
        this.leagueGroup = leagueGroup
        selectedPreset = preset

        val cached = cache[preset]
        uiState = if (cached != null) HistoryUiState.Loaded(cached) else HistoryUiState.Loading

        fetch(leagueGroup, preset)
    }

    /**
     * Warms the cache for a preset the user isn't looking at yet (the
     * season adjacent to whichever page the History pager is currently
     * on/settling toward) - a no-op if it's already cached or already being
     * fetched (including by a prior call to this same function), so rapid
     * back-and-forth swiping never piles up duplicate requests for the same
     * preset. Failures aren't surfaced here directly - [fetch]'s own
     * `preset == selectedPreset` check below only shows an error if/when the
     * user actually lands on this preset while it's still failing, which
     * naturally covers both "purely a background prefetch that never got
     * looked at" (silent) and "user swiped here before this prefetch
     * resolved, and it then failed" (a real error, correctly shown) with the
     * same one check - no separate "was this explicitly requested" flag
     * needed, since a fetch already in flight is shared between whoever
     * started it and whoever else asks for the same preset afterward.
     */
    fun prefetch(leagueGroup: LeagueGroup, preset: HistoryRangePreset) {
        if (leagueGroup != this.leagueGroup) return
        fetch(leagueGroup, preset)
    }

    private fun fetch(leagueGroup: LeagueGroup, preset: HistoryRangePreset) {
        if (cache.containsKey(preset) || inFlight.containsKey(preset)) return

        inFlight[preset] = viewModelScope.launch {
            try {
                val today = LocalDate.now()
                val (start, end) = dateRangeFor(preset, today, leagueGroup)
                val response = NetworkLeagueContentRepository.history(
                    baseUrl = BACKEND_BASE_URL,
                    start = start,
                    end = end,
                    leagueGroup = leagueGroup
                )
                // A stale response for a league the user has since switched
                // away from - already-cleared cache/inFlight above means
                // this can only land here if the league changed again
                // *after* this specific request started; either way, this
                // result no longer belongs to anything currently shown.
                if (leagueGroup != this@HistoryViewModel.leagueGroup) return@launch

                cache[preset] = response.games
                // These describe the league as a whole (every preset's
                // response carries the same season list/earliest date), not
                // just this one preset - kept up to date from any
                // successful fetch, prefetch included, same as before this
                // caching change existed.
                earliestDate = LocalDate.parse(response.earliestDate)
                presets = listOf(HistoryRangePreset.ThisSeason) +
                    response.seasons.map { HistoryRangePreset.NamedSeason(it) } +
                    listOf(HistoryRangePreset.AllTime)

                if (preset == selectedPreset) {
                    uiState = HistoryUiState.Loaded(response.games)
                }
            } catch (e: Exception) {
                if (preset == selectedPreset && leagueGroup == this@HistoryViewModel.leagueGroup) {
                    uiState = HistoryUiState.Error(e.message ?: "Couldn't reach the backend")
                }
            } finally {
                inFlight.remove(preset)
            }
        }
    }

    fun retry() = load(leagueGroup, selectedPreset)
}

private suspend fun dateRangeFor(preset: HistoryRangePreset, today: LocalDate, leagueGroup: LeagueGroup): Pair<LocalDate, LocalDate> =
    when (preset) {
        // Whatever's happened since the season boundary below - the current
        // in-progress period, whatever that is right now (mid-season, or the
        // Summer League/preseason gap before the next regular season tips
        // off - that gap now correctly counts as part of "This season" too,
        // per the rule below, rather than being orphaned into the season
        // that already finished).
        is HistoryRangePreset.ThisSeason -> currentSeasonStart(leagueGroup) to today
        is HistoryRangePreset.NamedSeason -> seasonDateRange(preset.seasonLabel, leagueGroup)
        // Comfortably before the backfill's own earliest date - the server
        // clamps this to whatever that actually is (historyService.ts).
        is HistoryRangePreset.AllTime -> LocalDate.of(2000, 1, 1) to today
    }

// Matches gameStore.ts's seasonLabelForTipoff: NBA's "2024-25" runs Oct 1
// (the label's start year) through Sep 30 the following year; WNBA's is
// just the plain calendar year, since a WNBA season never crosses a year
// boundary; EPL/La Liga's "2024-25" runs Aug 1 through Jul 31 the following
// year (soccer's own real season convention - no Finals/playoff stage to
// anchor on, so this is just the calendar-month cutoff gameStore.ts's
// soccerSeasonLabelForTipoff uses). The server clamps the end date to today
// if it's in the future (e.g. the still-forming current season), so
// passing each season's nominal end here is always safe.
private fun seasonDateRange(label: String, leagueGroup: LeagueGroup): Pair<LocalDate, LocalDate> {
    if (leagueGroup == LeagueGroup.WNBA) {
        val year = label.toInt()
        return LocalDate.of(year, 1, 1) to LocalDate.of(year, 12, 31)
    }
    val startYear = label.substringBefore("-").toInt()
    if (leagueGroup == LeagueGroup.EPL || leagueGroup == LeagueGroup.LA_LIGA) {
        return LocalDate.of(startYear, 8, 1) to LocalDate.of(startYear + 1, 7, 31)
    }
    return LocalDate.of(startYear, 10, 1) to LocalDate.of(startYear + 1, 9, 30)
}

/**
 * The real start of "This season" - the day immediately after the most
 * recently completed Finals game (backend's /current-season-start,
 * gameStore.ts's getMostRecentFinalsEnd), not a fixed Oct 1/Apr 1 calendar
 * cutoff. This is what correctly puts NBA Summer League (and any WNBA
 * equivalent gap) under the *new* season instead of the one that just
 * ended - a season boundary is the moment the previous one's Finals
 * conclude, per James's rule, not a calendar date.
 */
private suspend fun currentSeasonStart(leagueGroup: LeagueGroup): LocalDate {
    val response = NetworkLeagueContentRepository.currentSeasonStart(BACKEND_BASE_URL, leagueGroup)
    return LocalDate.parse(response.date)
}
