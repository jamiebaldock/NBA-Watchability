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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.nbawatchability.app.data.Team
import com.nbawatchability.app.data.Tier
import com.nbawatchability.app.data.bumpFavoriteTeamGames
import com.nbawatchability.app.data.effectiveScore
import com.nbawatchability.app.data.filterByMinTier
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary
import com.nbawatchability.app.ui.theme.TierWorthYourTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

private val earliestDateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")

// "All time" holds itself to a much higher bar than every other range - the
// server's own >=70 (gameStore.ts's HISTORY_MIN_SCORE) makes sense across a
// single season, but scanning the *entire* backfill at that same bar would
// surface a long list that isn't really "the best of all time" anymore.
// Applied client-side (not a backend param) since effective score depends
// on the viewer's own rubric weights, same as the existing rating sort
// below - the server's >=70 games are still fetched, this just narrows the
// display further for this one preset.
//
// WNBA gets its own, lower bar - NBA's 90 represents its rarest ~0.1% of
// games (3 of 2,650 in the backfill), but WNBA's entire History-qualifying
// pool (>=70) is only 8 games total, topping out at 81 - applying NBA's
// literal threshold, or even matching NBA's rarity percentile, leaves
// All-time permanently empty (or down to a single game) for WNBA. 75 keeps
// the top 5 of those 8 - still a real cut from "everything," just not one
// calibrated to a sample size WNBA's backfill doesn't have yet. Revisit as
// more WNBA seasons get backfilled and the pool grows past single digits.
private const val ALL_TIME_MIN_SCORE_NBA = 90
private const val ALL_TIME_MIN_SCORE_WNBA = 75

// Shared across EPL and La Liga (not split like NBA/WNBA) - both leagues
// run through the exact same soccerRubric.ts scale/calibration, and their
// real backfilled score distributions land close enough together (p95: 62
// vs 63, p99: 80 vs 80.8 out of the 760-match-each 2024-25+2025-26
// backfill) that a per-league split isn't warranted. Grounded the same way
// as WNBA's bar above - reusing NBA's literal 90 would leave EPL down to a
// single qualifying match (1 of 760) - but landed on 85 rather than 90:
// it's the 99.3rd percentile of the full 1,520-game combined backfill (10
// games total, a healthier 4 EPL + 6 La Liga split) and, conveniently,
// exactly Tier.fromScore's own instant_classic cutoff - "All-time" for
// soccer reads as "every real Instant Classic in the backfill," a bar
// that's self-documenting rather than an arbitrary number tuned to hit a
// target count.
private const val ALL_TIME_MIN_SCORE_SOCCER = 85

/**
 * "Which old games are actually worth going back to watch" - surfaces the
 * per-league watchability backfill (NBA and WNBA both have one, each scored
 * through the same league-aware rubric), games scoring 70+ only
 * (gameStore.ts's HISTORY_MIN_SCORE - stricter than the "Worth Your Time"
 * tier badge's own >=65), most-watchable-first by default - except "All
 * time" (ALL_TIME_MIN_SCORE_NBA/ALL_TIME_MIN_SCORE_WNBA above), which holds
 * every season's worth of backfill to a much higher, per-league bar
 * instead. Unlike every other tab, these games are
 * ones the viewer is intentionally browsing rather than following live, so
 * the breakdown is never spoiler-blurred (GameCard's spoilerFree = true) -
 * the tier/score/final result are the point, not something to hide.
 * [showScore] is a separate, purely local "browse blind" preference -
 * unlike spoilerFree, turning it off only hides the two teams' final
 * numeric score digits (tier badge, breakdown, and final result stay
 * visible either way). Resets to [showScoresByDefault] every time this
 * screen is (re)composed - e.g. navigating back to History from another
 * tab - rather than remembering whatever the toggle was last left at
 * within a single session, so an accidental peek doesn't linger past a
 * tab switch. [showScoresByDefault] itself is a persisted Settings choice
 * (default false, i.e. spoiler-safe), not a session toggle - a user who's
 * decided they don't mind seeing scores can make that the starting state
 * instead of hidden.
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
    selectedLeague: LeagueGroup,
    onLeagueSelected: (LeagueGroup) -> Unit,
    enabledLeagues: Set<LeagueGroup>,
    favoriteTeamNames: Set<String> = emptySet(),
    bumpFavoriteTeamGames: Boolean = false,
    onToggleFavoriteTeam: (Team) -> Unit = {},
    favoritePlayerNames: Set<String> = emptySet(),
    minTierFilterEnabled: Boolean = false,
    minTierFilter: Tier = Tier.SKIPPABLE,
    showScoresByDefault: Boolean = false
) {
    // Plain remember (not rememberSaveable) - resets to showScoresByDefault
    // every time this composable enters composition, e.g. switching back to
    // History from another tab, so a "showing scores" choice never survives
    // a tab switch and can't accidentally spoil something beyond what the
    // user's own persisted default already allows.
    var showScore by remember { mutableStateOf(showScoresByDefault) }
    // A single 4-option sort dropdown (SortMenuButton) rather than two
    // independent toggles - defaults to highest-rated first, matching this
    // tab's own "most watchable first" framing.
    var sortOption by rememberSaveable { mutableStateOf(SortOption.RATING_HIGHEST_FIRST) }
    var actionLabel by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = BackgroundBase,
        topBar = {
            TopAppBar(
                title = { TitleLeagueSelector(selectedLeague, onLeagueSelected, enabledLeagues) },
                actions = {
                    SortMenuButton(
                        selected = sortOption,
                        onSelected = { sortOption = it }
                    )
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
                }
            )
        }
    ) { padding ->
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
                    val displayGames = if (selectedPreset is HistoryRangePreset.AllTime) {
                        val allTimeMinScore = when (selectedLeague) {
                            LeagueGroup.WNBA -> ALL_TIME_MIN_SCORE_WNBA
                            LeagueGroup.EPL, LeagueGroup.LA_LIGA -> ALL_TIME_MIN_SCORE_SOCCER
                            else -> ALL_TIME_MIN_SCORE_NBA
                        }
                        uiState.games.filter { (it.effectiveScore(weights) ?: 0) >= allTimeMinScore }
                    } else {
                        uiState.games
                    }

                    if (displayGames.isEmpty()) {
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
                        // Ordered by the user's own rubric weights (not just
                        // the server's stored score) when sorting by rating -
                        // every History game already has a score, so unlike
                        // Starred there's no unscored tail to fall back to.
                        val ordered = when (sortOption) {
                            SortOption.RATING_HIGHEST_FIRST -> displayGames.sortedByDescending { it.effectiveScore(weights) }
                            SortOption.RATING_LOWEST_FIRST -> displayGames.sortedBy { it.effectiveScore(weights) }
                            SortOption.DATE_OLDEST_FIRST -> displayGames.sortedBy { OffsetDateTime.parse(it.tipoffUtc) }
                            SortOption.DATE_NEWEST_FIRST -> displayGames.sortedByDescending { OffsetDateTime.parse(it.tipoffUtc) }
                        }.filterByMinTier(minTierFilterEnabled, minTierFilter, weights)
                            .bumpFavoriteTeamGames(bumpFavoriteTeamGames, favoriteTeamNames)

                        val listState = rememberLazyListState()
                        // Without this, LazyColumn's key-based item tracking
                        // keeps whatever tile was on top in view across a
                        // re-sort (since it's usually still in the new list,
                        // just at a different index) - which can make an
                        // already-correct sort look wrong, not just skip the
                        // scroll-to-top animation.
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
                                    spoilerFree = true,
                                    showScore = showScore,
                                    favoriteTeamNames = favoriteTeamNames,
                                    onToggleFavoriteTeam = onToggleFavoriteTeam,
                                    favoritePlayerNames = favoritePlayerNames
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
