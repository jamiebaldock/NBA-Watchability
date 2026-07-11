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

    init {
        viewModelScope.launch {
            repository.settings.collect { settings = it }
        }
    }

    fun setShowWnba(value: Boolean) {
        viewModelScope.launch { repository.setShowWnba(value) }
    }

    fun setSelectedLeague(league: LeagueGroup) {
        viewModelScope.launch { repository.setSelectedLeague(league) }
    }
}
