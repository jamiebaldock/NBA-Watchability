package com.nbawatchability.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

private val SELECTED_LEAGUE_KEY = stringPreferencesKey("selected_league")
private val SHOW_NUMERIC_SCORE_KEY = booleanPreferencesKey("show_numeric_score")
private val SHOW_ALL_LEAGUES_IN_STARRED_KEY = booleanPreferencesKey("show_all_leagues_in_starred")

data class AppSettings(
    val selectedLeague: LeagueGroup = LeagueGroup.NBA,
    val showNumericScore: Boolean = false,
    val showAllLeaguesInStarred: Boolean = false
)

/** Persists the last-selected league and Games-tab display prefs (numeric score) - on-device only, so they survive an app restart. */
class AppSettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.appSettingsDataStore.data.map { prefs ->
        AppSettings(
            selectedLeague = when (prefs[SELECTED_LEAGUE_KEY]) {
                LeagueGroup.WNBA.apiValue -> LeagueGroup.WNBA
                else -> LeagueGroup.NBA
            },
            showNumericScore = prefs[SHOW_NUMERIC_SCORE_KEY] ?: false,
            showAllLeaguesInStarred = prefs[SHOW_ALL_LEAGUES_IN_STARRED_KEY] ?: false
        )
    }

    suspend fun setSelectedLeague(league: LeagueGroup) {
        context.appSettingsDataStore.edit { it[SELECTED_LEAGUE_KEY] = league.apiValue }
    }

    suspend fun setShowNumericScore(value: Boolean) {
        context.appSettingsDataStore.edit { it[SHOW_NUMERIC_SCORE_KEY] = value }
    }

    suspend fun setShowAllLeaguesInStarred(value: Boolean) {
        context.appSettingsDataStore.edit { it[SHOW_ALL_LEAGUES_IN_STARRED_KEY] = value }
    }
}
