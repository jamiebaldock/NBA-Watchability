package com.nbawatchability.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nbawatchability.app.data.DayGames
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
import com.nbawatchability.app.ui.theme.TextSecondary
import com.nbawatchability.app.ui.theme.TierInstantClassic
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// Nothing hardcoded here anymore - which date(s) get the banner and how many
// games they had are both computed dynamically by GameListViewModel.kt's
// loadYearRecordDaysIfNeeded (real per-day counts, bucketed by the viewer's
// own local calendar date, same mechanism the calendar picker itself uses).
// See that function's own comment for why more than one date commonly ties
// for the year's real max, and why the specific tied date(s) can differ by a
// day depending on the viewer's own timezone relative to the US Eastern/
// ESPN-scoreboard-day convention the raw data comes back in.
@Composable
private fun RecordGameDayBanner(gameCount: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(TierInstantClassic.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = Icons.Default.EmojiEvents, contentDescription = null, tint = TierInstantClassic, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Busiest day of 2026 - $gameCount games across every league today",
            color = TierInstantClassic,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

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
    nbaWeights: RubricWeights,
    wnbaWeights: RubricWeights,
    mlbWeights: MlbRubricWeights,
    nflWeights: NflRubricWeights,
    nhlWeights: NhlRubricWeights,
    selectedLeague: LeagueGroup,
    onLeagueSelected: (LeagueGroup) -> Unit,
    enabledLeagues: Set<LeagueGroup>,
    isAllLeaguesSelected: Boolean,
    onToggleAllLeagues: () -> Unit,
    starredIds: Set<String>,
    onToggleStar: (com.nbawatchability.app.data.Game) -> Unit,
    onWatchHighlights: (String) -> Unit,
    isJumpingToNextGame: Boolean,
    jumpToNextGameError: String?,
    onJumpToNextGame: () -> Unit,
    onJumpToNextGameErrorShown: () -> Unit,
    isJumpingToToday: Boolean,
    onJumpToToday: () -> Unit,
    monthCounts: Map<LocalDate, Int>,
    monthCountsMonth: YearMonth?,
    isLoadingMonthCounts: Boolean,
    onMonthChanged: (YearMonth) -> Unit,
    recordDays: Set<LocalDate>,
    isJumpingToDate: Boolean,
    onJumpToDate: (LocalDate) -> Unit,
    favoriteTeamNames: Set<String> = emptySet(),
    bumpFavoriteTeamGames: Boolean = false,
    onToggleFavoriteTeam: (Team) -> Unit = {},
    favoritePlayerNames: Set<String> = emptySet(),
    onToggleFavoritePlayer: (com.nbawatchability.app.data.FavoritePlayer) -> Unit = {},
    minTierFilterEnabled: Boolean = false,
    minTierFilter: Tier = Tier.SKIPPABLE,
    onGameClick: (com.nbawatchability.app.data.Game) -> Unit = {}
) {
    val pagerState = rememberPagerState(initialPage = selectedDayIndex) { days.size }
    var actionLabel by remember { mutableStateOf<String?>(null) }
    var showCalendar by remember { mutableStateOf(false) }
    // One shared sort preference across every day, not a per-day setting -
    // matches the shared action-icon row above it (one control, not one per
    // page). Defaults to oldest-first (i.e. tipoff order), the natural
    // reading order for a single day's slate.
    var sortOption by remember { mutableStateOf(SortOption.DATE_OLDEST_FIRST) }

    // settledPage (not currentPage) drives the push-up-to-ViewModel side of
    // this sync deliberately - currentPage changes on every intermediate
    // page a programmatic animateScrollToPage animation crosses, and for a
    // far calendar-picker jump (e.g. today -> two months away) that's dozens
    // of pages. Syncing selectedDayIndex off currentPage fed each of those
    // transient pages back into the effect below, which re-triggered
    // animateScrollToPage toward that transient (wrong) target and
    // cancelled the in-flight one — producing exactly the stuck
    // half-settled/split-page state reported against the calendar picker.
    // settledPage only changes once the pager is done moving (swipe or
    // programmatic), so it can't reintroduce that loop.
    LaunchedEffect(pagerState.settledPage) {
        if (pagerState.settledPage != selectedDayIndex) onDaySelected(pagerState.settledPage)
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
            AppTopBar(
                leading = {
                    TitleLeagueSelector(
                        selectedLeague = selectedLeague,
                        onLeagueSelected = onLeagueSelected,
                        enabledLeagues = enabledLeagues,
                        isAllLeaguesSelected = isAllLeaguesSelected,
                        onAllLeaguesSelected = onToggleAllLeagues
                    )
                },
                actions = {
                    // Always available now - the calendar scrolls freely
                    // across years and shows real per-day game counts
                    // (fetched lazily per visible month), so it's useful
                    // regardless of whether "the current season" happens to
                    // be loaded for whichever league is selected.
                    IconButton(onClick = { showCalendar = true }, enabled = !isJumpingToDate) {
                        if (isJumpingToDate) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.Default.CalendarMonth,
                                contentDescription = "Jump to date",
                                tint = TextSecondary
                            )
                        }
                    }
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
                },
                secondary = {
                    CenteringDayTabRow(
                        days = days,
                        today = today,
                        selectedDayIndex = selectedDayIndex,
                        onDaySelected = onDaySelected
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
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Only meaningful in "All Leagues" mode - the record
                        // is a cross-league total (see
                        // GameListViewModel.kt's loadYearRecordDaysIfNeeded),
                        // which a single-league view can't show anyway (that
                        // day's single-league count is always a small
                        // fraction of the real combined figure). recordDays
                        // is empty until that background fetch completes, so
                        // this naturally shows nothing while it's loading.
                        if (isAllLeaguesSelected && days[page].date in recordDays) {
                            RecordGameDayBanner(
                                gameCount = days[page].games.size,
                                modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp)
                            )
                        }
                        DayGamesList(
                            games = days[page].games,
                            sortOption = sortOption,
                            showNumericScore = showNumericScore,
                            nbaWeights = nbaWeights,
                            wnbaWeights = wnbaWeights,
                            mlbWeights = mlbWeights,
                            nflWeights = nflWeights,
                            nhlWeights = nhlWeights,
                            starredIds = starredIds,
                            onToggleStar = onToggleStar,
                            onWatchHighlights = onWatchHighlights,
                            isJumpingToNextGame = isJumpingToNextGame,
                            onJumpToNextGame = onJumpToNextGame,
                            favoriteTeamNames = favoriteTeamNames,
                            bumpFavoriteTeamGames = bumpFavoriteTeamGames,
                            onToggleFavoriteTeam = onToggleFavoriteTeam,
                            favoritePlayerNames = favoritePlayerNames,
                            onToggleFavoritePlayer = onToggleFavoritePlayer,
                            minTierFilterEnabled = minTierFilterEnabled,
                            minTierFilter = minTierFilter,
                            onGameClick = onGameClick
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

    if (showCalendar) {
        SeasonCalendarDialog(
            initialMonth = YearMonth.from(today),
            gameCounts = monthCounts,
            gameCountsMonth = monthCountsMonth,
            isLoadingCounts = isLoadingMonthCounts,
            onMonthChanged = onMonthChanged,
            onDateSelected = { date ->
                showCalendar = false
                onJumpToDate(date)
            },
            onDismiss = { showCalendar = false }
        )
    }
}

/**
 * Material3's ScrollableTabRow only scrolls the selected tab into view (edge
 * aligned), not centered — replaced with a manually-scrolled LazyRow of
 * [NavChip]s (the same pill visual every other tab's secondary row uses).
 * Each chip is sized to exactly a third of the viewport (rather than sizing
 * to its own text content), so at rest exactly 3 are ever visible with none
 * peeking in cut off at either edge - scrolling so (selectedDayIndex - 1)
 * becomes the first visible item then reproduces the same "selected chip
 * centered, one neighbor on each side" layout (clamped to the ends of the
 * list, where fewer than one full chip's worth of neighbors exist). Lazy
 * (not a plain Row, unlike [NavChipRow]) since a full WNBA season is ~140
 * days - only NBA's narrow window is small enough that a non-lazy Row
 * would've been fine, but there's no reason to maintain two versions of
 * this centering/sizing behavior just to also use [NavChipRow]'s plain Row.
 */
@Composable
private fun CenteringDayTabRow(
    days: List<DayGames>,
    today: LocalDate,
    selectedDayIndex: Int,
    onDaySelected: (Int) -> Unit
) {
    val listState = rememberLazyListState()
    var viewportWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val tabWidthPx = viewportWidth / 3

    LaunchedEffect(selectedDayIndex, viewportWidth) {
        if (tabWidthPx == 0 || days.isEmpty()) return@LaunchedEffect
        listState.animateScrollToItem((selectedDayIndex - 1).coerceIn(0, days.size - 1))
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { viewportWidth = it.width }
            .padding(vertical = 8.dp)
    ) {
        itemsIndexed(days, key = { _, day -> day.date.toString() }) { index, day ->
            NavChip(
                label = dayTabLabel(day.date, today),
                selected = index == selectedDayIndex,
                onClick = { onDaySelected(index) },
                modifier = Modifier
                    .width(with(density) { tabWidthPx.toDp() })
                    .padding(horizontal = 4.dp)
            )
        }
    }
}

// No best-first sort option here (unlike Starred/History) - every game on
// this tab is already the same single day - most days have only a handful
// of games, but a busy slate (a full WNBA night, or NBA's opening day) can
// have enough that a best-first ordering is genuinely useful, same as
// Starred/History/Favorites' identical 4-option sort.
@Composable
private fun DayGamesList(
    games: List<com.nbawatchability.app.data.Game>,
    sortOption: SortOption,
    showNumericScore: Boolean,
    nbaWeights: RubricWeights,
    wnbaWeights: RubricWeights,
    mlbWeights: MlbRubricWeights,
    nflWeights: NflRubricWeights,
    nhlWeights: NhlRubricWeights,
    starredIds: Set<String>,
    onToggleStar: (com.nbawatchability.app.data.Game) -> Unit,
    onWatchHighlights: (String) -> Unit,
    isJumpingToNextGame: Boolean,
    onJumpToNextGame: () -> Unit,
    favoriteTeamNames: Set<String> = emptySet(),
    bumpFavoriteTeamGames: Boolean = false,
    onToggleFavoriteTeam: (Team) -> Unit = {},
    favoritePlayerNames: Set<String> = emptySet(),
    onToggleFavoritePlayer: (com.nbawatchability.app.data.FavoritePlayer) -> Unit = {},
    minTierFilterEnabled: Boolean = false,
    minTierFilter: Tier = Tier.SKIPPABLE,
    onGameClick: (com.nbawatchability.app.data.Game) -> Unit = {}
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

    // Same unscored-games-fall-back-to-newest-first treatment as every
    // other sortable tab - an upcoming/live game on this same day has no
    // score to sort by yet.
    val orderedGames = when (sortOption) {
        SortOption.RATING_HIGHEST_FIRST -> {
            val (scored, unscored) = games.partition { it.effectiveScore(nbaWeights, wnbaWeights, mlbWeights, nflWeights, nhlWeights) != null }
            scored.sortedByDescending { it.effectiveScore(nbaWeights, wnbaWeights, mlbWeights, nflWeights, nhlWeights) } + unscored.sortedByDescending { it.tipoffUtc }
        }
        SortOption.RATING_LOWEST_FIRST -> {
            val (scored, unscored) = games.partition { it.effectiveScore(nbaWeights, wnbaWeights, mlbWeights, nflWeights, nhlWeights) != null }
            scored.sortedBy { it.effectiveScore(nbaWeights, wnbaWeights, mlbWeights, nflWeights, nhlWeights) } + unscored.sortedByDescending { it.tipoffUtc }
        }
        SortOption.DATE_OLDEST_FIRST -> games.sortedBy { it.tipoffUtc }
        SortOption.DATE_NEWEST_FIRST -> games.sortedByDescending { it.tipoffUtc }
    }.filterByMinTier(minTierFilterEnabled, minTierFilter, nbaWeights, wnbaWeights, mlbWeights, nflWeights, nhlWeights)
        .bumpFavoriteTeamGames(bumpFavoriteTeamGames, favoriteTeamNames)

    val listState = rememberLazyListState()
    // Without this, LazyColumn's key-based item tracking keeps whatever
    // tile was on top in view across a re-sort, same reasoning as every
    // other sortable tab's identical effect.
    LaunchedEffect(sortOption) { listState.animateScrollToTopAdaptively() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(orderedGames, key = { it.id }) { game ->
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
                favoriteTeamNames = favoriteTeamNames,
                onToggleFavoriteTeam = onToggleFavoriteTeam,
                favoritePlayerNames = favoritePlayerNames,
                onToggleFavoritePlayer = onToggleFavoritePlayer,
                onGameClick = onGameClick
            )
        }
    }
}
