package com.nbawatchability.app.ui

import android.app.Application
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nbawatchability.app.data.FavoritePlayer
import com.nbawatchability.app.data.FavoritesRepository
import com.nbawatchability.app.data.Team
import kotlinx.coroutines.launch

const val MAX_FAVORITE_TEAMS = 3
const val MAX_FAVORITE_PLAYERS = 3

class FavoritesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = FavoritesRepository(application.applicationContext)

    var favoriteTeams by mutableStateOf<List<Team>>(emptyList())
        private set

    // True once the very first DataStore emission (even an empty list) has
    // arrived - lets My Teams distinguish "still loading" from "genuinely no
    // favorites yet" instead of flashing an empty state first.
    var isLoaded by mutableStateOf(false)
        private set

    var favoritePlayers by mutableStateOf<List<FavoritePlayer>>(emptyList())
        private set

    init {
        viewModelScope.launch {
            repository.favoriteTeams.collect {
                favoriteTeams = it
                isLoaded = true
            }
        }
        viewModelScope.launch {
            repository.favoritePlayers.collect { favoritePlayers = it }
        }
    }

    fun isFavoriteTeam(name: String): Boolean = favoriteTeams.any { it.name == name }

    fun isFavoritePlayer(name: String): Boolean = favoritePlayers.any { it.name == name }

    /**
     * Adds [team] if not already favorited, removes it otherwise - the same
     * toggle shape used everywhere else in this app (starring, league
     * enabling). A plain Toast (not a Compose overlay) carries the cap-reached
     * message specifically because this is reachable from a long-press on any
     * team logo across several different screens (Games, Starred, History,
     * Leaders) - a Toast needs no per-screen overlay wiring to show up
     * correctly regardless of which one triggered it.
     */
    fun toggleFavoriteTeam(team: Team) {
        val alreadyFavorited = isFavoriteTeam(team.name)
        if (!alreadyFavorited && favoriteTeams.size >= MAX_FAVORITE_TEAMS) {
            Toast.makeText(getApplication(), "Up to $MAX_FAVORITE_TEAMS favorite teams for now", Toast.LENGTH_SHORT).show()
            return
        }
        val updated = if (alreadyFavorited) favoriteTeams.filterNot { it.name == team.name } else favoriteTeams + team
        viewModelScope.launch { repository.setFavoriteTeams(updated) }
    }

    /** Same toggle/cap shape as toggleFavoriteTeam above, for the player search/browse screen. */
    fun toggleFavoritePlayer(player: FavoritePlayer) {
        val alreadyFavorited = isFavoritePlayer(player.name)
        if (!alreadyFavorited && favoritePlayers.size >= MAX_FAVORITE_PLAYERS) {
            Toast.makeText(getApplication(), "Up to $MAX_FAVORITE_PLAYERS favorite players for now", Toast.LENGTH_SHORT).show()
            return
        }
        val updated = if (alreadyFavorited) favoritePlayers.filterNot { it.name == player.name } else favoritePlayers + player
        viewModelScope.launch { repository.setFavoritePlayers(updated) }
    }
}
