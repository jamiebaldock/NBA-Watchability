package com.nbawatchability.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nbawatchability.app.data.DayGames
import com.nbawatchability.app.data.LeagueGroup
import com.nbawatchability.app.data.RubricWeights
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary
import com.nbawatchability.app.ui.theme.TierWorthYourTime
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val dayTabFormatter = DateTimeFormatter.ofPattern("MMM d")

private fun dayTabLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    today.plusDays(1) -> "Tomorrow"
    else -> "${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${date.format(dayTabFormatter)}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayTabsScreen(
    days: List<DayGames>,
    today: LocalDate,
    selectedDayIndex: Int,
    onDaySelected: (Int) -> Unit,
    showNumericScore: Boolean,
    onToggleNumericScore: () -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    weights: RubricWeights,
    onSettingsClick: () -> Unit,
    selectedLeague: LeagueGroup,
    onLeagueSelected: (LeagueGroup) -> Unit,
    enabledLeagues: Set<LeagueGroup>,
    starredIds: Set<String>,
    onToggleStar: (com.nbawatchability.app.data.Game) -> Unit,
    onWatchHighlights: (String) -> Unit,
    isJumpingToNextGame: Boolean,
    jumpToNextGameError: String?,
    onJumpToNextGame: () -> Unit,
    onJumpToNextGameErrorShown: () -> Unit,
    isJumpingToToday: Boolean,
    onJumpToToday: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = selectedDayIndex) { days.size }
    var actionLabel by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != selectedDayIndex) onDaySelected(pagerState.currentPage)
    }
    LaunchedEffect(selectedDayIndex) {
        if (pagerState.currentPage != selectedDayIndex) pagerState.animateScrollToPage(selectedDayIndex)
    }
    // Surfaced through the same small top-right confirmation used for
    // toggle actions elsewhere on this screen, rather than a second overlay
    // mechanism - a failed/empty jump is the same kind of transient,
    // non-blocking notice.
    LaunchedEffect(jumpToNextGameError) {
        if (jumpToNextGameError != null) {
            actionLabel = jumpToNextGameError
            onJumpToNextGameErrorShown()
        }
    }

    Scaffold(
        containerColor = BackgroundBase,
        topBar = {
            TopAppBar(
                title = { TitleLeagueSelector(selectedLeague, onLeagueSelected, enabledLeagues) },
                actions = {
                    // Only shown once the viewer has actually wandered off
                    // today's tab (by swiping or via "jump to next game") -
                    // same "only surface it when it'd do something" rule as
                    // the empty-day jump button, rather than a permanently
                    // present icon that's a no-op most of the time.
                    if (days.getOrNull(selectedDayIndex)?.date != today) {
                        IconButton(onClick = onJumpToToday, enabled = !isJumpingToToday) {
                            if (isJumpingToToday) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Today,
                                    contentDescription = "Back to today",
                                    tint = TextSecondary
                                )
                            }
                        }
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
            Column(modifier = Modifier.fillMaxSize()) {
                CenteringDayTabRow(
                    days = days,
                    today = today,
                    selectedDayIndex = selectedDayIndex,
                    onDaySelected = onDaySelected
                )

                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize()
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        DayGamesList(
                            games = days[page].games,
                            showNumericScore = showNumericScore,
                            weights = weights,
                            starredIds = starredIds,
                            onToggleStar = onToggleStar,
                            onWatchHighlights = onWatchHighlights,
                            isJumpingToNextGame = isJumpingToNextGame,
                            onJumpToNextGame = onJumpToNextGame
                        )
                    }
                }
            }
            ActionLabelOverlay(
                label = actionLabel,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 56.dp)
            )
        }
    }
}

/**
 * Material3's ScrollableTabRow only scrolls the selected tab into view (edge
 * aligned), not centered — replaced with a manual horizontalScroll Row.
 * Each tab is sized to exactly a third of the viewport (rather than sizing
 * to its own text content), so at rest exactly 3 tabs are ever visible with
 * none peeking in cut off at either edge - the selected tab's scroll offset
 * is then just an exact multiple of that fixed tab width, landing it in the
 * middle slot with a full tab showing on each side (clamped to the ends of
 * the list, where fewer than one full tab's worth of neighbors exist).
 */
@Composable
private fun CenteringDayTabRow(
    days: List<DayGames>,
    today: LocalDate,
    selectedDayIndex: Int,
    onDaySelected: (Int) -> Unit
) {
    val scrollState = rememberScrollState()
    var viewportWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val tabWidthPx = viewportWidth / 3

    LaunchedEffect(selectedDayIndex, viewportWidth) {
        if (tabWidthPx == 0) return@LaunchedEffect
        val target = (selectedDayIndex - 1) * tabWidthPx
        scrollState.animateScrollTo(target.coerceIn(0, scrollState.maxValue))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { viewportWidth = it.width }
            .horizontalScroll(scrollState)
    ) {
        days.forEachIndexed { index, day ->
            val selected = index == selectedDayIndex
            Column(
                modifier = Modifier
                    .width(with(density) { tabWidthPx.toDp() })
                    .clickable { onDaySelected(index) }
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = dayTabLabel(day.date, today),
                    color = if (selected) TextPrimary else TextSecondary,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(if (selected) TierWorthYourTime else Color.Transparent)
                )
            }
        }
    }
}

// No best-first sort option here (unlike Starred/History) - every game on
// this tab is already the same single day, so reordering by score doesn't
// do anything a viewer couldn't already see at a glance across ~a handful
// of tiles; it's only meaningful once games from different dates mix in
// one list.
@Composable
private fun DayGamesList(
    games: List<com.nbawatchability.app.data.Game>,
    showNumericScore: Boolean,
    weights: RubricWeights,
    starredIds: Set<String>,
    onToggleStar: (com.nbawatchability.app.data.Game) -> Unit,
    onWatchHighlights: (String) -> Unit,
    isJumpingToNextGame: Boolean,
    onJumpToNextGame: () -> Unit
) {
    if (games.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "No games scheduled",
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(20.dp))
            // A day with nothing scheduled might just be a quiet gap within
            // an ongoing season, or the tail end of one - either way, rather
            // than making the viewer keep swiping blindly to find out which,
            // this looks ahead (possibly weeks past this window, possibly
            // into a different stage) for whatever the next real game
            // actually is.
            Button(onClick = onJumpToNextGame, enabled = !isJumpingToNextGame) {
                if (isJumpingToNextGame) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(text = "Jump to next game")
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(games, key = { it.id }) { game ->
            GameCard(
                game = game,
                showNumericScore = showNumericScore,
                weights = weights,
                isStarred = starredIds.contains(game.id),
                onToggleStar = { onToggleStar(game) },
                onWatchHighlights = onWatchHighlights
            )
        }
    }
}
