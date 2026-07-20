package com.nbawatchability.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nbawatchability.app.data.NflRubricCategory
import com.nbawatchability.app.data.NflRubricSettingsRepository
import com.nbawatchability.app.data.NflRubricWeights
import kotlinx.coroutines.launch

/** NFL's sibling to RubricSettingsViewModel/MlbRubricSettingsViewModel - identical shape, own repository/backing store. */
class NflRubricSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = NflRubricSettingsRepository(application.applicationContext)

    var weights by mutableStateOf(NflRubricWeights.DEFAULT)
        private set

    init {
        viewModelScope.launch {
            repository.weights.collect { weights = it }
        }
    }

    fun updateWeight(category: NflRubricCategory, value: Float) {
        viewModelScope.launch { repository.setWeight(category, value) }
    }

    fun resetToDefaults() {
        viewModelScope.launch { repository.resetToDefaults() }
    }
}
