package com.nbawatchability.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.soccerRubricSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "soccer_rubric_settings")

enum class SoccerRubricCategory(val key: Preferences.Key<Float>) {
    MARGIN(floatPreferencesKey("margin_weight")),
    TOTAL_GOALS(floatPreferencesKey("total_goals_weight")),
    COMEBACK(floatPreferencesKey("comeback_weight")),
    LATE_DRAMA(floatPreferencesKey("late_drama_weight")),
    STAR(floatPreferencesKey("star_weight")),
    CHANCES(floatPreferencesKey("chances_weight")),
    RED_CARD(floatPreferencesKey("red_card_weight")),
    SAVES(floatPreferencesKey("saves_weight")),
    FREE_KICK_GOAL(floatPreferencesKey("free_kick_goal_weight")),
    PENALTY_MISS(floatPreferencesKey("penalty_miss_weight"))
}

/** Soccer's sibling to RubricSettingsRepository - a separate DataStore file, not shared keys, so the two sports' weight sets never collide. */
class SoccerRubricSettingsRepository(private val context: Context) {

    val weights: Flow<SoccerRubricWeights> = context.soccerRubricSettingsDataStore.data.map { prefs ->
        SoccerRubricWeights(
            margin = prefs[SoccerRubricCategory.MARGIN.key] ?: 1f,
            totalGoals = prefs[SoccerRubricCategory.TOTAL_GOALS.key] ?: 1f,
            comeback = prefs[SoccerRubricCategory.COMEBACK.key] ?: 1f,
            lateDrama = prefs[SoccerRubricCategory.LATE_DRAMA.key] ?: 1f,
            star = prefs[SoccerRubricCategory.STAR.key] ?: 1f,
            chances = prefs[SoccerRubricCategory.CHANCES.key] ?: 1f,
            redCard = prefs[SoccerRubricCategory.RED_CARD.key] ?: 1f,
            saves = prefs[SoccerRubricCategory.SAVES.key] ?: 1f,
            freeKickGoal = prefs[SoccerRubricCategory.FREE_KICK_GOAL.key] ?: 1f,
            penaltyMiss = prefs[SoccerRubricCategory.PENALTY_MISS.key] ?: 1f
        )
    }

    suspend fun setWeight(category: SoccerRubricCategory, value: Float) {
        context.soccerRubricSettingsDataStore.edit { it[category.key] = value }
    }

    suspend fun resetToDefaults() {
        context.soccerRubricSettingsDataStore.edit { it.clear() }
    }
}
