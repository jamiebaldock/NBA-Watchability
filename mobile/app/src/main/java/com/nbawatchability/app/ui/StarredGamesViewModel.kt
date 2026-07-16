package com.nbawatchability.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nbawatchability.app.data.BACKEND_BASE_URL
import com.nbawatchability.app.data.Game
import com.nbawatchability.app.data.LeagueGroup
import com.nbawatchability.app.data.NetworkGameRepository
import com.nbawatchability.app.data.StarredGamesRepository
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

// Matches GameListViewModel's own buffer: the backend buckets games by
// ESPN's US-Eastern scoreboard day, not the viewer's local date, so a plain
// start==end query for a game's local date can miss it. Doesn't matter here
// since games are matched back by id (not by which day they land under),
// but the fetch still needs to cover the real date either way.
private const val QUERY_BUFFER_DAYS = 2L

class StarredGamesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = StarredGamesRepository(application.applicationContext)

    // The persisted snapshot from whenever each game was starred - frozen in
    // time (a live game stays showing its pregame tipoff time forever)
    // unless refreshed against freshById below.
    private var storedGames: List<Game> = emptyList()
    private var freshById: Map<String, Game> = emptyMap()

    var starredGames by mutableStateOf<List<Game>>(emptyList())
        private set

    // Fast membership check for GameCard's star icon - avoids a linear scan
    // of starredGames per card on every recomposition of a long day's list.
    var starredIds by mutableStateOf<Set<String>>(emptySet())
        private set

    var isRefreshing by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            repository.starredGames.collect {
                storedGames = it
                starredIds = it.map { game -> game.id }.toSet()
                recomputeDisplayList()
            }
        }
    }

    fun toggleStar(game: Game) {
        viewModelScope.launch { repository.toggleStar(game) }
    }

    /**
     * There's no backend endpoint to look up a single game by id, so this
     * re-fetches the schedule range each starred game's date falls within
     * (per league group, since NBA/WNBA are separate queries) and swaps in
     * whatever comes back matching by id - the same LIVE/quarter/clock data
     * the Games tab shows, instead of the frozen star-time snapshot. A game
     * outside the fetched range (very old, or a fetch failure) just keeps
     * showing its last-known snapshot rather than disappearing.
     */
    fun refreshLiveData() {
        if (isRefreshing || storedGames.isEmpty()) return
        isRefreshing = true
        viewModelScope.launch {
            try {
                val fresh = mutableMapOf<String, Game>()
                storedGames.groupBy(::leagueGroupOf).forEach { (leagueGroup, games) ->
                    val dates = games.map { localDateOf(it) }
                    val days = NetworkGameRepository.schedule(
                        baseUrl = BACKEND_BASE_URL,
                        start = dates.min().minusDays(QUERY_BUFFER_DAYS),
                        end = dates.max().plusDays(QUERY_BUFFER_DAYS),
                        leagueGroup = leagueGroup
                    )
                    days.flatMap { it.games }.forEach { fresh[it.id] = it }
                }
                freshById = fresh
                recomputeDisplayList()
            } catch (e: Exception) {
                // Keep showing whatever was already displayed - a failed
                // refresh shouldn't blow away the last-known state.
            } finally {
                isRefreshing = false
            }
        }
    }

    private fun recomputeDisplayList() {
        starredGames = storedGames.map { stored -> freshById[stored.id] ?: stored }
    }
}

// NBA Summer League games are unioned into the "nba" LeagueGroup server-side
// (there's no separate SUMMER case), so a starred Summer League game must be
// queried under LeagueGroup.NBA too. Not private - StarredScreen.kt reuses
// this to filter the combined list back down to one league when "all
// leagues" is off, and GameCard.kt's favorite-team long-press quick-add
// uses it to tag which league a team belongs to for the per-league cap.
//
// game.league is only ever "nba"/"wnba"/"summer"/"soccer" - it can't tell
// EPL and La Liga apart on its own, so a soccer game falls back to
// competitionLabel (e.g. "La Liga - Regular Season"/"EPL - Regular
// Season" - soccerGamesService.ts always generates this from the same
// LEAGUE_DISPLAY_NAME map, so the prefix match is reliable, not guessing
// off free text). Previously this always returned LeagueGroup.NBA for any
// non-WNBA game, which silently miscategorized every EPL/La Liga starred
// game (refreshLiveData's groupBy below queried them against the NBA
// schedule endpoint, where they could never be found, so a starred
// soccer game's live score/tier never actually refreshed).
fun leagueGroupOf(game: Game): LeagueGroup = when {
    game.league == "wnba" -> LeagueGroup.WNBA
    game.league == "soccer" && game.competitionLabel?.startsWith("La Liga") == true -> LeagueGroup.LA_LIGA
    game.league == "soccer" -> LeagueGroup.EPL
    else -> LeagueGroup.NBA
}

private fun localDateOf(game: Game): LocalDate =
    OffsetDateTime.parse(game.tipoffUtc).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
