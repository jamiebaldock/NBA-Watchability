package com.nbawatchability.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nbawatchability.app.data.RubricCategory
import com.nbawatchability.app.data.RubricSettingsRepository
import com.nbawatchability.app.data.RubricWeights
import kotlinx.coroutines.launch

class RubricSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = RubricSettingsRepository(application.applicationContext)

    var weights by mutableStateOf(RubricWeights.DEFAULT)
        private set

    init {
        viewModelScope.launch {
            repository.weights.collect { weights = it }
        }
    }

    fun updateWeight(category: RubricCategory, value: Float) {
        viewModelScope.launch { repository.setWeight(category, value) }
    }

    fun resetToDefaults() {
        viewModelScope.launch { repository.resetToDefaults() }
    }
}
