package com.nbawatchability.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nbawatchability.app.data.SoccerRubricCategory
import com.nbawatchability.app.data.SoccerRubricSettingsRepository
import com.nbawatchability.app.data.SoccerRubricWeights
import kotlinx.coroutines.launch

/** Soccer's sibling to RubricSettingsViewModel - identical shape, own repository/backing store. */
class SoccerRubricSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SoccerRubricSettingsRepository(application.applicationContext)

    var weights by mutableStateOf(SoccerRubricWeights.DEFAULT)
        private set

    init {
        viewModelScope.launch {
            repository.weights.collect { weights = it }
        }
    }

    fun updateWeight(category: SoccerRubricCategory, value: Float) {
        viewModelScope.launch { repository.setWeight(category, value) }
    }

    fun resetToDefaults() {
        viewModelScope.launch { repository.resetToDefaults() }
    }
}
