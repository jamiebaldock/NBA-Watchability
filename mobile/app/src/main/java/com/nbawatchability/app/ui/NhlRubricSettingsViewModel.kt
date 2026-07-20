package com.nbawatchability.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nbawatchability.app.data.NhlRubricCategory
import com.nbawatchability.app.data.NhlRubricSettingsRepository
import com.nbawatchability.app.data.NhlRubricWeights
import kotlinx.coroutines.launch

/** NHL's sibling to RubricSettingsViewModel/MlbRubricSettingsViewModel/NflRubricSettingsViewModel - identical shape, own repository/backing store. */
class NhlRubricSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = NhlRubricSettingsRepository(application.applicationContext)

    var weights by mutableStateOf(NhlRubricWeights.DEFAULT)
        private set

    init {
        viewModelScope.launch {
            repository.weights.collect { weights = it }
        }
    }

    fun updateWeight(category: NhlRubricCategory, value: Float) {
        viewModelScope.launch { repository.setWeight(category, value) }
    }

    fun resetToDefaults() {
        viewModelScope.launch { repository.resetToDefaults() }
    }
}
