package com.nbawatchability.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

private val earliestDateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")

/**
 * "Which old games are actually worth going back to watch" - surfaces the
 * NBA watchability backfill, games scoring 70+ only (gameStore.ts's
 * HISTORY_MIN_SCORE - stricter than the "Worth Your Time" tier badge's own
 * >=65), most-watchable-first by default. Unlike every
 * other tab, these games are ones the viewer is intentionally browsing
 * rather than following live, so the breakdown is never spoiler-blurred
 * (GameCard's spoilerFree = true) - the tier/score/final result are the
 * point, not something to hide. [showScore] is a separate, purely local
 * "browse blind" preference - unlike spoilerFree, turning it off only hides
 * the two teams' final numeric score digits (tier badge, breakdown, and
 * final result stay visible either way). Defaults to hidden (spoiler-safe)
 * every time this screen is (re)composed - e.g. navigating back to History
 * from another tab - rather than persisting a "showing scores" choice
 * across tab switches, so a viewer can't accidentally get spoiled by
 * whatever state they left it in last time.
 *
 * WNBA has no historical backfill yet (NBA only) - [leagueGroup] gates the
 * whole screen to a plain blank state when WNBA is selected, rather than
 * erroring or showing stale NBA data.
 *
 * No hook/pitch preview text is generated or shown here (unlike the
 * pregame preview on other tabs) - these are already-finished games, so
 * there's nothing to preview, and historyService.ts never calls the LLM for
 * them at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    uiState: HistoryUiState,
    presets: List<HistoryRangePreset>,
    selectedPreset: HistoryRangePreset,
    earliestDate: java.time.LocalDate?,
    onPresetSelected: (HistoryRangePreset) -> Unit,
    onRetry: () -> Unit,
    showNumericScore: Boolean,
    onToggleNumericScore: () -> Unit,
    weights: RubricWeights,
    starredIds: Set<String>,
    onToggleStar: (Game) -> Unit,
    onWatchHighlights: (String) -> Unit,
    showWnba: Boolean,
    selectedLeague: LeagueGroup,
    onLeagueSelected: (LeagueGroup) -> Unit,
    leagueGroup: LeagueGroup,
    onSettingsClick: () -> Unit
) {
    // Plain remember (not rememberSaveable) - defaults to hidden every time
    // this composable enters composition, e.g. switching back to History
    // from another tab, so a "showing scores" choice never survives a tab
    // switch and can't accidentally spoil something.
    var showScore by remember { mutableStateOf(false) }
    // Same two-toggle pattern as StarredScreen: sortBestFirst picks the sort
    // mode (rating vs date), dateAscending only matters in date mode - both
    // local re-sorts of the already-fetched list, no re-fetch needed.
    var sortBestFirst by rememberSaveable { mutableStateOf(true) }
    var dateAscending by rememberSaveable { mutableStateOf(false) }
    var actionLabel by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = BackgroundBase,
        topBar = {
            TopAppBar(
                title = { TabTitle(showWnba, selectedLeague, onLeagueSelected, "Past Barn Burners") },
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
                            sortBestFirst = it
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
                        checked = showScore,
                        onCheckedChange = {
                            showScore = it
                            actionLabel = if (it) "Showing scores" else "Hiding scores"
                        }
                    ) {
                        Icon(
                            imageVector = if (showScore) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (showScore) "Hide scores" else "Show scores",
                            tint = if (!showScore) TierWorthYourTime else TextSecondary
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
        if (leagueGroup != LeagueGroup.NBA) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "History isn't built for WNBA yet - check back later.",
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            return@Scaffold
        }
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { preset ->
                    FilterChip(
                        selected = preset == selectedPreset,
                        onClick = { onPresetSelected(preset) },
                        label = { Text(preset.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = TierWorthYourTime,
                            selectedLabelColor = BackgroundBase
                        )
                    )
                }
            }

            when (uiState) {
                is HistoryUiState.Loading -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                }

                is HistoryUiState.Error -> Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Couldn't load history",
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = uiState.message,
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                    )
                    Button(onClick = onRetry) { Text("Retry") }
                }

                is HistoryUiState.Loaded -> {
                    if (uiState.games.isEmpty()) {
                        val earliestText = earliestDate?.format(earliestDateFormatter)
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "No barn burners in this range" +
                                    (if (earliestText != null) " - data goes back to $earliestText, try a wider one." else "."),
                                color = TextSecondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    } else {
                        // Same three-way ordering as StarredScreen: best first
                        // (by the user's own rubric weights, not just the
                        // server's stored score) or date order in either
                        // direction - every History game already has a score,
                        // so unlike Starred there's no unscored tail to fall
                        // back to.
                        val ordered = if (sortBestFirst) {
                            uiState.games.sortedByDescending { it.effectiveScore(weights) }
                        } else if (dateAscending) {
                            uiState.games.sortedBy { OffsetDateTime.parse(it.tipoffUtc) }
                        } else {
                            uiState.games.sortedByDescending { OffsetDateTime.parse(it.tipoffUtc) }
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
                                    showDate = true,
                                    spoilerFree = true,
                                    showScore = showScore
                                )
                            }
                        }
                    }
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
