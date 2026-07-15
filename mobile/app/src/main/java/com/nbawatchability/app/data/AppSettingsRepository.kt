package com.nbawatchability.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

private val SELECTED_LEAGUE_KEY = stringPreferencesKey("selected_league")
private val SHOW_NUMERIC_SCORE_KEY = booleanPreferencesKey("show_numeric_score")
private val SHOW_ALL_LEAGUES_IN_STARRED_KEY = booleanPreferencesKey("show_all_leagues_in_starred")
private val ENABLED_LEAGUES_KEY = stringSetPreferencesKey("enabled_leagues")

// Every league ships enabled by default, including the four not-yet-built
// placeholders - Settings' "Selected Sports" section is what a user reaches
// for to hide ones they don't care about, not something they need to opt
// into first just to see NBA/WNBA in the dropdown as before.
private val DEFAULT_ENABLED_LEAGUES = LeagueGroup.entries.map { it.apiValue }.toSet()

data class AppSettings(
    val selectedLeague: LeagueGroup = LeagueGroup.NBA,
    val showNumericScore: Boolean = false,
    val showAllLeaguesInStarred: Boolean = false,
    val enabledLeagues: Set<LeagueGroup> = LeagueGroup.entries.toSet()
)

/** Persists the last-selected league, which leagues show in the dropdown, and Games-tab display prefs (numeric score) - on-device only, so they survive an app restart. */
class AppSettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.appSettingsDataStore.data.map { prefs ->
        val enabledApiValues = prefs[ENABLED_LEAGUES_KEY] ?: DEFAULT_ENABLED_LEAGUES
        AppSettings(
            selectedLeague = LeagueGroup.entries.find { it.apiValue == prefs[SELECTED_LEAGUE_KEY] } ?: LeagueGroup.NBA,
            showNumericScore = prefs[SHOW_NUMERIC_SCORE_KEY] ?: false,
            showAllLeaguesInStarred = prefs[SHOW_ALL_LEAGUES_IN_STARRED_KEY] ?: false,
            enabledLeagues = LeagueGroup.entries.filter { it.apiValue in enabledApiValues }.toSet()
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

    suspend fun setEnabledLeagues(leagues: Set<LeagueGroup>) {
        context.appSettingsDataStore.edit { it[ENABLED_LEAGUES_KEY] = leagues.map { league -> league.apiValue }.toSet() }
    }
}
