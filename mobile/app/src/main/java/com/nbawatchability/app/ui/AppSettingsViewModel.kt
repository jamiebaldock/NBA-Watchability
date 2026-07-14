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

    // Starts false because [settings] is a hardcoded default (NBA, sort off,
    // etc.) until DataStore's first emission arrives asynchronously - callers
    // must wait for this before reading [settings], otherwise a persisted
    // league/sort/numeric-score choice loses a race against a request already
    // fired for the wrong (default) league on cold start.
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

    fun toggleSortBestFirst() {
        viewModelScope.launch { repository.setSortBestFirst(!settings.sortBestFirst) }
    }

    fun toggleShowNumericScore() {
        viewModelScope.launch { repository.setShowNumericScore(!settings.showNumericScore) }
    }
}
