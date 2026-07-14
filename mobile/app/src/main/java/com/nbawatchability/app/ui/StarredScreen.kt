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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nbawatchability.app.data.Game
import com.nbawatchability.app.data.LeagueGroup
import com.nbawatchability.app.data.RubricWeights
import com.nbawatchability.app.data.effectiveScore
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary
import com.nbawatchability.app.ui.theme.TierWorthYourTime

/**
 * Combines NBA and WNBA starred games in one list (most recent tipoff
 * first by default, or best-rated first when toggled), unlike every other
 * tab which is scoped to a single league group - a personal favorites list
 * has no reason to split by league. Sort/numeric-score toggles are the
 * same app-wide preference the Games tab uses, so flipping one affects
 * both. The date-order toggle is Starred-specific (no other tab mixes
 * dates in one list) and purely a local view preference, not persisted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarredScreen(
    games: List<Game>,
    showNumericScore: Boolean,
    onToggleNumericScore: () -> Unit,
    sortBestFirst: Boolean,
    onToggleSort: () -> Unit,
    weights: RubricWeights,
    starredIds: Set<String>,
    onToggleStar: (Game) -> Unit,
    onWatchHighlights: (String) -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    selectedLeague: LeagueGroup,
    onLeagueSelected: (LeagueGroup) -> Unit,
    onSettingsClick: () -> Unit
) {
    var dateAscending by remember { mutableStateOf(false) }
    var actionLabel by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = BackgroundBase,
        topBar = {
            TopAppBar(
                title = { TitleLeagueSelector(selectedLeague, onLeagueSelected) },
                actions = {
                    IconToggleButton(
                        checked = dateAscending,
                        onCheckedChange = {
                            dateAscending = it
                            actionLabel = if (it) "Oldest first" else "Newest first"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.CalendarToday,
                            contentDescription = if (dateAscending) "Oldest game first" else "Newest game first",
                            tint = if (dateAscending) TierWorthYourTime else TextSecondary
                        )
                    }
                    IconToggleButton(
                        checked = sortBestFirst,
                        onCheckedChange = {
                            onToggleSort()
                            actionLabel = if (it) "Sorted by rating" else "Sorted by date"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Sort,
                            contentDescription = "Best first sort",
                            tint = if (sortBestFirst) TierWorthYourTime else TextSecondary
                        )
                    }
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
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = TextSecondary
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
            if (games.isEmpty()) {
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

            fun List<Game>.byDate() = if (dateAscending) sortedBy { it.tipoffUtc } else sortedByDescending { it.tipoffUtc }

            val ordered = if (sortBestFirst) {
                val (scored, unscored) = games.partition { it.effectiveScore(weights) != null }
                scored.sortedByDescending { it.effectiveScore(weights) } + unscored.byDate()
            } else {
                games.byDate()
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
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
                        showDate = true
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
