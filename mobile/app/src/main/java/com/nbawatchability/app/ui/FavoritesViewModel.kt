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

const val MAX_FAVORITE_TEAMS = 5
const val MAX_FAVORITE_PLAYERS = 10
// Player Hater Mode easter egg - global cap (not per-league like the two
// above), James's specific call: up to 10 hated players total, across every
// league at once.
const val MAX_HATED_PLAYERS = 10

class FavoritesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = FavoritesRepository(application.applicationContext)

    var favoriteTeams by mutableStateOf<List<Team>>(emptyList())
        private set

    // True once the very first DataStore emission (even an empty list) has
    // arrived - lets the Favorites tab distinguish "still loading" from "genuinely no
    // favorites yet" instead of flashing an empty state first.
    var isLoaded by mutableStateOf(false)
        private set

    var favoritePlayers by mutableStateOf<List<FavoritePlayer>>(emptyList())
        private set

    var hatedPlayers by mutableStateOf<List<FavoritePlayer>>(emptyList())
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
        viewModelScope.launch {
            repository.hatedPlayers.collect { hatedPlayers = it }
        }
    }

    fun isFavoriteTeam(name: String): Boolean = favoriteTeams.any { it.name == name }

    fun isFavoritePlayer(name: String): Boolean = favoritePlayers.any { it.name == name }

    fun isHatedPlayer(name: String): Boolean = hatedPlayers.any { it.name == name }

    /**
     * Adds [team] if not already favorited, removes it otherwise - the same
     * toggle shape used everywhere else in this app (starring, league
     * enabling). A plain Toast (not a Compose overlay) carries the cap-reached
     * message specifically because this is reachable from a long-press on any
     * team logo across several different screens (Games, Starred, History,
     * Leaders) - a Toast needs no per-screen overlay wiring to show up
     * correctly regardless of which one triggered it.
     *
     * The cap is per-league (per James' request), not global - up to
     * MAX_FAVORITE_TEAMS in NBA *and* MAX_FAVORITE_TEAMS in WNBA *and* ...,
     * counted by matching [team]'s own leagueGroup against every other
     * currently-favorited team's, not the total list size.
     */
    fun toggleFavoriteTeam(team: Team) {
        val alreadyFavorited = isFavoriteTeam(team.name)
        if (!alreadyFavorited) {
            val countInSameLeague = favoriteTeams.count { it.leagueGroup == team.leagueGroup }
            if (countInSameLeague >= MAX_FAVORITE_TEAMS) {
                Toast.makeText(getApplication(), "Up to $MAX_FAVORITE_TEAMS favorite teams per league for now", Toast.LENGTH_SHORT).show()
                return
            }
        }
        val updated = if (alreadyFavorited) favoriteTeams.filterNot { it.name == team.name } else favoriteTeams + team
        viewModelScope.launch { repository.setFavoriteTeams(updated) }
    }

    /**
     * Same per-league cap shape as toggleFavoriteTeam above (James' request
     * to match the teams cap change) - up to MAX_FAVORITE_PLAYERS in NBA
     * *and* MAX_FAVORITE_PLAYERS in WNBA *and* ..., counted by matching
     * [player]'s own leagueGroup against every other currently-favorited
     * player's, not the total list size.
     */
    fun toggleFavoritePlayer(player: FavoritePlayer) {
        val alreadyFavorited = isFavoritePlayer(player.name)
        if (!alreadyFavorited) {
            val countInSameLeague = favoritePlayers.count { it.leagueGroup == player.leagueGroup }
            if (countInSameLeague >= MAX_FAVORITE_PLAYERS) {
                Toast.makeText(getApplication(), "Up to $MAX_FAVORITE_PLAYERS favorite players per league for now", Toast.LENGTH_SHORT).show()
                return
            }
        }
        val updated = if (alreadyFavorited) favoritePlayers.filterNot { it.name == player.name } else favoritePlayers + player
        viewModelScope.launch { repository.setFavoritePlayers(updated) }
    }

    /**
     * Player Hater Mode easter egg - independent of toggleFavoritePlayer
     * above (a player can be hated without being favorited, or favorited
     * without being hated). Cap is global (MAX_HATED_PLAYERS), not per-league
     * - unlike favorites, there's no per-league reasoning to preserve here.
     */
    fun toggleHatedPlayer(player: FavoritePlayer) {
        val alreadyHated = isHatedPlayer(player.name)
        if (!alreadyHated && hatedPlayers.size >= MAX_HATED_PLAYERS) {
            Toast.makeText(getApplication(), "Up to $MAX_HATED_PLAYERS players you're not a fan of for now", Toast.LENGTH_SHORT).show()
            return
        }
        val updated = if (alreadyHated) hatedPlayers.filterNot { it.name == player.name } else hatedPlayers + player
        viewModelScope.launch { repository.setHatedPlayers(updated) }
    }
}
