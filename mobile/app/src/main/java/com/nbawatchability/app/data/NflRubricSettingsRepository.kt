package com.nbawatchability.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.nflRubricSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "nfl_rubric_settings")

enum class NflRubricCategory(val key: Preferences.Key<Float>) {
    MARGIN(floatPreferencesKey("margin_weight")),
    COMEBACK(floatPreferencesKey("comeback_weight")),
    LEAD_CHANGES(floatPreferencesKey("lead_changes_weight")),
    OVERTIME(floatPreferencesKey("overtime_weight")),
    DECISIVE_SCORE_LATE(floatPreferencesKey("decisive_score_late_weight")),
    TURNOVERS(floatPreferencesKey("turnovers_weight")),
    DEFENSIVE_OR_SPECIAL_TEAMS_TD(floatPreferencesKey("defensive_or_special_teams_td_weight")),
    STAR(floatPreferencesKey("star_weight")),
    TOTAL_POINTS(floatPreferencesKey("total_points_weight")),
    STAKES(floatPreferencesKey("stakes_weight"))
}

/** NFL's sibling to RubricSettingsRepository/MlbRubricSettingsRepository - a separate DataStore file, not shared keys, so each sport's weight set never collides. */
class NflRubricSettingsRepository(private val context: Context) {

    val weights: Flow<NflRubricWeights> = context.nflRubricSettingsDataStore.data.map { prefs ->
        NflRubricWeights(
            margin = prefs[NflRubricCategory.MARGIN.key] ?: 1f,
            comeback = prefs[NflRubricCategory.COMEBACK.key] ?: 1f,
            leadChanges = prefs[NflRubricCategory.LEAD_CHANGES.key] ?: 1f,
            overtime = prefs[NflRubricCategory.OVERTIME.key] ?: 1f,
            decisiveScoreLate = prefs[NflRubricCategory.DECISIVE_SCORE_LATE.key] ?: 1f,
            turnovers = prefs[NflRubricCategory.TURNOVERS.key] ?: 1f,
            defensiveOrSpecialTeamsTd = prefs[NflRubricCategory.DEFENSIVE_OR_SPECIAL_TEAMS_TD.key] ?: 1f,
            star = prefs[NflRubricCategory.STAR.key] ?: 1f,
            totalPoints = prefs[NflRubricCategory.TOTAL_POINTS.key] ?: 1f,
            stakes = prefs[NflRubricCategory.STAKES.key] ?: 1f
        )
    }

    suspend fun setWeight(category: NflRubricCategory, value: Float) {
        context.nflRubricSettingsDataStore.edit { it[category.key] = value }
    }

    suspend fun resetToDefaults() {
        context.nflRubricSettingsDataStore.edit { it.clear() }
    }
}
