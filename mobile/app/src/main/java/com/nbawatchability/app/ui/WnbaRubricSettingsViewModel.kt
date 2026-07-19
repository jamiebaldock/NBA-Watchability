package com.nbawatchability.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nbawatchability.app.data.RubricCategory
import com.nbawatchability.app.data.RubricWeights
import com.nbawatchability.app.data.WnbaRubricSettingsRepository
import kotlinx.coroutines.launch

/** WNBA's sibling to RubricSettingsViewModel - identical shape, own repository/backing store, so a user can weight margin/clutch/etc. differently for WNBA than for NBA. */
class WnbaRubricSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = WnbaRubricSettingsRepository(application.applicationContext)

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
