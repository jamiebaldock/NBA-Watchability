package com.nbawatchability.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.mlbRubricSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "mlb_rubric_settings")

enum class MlbRubricCategory(val key: Preferences.Key<Float>) {
    MARGIN(floatPreferencesKey("margin_weight")),
    WALK_OFF(floatPreferencesKey("walk_off_weight")),
    COMEBACK(floatPreferencesKey("comeback_weight")),
    EXTRA_INNINGS(floatPreferencesKey("extra_innings_weight")),
    TOTAL_RUNS(floatPreferencesKey("total_runs_weight")),
    COMBINED_HOME_RUNS(floatPreferencesKey("combined_home_runs_weight")),
    STAR_HOME_RUN(floatPreferencesKey("star_home_run_weight")),
    PITCHING_DOMINANCE(floatPreferencesKey("pitching_dominance_weight")),
    BLOWN_SAVE(floatPreferencesKey("blown_save_weight")),
    ERRORS(floatPreferencesKey("errors_weight")),
    STAKES(floatPreferencesKey("stakes_weight"))
}

/** MLB's sibling to RubricSettingsRepository - a separate DataStore file, not shared keys, so the two sports' weight sets never collide. */
class MlbRubricSettingsRepository(private val context: Context) {

    val weights: Flow<MlbRubricWeights> = context.mlbRubricSettingsDataStore.data.map { prefs ->
        MlbRubricWeights(
            margin = prefs[MlbRubricCategory.MARGIN.key] ?: 1f,
            walkOff = prefs[MlbRubricCategory.WALK_OFF.key] ?: 1f,
            comeback = prefs[MlbRubricCategory.COMEBACK.key] ?: 1f,
            extraInnings = prefs[MlbRubricCategory.EXTRA_INNINGS.key] ?: 1f,
            totalRuns = prefs[MlbRubricCategory.TOTAL_RUNS.key] ?: 1f,
            combinedHomeRuns = prefs[MlbRubricCategory.COMBINED_HOME_RUNS.key] ?: 1f,
            starHomeRun = prefs[MlbRubricCategory.STAR_HOME_RUN.key] ?: 1f,
            pitchingDominance = prefs[MlbRubricCategory.PITCHING_DOMINANCE.key] ?: 1f,
            blownSave = prefs[MlbRubricCategory.BLOWN_SAVE.key] ?: 1f,
            errors = prefs[MlbRubricCategory.ERRORS.key] ?: 1f,
            stakes = prefs[MlbRubricCategory.STAKES.key] ?: 1f
        )
    }

    suspend fun setWeight(category: MlbRubricCategory, value: Float) {
        context.mlbRubricSettingsDataStore.edit { it[category.key] = value }
    }

    suspend fun resetToDefaults() {
        context.mlbRubricSettingsDataStore.edit { it.clear() }
    }
}
