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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.sp
import com.nbawatchability.app.data.Game
import com.nbawatchability.app.data.LeagueGroup
import com.nbawatchability.app.data.RubricWeights
import com.nbawatchability.app.data.Team
import com.nbawatchability.app.data.bumpFavoriteTeamGames
import com.nbawatchability.app.data.effectiveScore
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary
import com.nbawatchability.app.ui.theme.TierWorthYourTime

/**
 * Scoped to [selectedLeague] by default, same as every other tab - but
 * unlike every other tab, this scoping can be turned off entirely (Settings'
 * "Show all leagues in Starred"), since a personal favorites list arguably
 * has less reason to split by league than a live schedule does. When that's
 * on, [showAllLeagues] is true, the top bar shows "All Leagues" instead of
 * the normal tappable league selector, and the full combined [games] list is
 * shown unfiltered - tapping "All Leagues" surfaces a quick explanation +
 * toggle-off rather than sending the user to Settings. Sort order is a local
 * view preference (not persisted) covering all 4 combinations (date/rating
 * x asc/desc) via a single dropdown (SortMenuButton), not two independent
 * toggles.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarredScreen(
    games: List<Game>,
    showNumericScore: Boolean,
    onToggleNumericScore: () -> Unit,
    weights: RubricWeights,
    starredIds: Set<String>,
    onToggleStar: (Game) -> Unit,
    onWatchHighlights: (String) -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    selectedLeague: LeagueGroup,
    onLeagueSelected: (LeagueGroup) -> Unit,
    enabledLeagues: Set<LeagueGroup>,
    showAllLeagues: Boolean,
    onToggleAllLeagues: () -> Unit,
    favoriteTeamNames: Set<String> = emptySet(),
    bumpFavoriteTeamGames: Boolean = false,
    onToggleFavoriteTeam: (Team) -> Unit = {},
    favoritePlayerNames: Set<String> = emptySet()
) {
    var sortOption by remember { mutableStateOf(SortOption.DATE_NEWEST_FIRST) }
    var actionLabel by remember { mutableStateOf<String?>(null) }
    var showAllLeaguesInfo by remember { mutableStateOf(false) }

    val visibleGames = if (showAllLeagues) games else games.filter { leagueGroupOf(it) == selectedLeague }

    Scaffold(
        containerColor = BackgroundBase,
        topBar = {
            TopAppBar(
                title = {
                    if (showAllLeagues) {
                        Text(
                            text = "All Leagues",
                            color = TextPrimary,
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp),
                            modifier = Modifier.clickable { showAllLeaguesInfo = true }
                        )
                    } else {
                        TitleLeagueSelector(selectedLeague, onLeagueSelected, enabledLeagues)
                    }
                },
                actions = {
                    SortMenuButton(
                        selected = sortOption,
                        onSelected = { sortOption = it }
                    )
                    IconToggleButton(
                        checked = showNumericScore,
                        onCheckedChange = {
                            onToggleNumericScore()
                            actionLabel = if (it) "Showing numeric score" else "Hiding numeric score"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tag,
                            contentDescription = "Show numeric score",
                            tint = if (showNumericScore) TierWorthYourTime else TextSecondary
                        )
                    }
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
                    val (scored, unscored) = visibleGames.partition { it.effectiveScore(weights) != null }
                    scored.sortedByDescending { it.effectiveScore(weights) } + unscored.sortedByDescending { it.tipoffUtc }
                }
                SortOption.RATING_LOWEST_FIRST -> {
                    val (scored, unscored) = visibleGames.partition { it.effectiveScore(weights) != null }
                    scored.sortedBy { it.effectiveScore(weights) } + unscored.sortedByDescending { it.tipoffUtc }
                }
                SortOption.DATE_OLDEST_FIRST -> visibleGames.sortedBy { it.tipoffUtc }
                SortOption.DATE_NEWEST_FIRST -> visibleGames.sortedByDescending { it.tipoffUtc }
            }.bumpFavoriteTeamGames(bumpFavoriteTeamGames, favoriteTeamNames)

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
                        weights = weights,
                        isStarred = starredIds.contains(game.id),
                        onToggleStar = { onToggleStar(game) },
                        onWatchHighlights = onWatchHighlights,
                        showDate = true,
                        favoriteTeamNames = favoriteTeamNames,
                        onToggleFavoriteTeam = onToggleFavoriteTeam,
                        favoritePlayerNames = favoritePlayerNames
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

    if (showAllLeaguesInfo) {
        AlertDialog(
            onDismissRequest = { showAllLeaguesInfo = false },
            title = { Text("All Leagues is turned on") },
            text = { Text("Starred is showing starred games from every league, ignoring the league dropdown. Turn this off in Settings to go back to filtering by a single league.") },
            confirmButton = {
                TextButton(onClick = {
                    onToggleAllLeagues()
                    showAllLeaguesInfo = false
                }) { Text("Turn off") }
            },
            dismissButton = {
                TextButton(onClick = { showAllLeaguesInfo = false }) { Text("Close") }
            }
        )
    }
}
