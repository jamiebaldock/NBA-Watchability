package com.nbawatchability.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nbawatchability.app.data.MlbRubricCategory
import com.nbawatchability.app.data.MlbRubricSettingsRepository
import com.nbawatchability.app.data.MlbRubricWeights
import kotlinx.coroutines.launch

/** MLB's sibling to RubricSettingsViewModel - identical shape, own repository/backing store. */
class MlbRubricSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MlbRubricSettingsRepository(application.applicationContext)

    var weights by mutableStateOf(MlbRubricWeights.DEFAULT)
        private set

    init {
        viewModelScope.launch {
            repository.weights.collect { weights = it }
        }
    }

    fun updateWeight(category: MlbRubricCategory, value: Float) {
        viewModelScope.launch { repository.setWeight(category, value) }
    }

    fun resetToDefaults() {
        viewModelScope.launch { repository.resetToDefaults() }
    }
}
