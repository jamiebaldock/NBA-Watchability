package com.nbawatchability.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nbawatchability.app.data.FavoritePlayer
import com.nbawatchability.app.data.Game
import com.nbawatchability.app.data.LeagueGroup
import com.nbawatchability.app.data.MlbRubricWeights
import com.nbawatchability.app.data.NflRubricWeights
import com.nbawatchability.app.data.NhlRubricWeights
import com.nbawatchability.app.data.RubricWeights
import com.nbawatchability.app.data.Team
import com.nbawatchability.app.data.Tier
import com.nbawatchability.app.data.bumpFavoriteTeamGames
import com.nbawatchability.app.data.effectiveScore
import com.nbawatchability.app.data.filterByMinTier
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary

/**
 * Scoped to [selectedLeague] by default, same as every other tab - can be
 * turned off entirely via the league dropdown's "All Leagues" entry, since a
 * personal favorites list arguably has less reason to split by league than a
 * live schedule does. [isAllLeaguesSelected] is one shared, persisted value
 * (AppSettingsRepository) app-wide across every tab that supports it (Games/
 * Starred/History/Favorites) - previously this was local per-screen state
 * that silently reset every time a tab was freshly entered, which read as a
 * bug when switching tabs mid-session (James's report, 2026-07-20). When
 * it's on, the full combined [games] list is shown unfiltered. Sort order is
 * a local view preference (not persisted) covering all 4 combinations
 * (date/rating x asc/desc) via a single dropdown (SortMenuButton), not two
 * independent toggles.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarredScreen(
    games: List<Game>,
    showNumericScore: Boolean,
    onToggleNumericScore: () -> Unit,
    nbaWeights: RubricWeights,
    wnbaWeights: RubricWeights,
    mlbWeights: MlbRubricWeights,
    nflWeights: NflRubricWeights,
    nhlWeights: NhlRubricWeights,
    starredIds: Set<String>,
    onToggleStar: (Game) -> Unit,
    onWatchHighlights: (String) -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    selectedLeague: LeagueGroup,
    onLeagueSelected: (LeagueGroup) -> Unit,
    isAllLeaguesSelected: Boolean,
    onAllLeaguesSelected: () -> Unit,
    enabledLeagues: Set<LeagueGroup>,
    favoriteTeamNames: Set<String> = emptySet(),
    bumpFavoriteTeamGames: Boolean = false,
    onToggleFavoriteTeam: (Team) -> Unit = {},
    favoritePlayerNames: Set<String> = emptySet(),
    onToggleFavoritePlayer: (FavoritePlayer) -> Unit = {},
    minTierFilterEnabled: Boolean = false,
    minTierFilter: Tier = Tier.SKIPPABLE,
    onGameClick: (Game) -> Unit = {}
) {
    var sortOption by remember { mutableStateOf(SortOption.DATE_NEWEST_FIRST) }
    var actionLabel by remember { mutableStateOf<String?>(null) }

    val visibleGames = if (isAllLeaguesSelected) games else games.filter { leagueGroupOf(it) == selectedLeague }

    Scaffold(
        containerColor = BackgroundBase,
        topBar = {
            AppTopBar(
                leading = {
                    TitleLeagueSelector(
                        selectedLeague = selectedLeague,
                        onLeagueSelected = onLeagueSelected,
                        enabledLeagues = enabledLeagues,
                        isAllLeaguesSelected = isAllLeaguesSelected,
                        onAllLeaguesSelected = onAllLeaguesSelected
                    )
                },
                actions = {
                    SortMenuButton(
                        selected = sortOption,
                        onSelected = { sortOption = it }
                    )
                    NumericScoreToggleButton(
                        checked = showNumericScore,
                        onCheckedChange = {
                            onToggleNumericScore()
                            actionLabel = if (it) "Showing numeric score" else "Hiding numeric score"
                        }
                    )
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize()
        ) {
            if (visibleGames.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "No starred games yet — tap the star on a game to add it here.",
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                return@PullToRefreshBox
            }

            // Rating modes still fall back to newest-first for any unscored
            // (future/live) starred games, since those have no rating to sort by.
            val ordered = when (sortOption) {
                SortOption.RATING_HIGHEST_FIRST -> {
                    val (scored, unscored) = visibleGames.partition { it.effectiveScore(nbaWeights, wnbaWeights, mlbWeights, nflWeights, nhlWeights) != null }
                    scored.sortedByDescending { it.effectiveScore(nbaWeights, wnbaWeights, mlbWeights, nflWeights, nhlWeights) } + unscored.sortedByDescending { it.tipoffUtc }
                }
                SortOption.RATING_LOWEST_FIRST -> {
                    val (scored, unscored) = visibleGames.partition { it.effectiveScore(nbaWeights, wnbaWeights, mlbWeights, nflWeights, nhlWeights) != null }
                    scored.sortedBy { it.effectiveScore(nbaWeights, wnbaWeights, mlbWeights, nflWeights, nhlWeights) } + unscored.sortedByDescending { it.tipoffUtc }
                }
                SortOption.DATE_OLDEST_FIRST -> visibleGames.sortedBy { it.tipoffUtc }
                SortOption.DATE_NEWEST_FIRST -> visibleGames.sortedByDescending { it.tipoffUtc }
            }.filterByMinTier(minTierFilterEnabled, minTierFilter, nbaWeights, wnbaWeights, mlbWeights, nflWeights, nhlWeights)
                .bumpFavoriteTeamGames(bumpFavoriteTeamGames, favoriteTeamNames)

            val listState = rememberLazyListState()
            // Without this, LazyColumn's key-based item tracking keeps
            // whatever tile was on top in view across a re-sort (since it's
            // usually still in the new list, just at a different index) -
            // which can make an already-correct sort look wrong, not just
            // skip the scroll-to-top animation.
            LaunchedEffect(sortOption) { listState.animateScrollToTopAdaptively() }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(ordered, key = { it.id }) { game ->
                    GameCard(
                        game = game,
                        showNumericScore = showNumericScore,
                        nbaWeights = nbaWeights,
                        wnbaWeights = wnbaWeights,
                        mlbWeights = mlbWeights,
                        nflWeights = nflWeights,
                        nhlWeights = nhlWeights,
                        isStarred = starredIds.contains(game.id),
                        onToggleStar = { onToggleStar(game) },
                        onWatchHighlights = onWatchHighlights,
                        showDate = true,
                        favoriteTeamNames = favoriteTeamNames,
                        onToggleFavoriteTeam = onToggleFavoriteTeam,
                        favoritePlayerNames = favoritePlayerNames,
                        onToggleFavoritePlayer = onToggleFavoritePlayer,
                        onGameClick = onGameClick
                    )
                }
            }
        }
        ActionLabelOverlay(
            label = actionLabel,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp)
        )
        }
    }
}
