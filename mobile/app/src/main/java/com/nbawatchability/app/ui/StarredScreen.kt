package com.nbawatchability.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nbawatchability.app.data.Game
import com.nbawatchability.app.data.RubricWeights
import com.nbawatchability.app.data.effectiveScore
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary
import com.nbawatchability.app.ui.theme.TierWorthYourTime

/**
 * Combines NBA and WNBA starred games in one list (most recent tipoff
 * first, or best-rated first when toggled), unlike every other tab which
 * is scoped to a single league group - a personal favorites list has no
 * reason to split by league. Sort/numeric-score toggles are the same
 * app-wide preference the Games tab uses, so flipping one affects both.
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
    onWatchHighlights: (String) -> Unit
) {
    Scaffold(
        containerColor = BackgroundBase,
        topBar = {
            TopAppBar(
                title = { Text("Starred", color = TextPrimary) },
                actions = {
                    IconToggleButton(checked = sortBestFirst, onCheckedChange = { onToggleSort() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Sort,
                            contentDescription = "Best first sort",
                            tint = if (sortBestFirst) TierWorthYourTime else TextSecondary
                        )
                    }
                    IconToggleButton(checked = showNumericScore, onCheckedChange = { onToggleNumericScore() }) {
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
        if (games.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
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
            return@Scaffold
        }

        val ordered = if (sortBestFirst) {
            val (scored, unscored) = games.partition { it.effectiveScore(weights) != null }
            scored.sortedByDescending { it.effectiveScore(weights) } + unscored.sortedByDescending { it.tipoffUtc }
        } else {
            games.sortedByDescending { it.tipoffUtc }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
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
}
