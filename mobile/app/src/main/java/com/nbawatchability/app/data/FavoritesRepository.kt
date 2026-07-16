package com.nbawatchability.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.favoritesDataStore: DataStore<Preferences> by preferencesDataStore(name = "favorites")

private val FAVORITE_TEAMS_KEY = stringPreferencesKey("favorite_teams_json")
private val FAVORITE_PLAYERS_KEY = stringPreferencesKey("favorite_players_json")
private val json = Json { ignoreUnknownKeys = true }

/**
 * Favorite teams and players, global across every league (not a per-league
 * list) - a personal preference, on-device only, same as
 * StarredGamesRepository. The full Team snapshot (name + logo) is
 * persisted, not just the name, so My Teams can render immediately without
 * a fresh network round-trip - the logo shown there could go stale if ESPN
 * ever changes a crest, an acceptable tradeoff for a favorites list capped
 * at 3. Favorite players are stored the same way (name + team), so the
 * standout-performance callout (GameCard.kt) never needs a network call to
 * check "is this player one of mine."
 */
class FavoritesRepository(private val context: Context) {

    val favoriteTeams: Flow<List<Team>> = context.favoritesDataStore.data.map { prefs ->
        val raw = prefs[FAVORITE_TEAMS_KEY] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<Team>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun setFavoriteTeams(teams: List<Team>) {
        context.favoritesDataStore.edit { prefs -> prefs[FAVORITE_TEAMS_KEY] = json.encodeToString(teams) }
    }

    val favoritePlayers: Flow<List<FavoritePlayer>> = context.favoritesDataStore.data.map { prefs ->
        val raw = prefs[FAVORITE_PLAYERS_KEY] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<FavoritePlayer>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun setFavoritePlayers(players: List<FavoritePlayer>) {
        context.favoritesDataStore.edit { prefs -> prefs[FAVORITE_PLAYERS_KEY] = json.encodeToString(players) }
    }
}
