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

private val SHOW_WNBA_KEY = booleanPreferencesKey("show_wnba")
private val SELECTED_LEAGUE_KEY = stringPreferencesKey("selected_league")

data class AppSettings(
    val showWnba: Boolean = false,
    val selectedLeague: LeagueGroup = LeagueGroup.NBA
) {
    /** OFF always means NBA, regardless of what was last selected while it was ON. */
    val effectiveLeagueGroup: LeagueGroup get() = if (showWnba) selectedLeague else LeagueGroup.NBA
}

/** Persists the WNBA toggle + last-selected league - on-device only. */
class AppSettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.appSettingsDataStore.data.map { prefs ->
        AppSettings(
            showWnba = prefs[SHOW_WNBA_KEY] ?: false,
            selectedLeague = when (prefs[SELECTED_LEAGUE_KEY]) {
                LeagueGroup.WNBA.apiValue -> LeagueGroup.WNBA
                else -> LeagueGroup.NBA
            }
        )
    }

    suspend fun setShowWnba(value: Boolean) {
        context.appSettingsDataStore.edit { it[SHOW_WNBA_KEY] = value }
    }

    suspend fun setSelectedLeague(league: LeagueGroup) {
        context.appSettingsDataStore.edit { it[SELECTED_LEAGUE_KEY] = league.apiValue }
    }
}
