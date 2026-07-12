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

private val Context.starredGamesDataStore: DataStore<Preferences> by preferencesDataStore(name = "starred_games")

private val STARRED_GAMES_KEY = stringPreferencesKey("starred_games_json")
private val json = Json { ignoreUnknownKeys = true }

/**
 * Persists the full snapshot of each starred game (not just its id) so the
 * Starred tab can show it standalone - no backend endpoint exists to look up
 * an arbitrary game by id outside its own date's schedule request. A starred
 * live game's score can go stale until the user revisits its date in the
 * Games tab, which is an acceptable tradeoff for a personal favorites list.
 */
class StarredGamesRepository(private val context: Context) {

    val starredGames: Flow<List<Game>> = context.starredGamesDataStore.data.map { prefs ->
        val raw = prefs[STARRED_GAMES_KEY] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<Game>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun toggleStar(game: Game) {
        context.starredGamesDataStore.edit { prefs ->
            val current = prefs[STARRED_GAMES_KEY]
                ?.let { runCatching { json.decodeFromString<List<Game>>(it) }.getOrDefault(emptyList()) }
                ?: emptyList()
            val updated = if (current.any { it.id == game.id }) {
                current.filterNot { it.id == game.id }
            } else {
                current + game
            }
            prefs[STARRED_GAMES_KEY] = json.encodeToString(updated)
        }
    }
}
