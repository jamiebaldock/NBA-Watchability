package com.nbawatchability.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nbawatchability.app.data.AppSettings
import com.nbawatchability.app.data.AppSettingsRepository
import com.nbawatchability.app.data.LeagueGroup
import kotlinx.coroutines.launch

class AppSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AppSettingsRepository(application.applicationContext)

    var settings by mutableStateOf(AppSettings())
        private set

    // Starts false because [settings] is a hardcoded default (NBA, numeric
    // score off) until DataStore's first emission arrives asynchronously -
    // callers must wait for this before reading [settings], otherwise a
    // persisted league/numeric-score choice loses a race against a request
    // already fired for the wrong (default) league on cold start.
    var isLoaded by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            repository.settings.collect {
                settings = it
                isLoaded = true
            }
        }
    }

    fun setSelectedLeague(league: LeagueGroup) {
        viewModelScope.launch { repository.setSelectedLeague(league) }
    }

    fun toggleShowNumericScore() {
        viewModelScope.launch { repository.setShowNumericScore(!settings.showNumericScore) }
    }

    fun toggleBumpFavoriteTeamGames() {
        viewModelScope.launch { repository.setBumpFavoriteTeamGames(!settings.bumpFavoriteTeamGames) }
    }

    fun setDefaultLandingTab(tabName: String) {
        viewModelScope.launch { repository.setDefaultLandingTab(tabName) }
    }

    fun toggleHistoryShowScoresByDefault() {
        viewModelScope.launch { repository.setHistoryShowScoresByDefault(!settings.historyShowScoresByDefault) }
    }

    fun toggleMinTierFilterEnabled() {
        viewModelScope.launch { repository.setMinTierFilterEnabled(!settings.minTierFilterEnabled) }
    }

    fun setMinTierFilter(tierName: String) {
        viewModelScope.launch { repository.setMinTierFilter(tierName) }
    }

    fun toggleWifiOnlyHighlights() {
        viewModelScope.launch { repository.setWifiOnlyHighlights(!settings.wifiOnlyHighlights) }
    }

    fun toggleLightTheme() {
        viewModelScope.launch { repository.setLightTheme(!settings.lightTheme) }
    }

    fun setDefaultGameDetailTab(tabName: String) {
        viewModelScope.launch { repository.setDefaultGameDetailTab(tabName) }
    }

    fun setAllLeaguesSelected(value: Boolean) {
        viewModelScope.launch { repository.setAllLeaguesSelected(value) }
    }

    fun togglePlayerHaterMode() {
        viewModelScope.launch { repository.setPlayerHaterMode(!settings.playerHaterMode) }
    }

    /**
     * Picks a single real league, turning "All Leagues" back off - the one
     * place this reset happens, shared by every tab's dropdown
     * (TitleLeagueSelector's per-league menu items all call this same
     * callback), rather than each tab re-implementing "unset All Leagues
     * when a specific league is picked" on its own.
     */
    fun selectLeague(league: LeagueGroup) {
        setAllLeaguesSelected(false)
        setSelectedLeague(league)
    }

    /**
     * Flips [league] in the dropdown's visible set. Two safety rules the
     * Settings toggle UI can't enforce on its own: never let the set go
     * fully empty (would leave the dropdown with nothing to pick), and if
     * the league being turned off is the one currently selected, fall back
     * to NBA (or whatever else remains enabled, if NBA itself was somehow
     * turned off) rather than leaving selectedLeague pointing at a league
     * that no longer shows in its own dropdown.
     */
    fun toggleLeagueEnabled(league: LeagueGroup) {
        val current = settings.enabledLeagues
        val updated = if (league in current) current - league else current + league
        if (updated.isEmpty()) return

        viewModelScope.launch {
            repository.setEnabledLeagues(updated)
            if (settings.selectedLeague !in updated) {
                repository.setSelectedLeague(updated.find { it == LeagueGroup.NBA } ?: updated.first())
            }
        }
    }
}
