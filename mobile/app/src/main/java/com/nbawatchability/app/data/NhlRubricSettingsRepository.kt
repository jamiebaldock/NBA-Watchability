package com.nbawatchability.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.nhlRubricSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "nhl_rubric_settings")

enum class NhlRubricCategory(val key: Preferences.Key<Float>) {
    MARGIN(floatPreferencesKey("margin_weight")),
    COMEBACK(floatPreferencesKey("comeback_weight")),
    LEAD_CHANGES(floatPreferencesKey("lead_changes_weight")),
    OVERTIME(floatPreferencesKey("overtime_weight")),
    DECISIVE_SCORE_LATE(floatPreferencesKey("decisive_score_late_weight")),
    POWER_PLAY(floatPreferencesKey("power_play_weight")),
    STAR(floatPreferencesKey("star_weight")),
    SHUTOUT(floatPreferencesKey("shutout_weight")),
    TOTAL_GOALS(floatPreferencesKey("total_goals_weight")),
    STAKES(floatPreferencesKey("stakes_weight"))
}

/** NHL's sibling to RubricSettingsRepository/MlbRubricSettingsRepository/NflRubricSettingsRepository - a separate DataStore file, not shared keys, so each sport's weight set never collides. */
class NhlRubricSettingsRepository(private val context: Context) {

    val weights: Flow<NhlRubricWeights> = context.nhlRubricSettingsDataStore.data.map { prefs ->
        NhlRubricWeights(
            margin = prefs[NhlRubricCategory.MARGIN.key] ?: 1f,
            comeback = prefs[NhlRubricCategory.COMEBACK.key] ?: 1f,
            leadChanges = prefs[NhlRubricCategory.LEAD_CHANGES.key] ?: 1f,
            overtime = prefs[NhlRubricCategory.OVERTIME.key] ?: 1f,
            decisiveScoreLate = prefs[NhlRubricCategory.DECISIVE_SCORE_LATE.key] ?: 1f,
            powerPlay = prefs[NhlRubricCategory.POWER_PLAY.key] ?: 1f,
            star = prefs[NhlRubricCategory.STAR.key] ?: 1f,
            shutout = prefs[NhlRubricCategory.SHUTOUT.key] ?: 1f,
            totalGoals = prefs[NhlRubricCategory.TOTAL_GOALS.key] ?: 1f,
            stakes = prefs[NhlRubricCategory.STAKES.key] ?: 1f
        )
    }

    suspend fun setWeight(category: NhlRubricCategory, value: Float) {
        context.nhlRubricSettingsDataStore.edit { it[category.key] = value }
    }

    suspend fun resetToDefaults() {
        context.nhlRubricSettingsDataStore.edit { it.clear() }
    }
}
