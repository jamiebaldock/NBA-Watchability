package com.nbawatchability.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.rubricSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "rubric_settings")

enum class RubricCategory(val key: Preferences.Key<Float>) {
    MARGIN(floatPreferencesKey("margin_weight")),
    CLUTCH(floatPreferencesKey("clutch_weight")),
    BUZZER_BEATER(floatPreferencesKey("buzzer_beater_weight")),
    COMEBACK(floatPreferencesKey("comeback_weight")),
    LEAD_CHANGES(floatPreferencesKey("lead_changes_weight")),
    OVERTIME(floatPreferencesKey("overtime_weight")),
    STAR_PERFORMANCE(floatPreferencesKey("star_performance_weight")),
    STAKES(floatPreferencesKey("stakes_weight"))
}

/** Persists per-device rubric weight overrides - on-device only, never touches the backend. */
class RubricSettingsRepository(private val context: Context) {

    val weights: Flow<RubricWeights> = context.rubricSettingsDataStore.data.map { prefs ->
        RubricWeights(
            margin = prefs[RubricCategory.MARGIN.key] ?: 1f,
            clutch = prefs[RubricCategory.CLUTCH.key] ?: 1f,
            buzzerBeater = prefs[RubricCategory.BUZZER_BEATER.key] ?: 1f,
            comeback = prefs[RubricCategory.COMEBACK.key] ?: 1f,
            leadChanges = prefs[RubricCategory.LEAD_CHANGES.key] ?: 1f,
            overtime = prefs[RubricCategory.OVERTIME.key] ?: 1f,
            starPerformance = prefs[RubricCategory.STAR_PERFORMANCE.key] ?: 1f,
            stakes = prefs[RubricCategory.STAKES.key] ?: 1f
        )
    }

    suspend fun setWeight(category: RubricCategory, value: Float) {
        context.rubricSettingsDataStore.edit { it[category.key] = value }
    }

    suspend fun resetToDefaults() {
        context.rubricSettingsDataStore.edit { it.clear() }
    }
}
