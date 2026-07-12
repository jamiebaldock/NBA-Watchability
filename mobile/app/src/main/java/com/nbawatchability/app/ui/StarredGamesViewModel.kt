package com.nbawatchability.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nbawatchability.app.data.Game
import com.nbawatchability.app.data.StarredGamesRepository
import kotlinx.coroutines.launch

class StarredGamesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = StarredGamesRepository(application.applicationContext)

    var starredGames by mutableStateOf<List<Game>>(emptyList())
        private set

    // Fast membership check for GameCard's star icon - avoids a linear scan
    // of starredGames per card on every recomposition of a long day's list.
    var starredIds by mutableStateOf<Set<String>>(emptySet())
        private set

    init {
        viewModelScope.launch {
            repository.starredGames.collect {
                starredGames = it
                starredIds = it.map { game -> game.id }.toSet()
            }
        }
    }

    fun toggleStar(game: Game) {
        viewModelScope.launch { repository.toggleStar(game) }
    }
}
