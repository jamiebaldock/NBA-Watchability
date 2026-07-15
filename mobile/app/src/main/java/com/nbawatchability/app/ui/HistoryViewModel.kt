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

/**
 * "This season" and "All time" always exist; [NamedSeason] entries are
 * built dynamically from whatever distinct seasons the backend reports
 * (`HistoryResponse.seasons`), newest first - a season that hasn't been
 * backfilled yet just doesn't have a chip, no code change needed once it is.
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

/**
 * NBA-only for now (the backfill this reads from is NBA-only) - no league
 * group parameter, unlike GameListViewModel/StandingsViewModel/etc.
 */
class HistoryViewModel : ViewModel() {

    var uiState by mutableStateOf<HistoryUiState>(HistoryUiState.Loading)
        private set

    var selectedPreset by mutableStateOf<HistoryRangePreset>(HistoryRangePreset.ThisSeason)
        private set

    // Starts as just This season/All time - the named-season chips in
    // between only exist once a response tells us which seasons the
    // backend actually has (HistoryResponse.seasons), so the first load
    // (always "This season", which needs no season list to compute its own
    // range) fills these in for every load after.
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

    fun load(preset: HistoryRangePreset = selectedPreset) {
        selectedPreset = preset
        uiState = HistoryUiState.Loading
        viewModelScope.launch {
            uiState = try {
                val today = LocalDate.now()
                val (start, end) = dateRangeFor(preset, today)
                val response = NetworkLeagueContentRepository.history(
                    baseUrl = BACKEND_BASE_URL,
                    start = start,
                    end = end
                )
                earliestDate = LocalDate.parse(response.earliestDate)
                presets = listOf(HistoryRangePreset.ThisSeason) +
                    response.seasons.map { HistoryRangePreset.NamedSeason(it) } +
                    listOf(HistoryRangePreset.AllTime)
                HistoryUiState.Loaded(response.games)
            } catch (e: Exception) {
                HistoryUiState.Error(e.message ?: "Couldn't reach the backend")
            }
        }
    }

    fun retry() = load(selectedPreset)
}

private fun dateRangeFor(preset: HistoryRangePreset, today: LocalDate): Pair<LocalDate, LocalDate> = when (preset) {
    // Whatever's happened since the most recent Oct 1 - the current
    // in-progress period, whatever that is right now (mid-season, or the
    // Summer League/preseason gap before the next one tips off).
    is HistoryRangePreset.ThisSeason -> currentSeasonStart(today) to today
    is HistoryRangePreset.NamedSeason -> seasonDateRange(preset.seasonLabel)
    // Comfortably before the backfill's own earliest date - the server
    // clamps this to whatever that actually is (historyService.ts).
    is HistoryRangePreset.AllTime -> LocalDate.of(2000, 1, 1) to today
}

// Matches gameStore.ts's seasonLabelForTipoff - a season runs Oct 1 (of the
// label's start year) through Sep 30 the following year. The server clamps
// this end date to today if it's in the future (e.g. the still-forming
// current season), so passing the season's nominal end here is always safe.
private fun seasonDateRange(label: String): Pair<LocalDate, LocalDate> {
    val startYear = label.substringBefore("-").toInt()
    return LocalDate.of(startYear, 10, 1) to LocalDate.of(startYear + 1, 9, 30)
}

// Same "ends in" season convention as gameStore.ts's seasonLabelForTipoff
// (a season starting this Oct or last Oct, whichever is more recent).
private fun currentSeasonStart(today: LocalDate): LocalDate {
    val octoberFirstThisYear = LocalDate.of(today.year, 10, 1)
    return if (!today.isBefore(octoberFirstThisYear)) octoberFirstThisYear else LocalDate.of(today.year - 1, 10, 1)
}
