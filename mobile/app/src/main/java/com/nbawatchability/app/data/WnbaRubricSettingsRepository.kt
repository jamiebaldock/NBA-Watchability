package com.nbawatchability.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.wnbaRubricSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "wnba_rubric_settings")

/**
 * WNBA's sibling to RubricSettingsRepository - a separate DataStore file (and
 * its own independent set of weight values) so NBA and WNBA weight profiles
 * never collide, even though they reuse the exact same RubricCategory
 * key/RubricWeights shape (both leagues score on the same 8 dimensions -
 * see data/Rubric.kt's per-league point-bracket math). RubricSettingsRepository
 * itself was NOT renamed to "Nba..." - it's kept as-is and simply reinterpreted
 * as the NBA profile, so any user who already customized basketball weights
 * before this split keeps their customization as their NBA profile rather
 * than losing it.
 */
class WnbaRubricSettingsRepository(private val context: Context) {

    val weights: Flow<RubricWeights> = context.wnbaRubricSettingsDataStore.data.map { prefs ->
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
        context.wnbaRubricSettingsDataStore.edit { it[category.key] = value }
    }

    suspend fun resetToDefaults() {
        context.wnbaRubricSettingsDataStore.edit { it.clear() }
    }
}
