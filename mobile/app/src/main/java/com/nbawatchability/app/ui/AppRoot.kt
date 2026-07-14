package com.nbawatchability.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.SportsBasketball
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nbawatchability.app.data.LeagueGroup
import com.nbawatchability.app.data.RubricWeights
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.SurfaceCardElevated
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary
import com.nbawatchability.app.ui.theme.TierWorthYourTime

private enum class BottomNavTab(val label: String, val icon: ImageVector) {
    GAMES("Games", Icons.Default.SportsBasketball),
    STARRED("Starred", Icons.Default.Star),
    // Icon is a flame ("barn burner") even though the nav label stays
    // "History" - the in-screen title is the one that says "Past Barn
    // Burners" (HistoryScreen.kt).
    HISTORY("History", Icons.Default.LocalFireDepartment),
    // Standings + Stats merged into one swipeable tab (LeadersScreen.kt) -
    // "3 horizontal lines" reads as a list/menu, matching what's inside.
    LEADERS("Leaders", Icons.Default.Menu),
    NEWS("News", Icons.AutoMirrored.Filled.Article)
}

/**
 * App-wide root: a persistent bottom navigation bar with 5 tabs. Every tab's
 * top bar shows the same tappable league selector (Settings' "Show WNBA"
 * toggle + the title dropdown) so the league can be switched from anywhere,
 * not just Games. History's backfill is NBA-only for now - selecting WNBA
 * there shows a plain "not built yet" empty state instead of an error.
 * "About" isn't a tab - it's a slot's worth of nav real estate that Starred
 * needed more, so it lives one level deeper, opened from Settings instead.
 */
@Composable
fun AppRoot() {
    var selectedTab by rememberSaveable { mutableStateOf(BottomNavTab.GAMES) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showRubricWeights by rememberSaveable { mutableStateOf(false) }
    var highlightsVideoId by rememberSaveable { mutableStateOf<String?>(null) }
    val settingsViewModel: RubricSettingsViewModel = viewModel()
    val appSettingsViewModel: AppSettingsViewModel = viewModel()
    val starredGamesViewModel: StarredGamesViewModel = viewModel()

    // Persisted settings (last league, sort, numeric score) load from
    // DataStore asynchronously - rendering a tab before they arrive would
    // fire a request for the hardcoded default (NBA) that can then race
    // against, and sometimes overwrite, the real persisted choice.
    if (!appSettingsViewModel.isLoaded) {
        LoadingScreen()
        return
    }

    BackHandler(enabled = showSettings) { showSettings = false }
    // Declared after showSettings's handler so they sit higher on the back
    // stack - back from About/rating weights returns to Settings, not
    // straight past it.
    BackHandler(enabled = showAbout) { showAbout = false }
    BackHandler(enabled = showRubricWeights) { showRubricWeights = false }
    BackHandler(enabled = highlightsVideoId != null) { highlightsVideoId = null }

    if (showAbout) {
        AboutScreen(onBack = { showAbout = false })
        return
    }

    if (showRubricWeights) {
        RubricWeightsScreen(
            weights = settingsViewModel.weights,
            onWeightChange = settingsViewModel::updateWeight,
            onReset = settingsViewModel::resetToDefaults,
            onBack = { showRubricWeights = false }
        )
        return
    }

    if (showSettings) {
        SettingsScreen(
            showWnba = appSettingsViewModel.settings.showWnba,
            onShowWnbaChange = appSettingsViewModel::setShowWnba,
            onRubricWeightsClick = { showRubricWeights = true },
            onAboutClick = { showAbout = true },
            onBack = { showSettings = false }
        )
        return
    }

    highlightsVideoId?.let { videoId ->
        HighlightsPlayerScreen(videoId = videoId, onBack = { highlightsVideoId = null })
        return
    }

    Scaffold(
        containerColor = BackgroundBase,
        bottomBar = {
            NavigationBar(containerColor = SurfaceCardElevated) {
                BottomNavTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == selectedTab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(imageVector = tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = TierWorthYourTime,
                            selectedTextColor = TierWorthYourTime,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = SurfaceCardElevated
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (selectedTab) {
                BottomNavTab.GAMES -> GamesTab(
                    weights = settingsViewModel.weights,
                    onSettingsClick = { showSettings = true },
                    showWnba = appSettingsViewModel.settings.showWnba,
                    selectedLeague = appSettingsViewModel.settings.selectedLeague,
                    onLeagueSelected = appSettingsViewModel::setSelectedLeague,
                    effectiveLeagueGroup = appSettingsViewModel.settings.effectiveLeagueGroup,
                    showNumericScore = appSettingsViewModel.settings.showNumericScore,
                    onToggleNumericScore = appSettingsViewModel::toggleShowNumericScore,
                    starredIds = starredGamesViewModel.starredIds,
                    onToggleStar = starredGamesViewModel::toggleStar,
                    onWatchHighlights = { videoId -> highlightsVideoId = videoId }
                )
                BottomNavTab.LEADERS -> LeadersTab(
                    showWnba = appSettingsViewModel.settings.showWnba,
                    selectedLeague = appSettingsViewModel.settings.selectedLeague,
                    onLeagueSelected = appSettingsViewModel::setSelectedLeague,
                    effectiveLeagueGroup = appSettingsViewModel.settings.effectiveLeagueGroup,
                    onSettingsClick = { showSettings = true }
                )
                BottomNavTab.NEWS -> NewsTab(
                    showWnba = appSettingsViewModel.settings.showWnba,
                    selectedLeague = appSettingsViewModel.settings.selectedLeague,
                    onLeagueSelected = appSettingsViewModel::setSelectedLeague,
                    effectiveLeagueGroup = appSettingsViewModel.settings.effectiveLeagueGroup,
                    onSettingsClick = { showSettings = true }
                )
                BottomNavTab.STARRED -> StarredTab(
                    starredGamesViewModel = starredGamesViewModel,
                    showWnba = appSettingsViewModel.settings.showWnba,
                    selectedLeague = appSettingsViewModel.settings.selectedLeague,
                    onLeagueSelected = appSettingsViewModel::setSelectedLeague,
                    showNumericScore = appSettingsViewModel.settings.showNumericScore,
                    onToggleNumericScore = appSettingsViewModel::toggleShowNumericScore,
                    sortBestFirst = appSettingsViewModel.settings.sortBestFirst,
                    onToggleSort = appSettingsViewModel::toggleSortBestFirst,
                    weights = settingsViewModel.weights,
                    onWatchHighlights = { videoId -> highlightsVideoId = videoId },
                    onSettingsClick = { showSettings = true }
                )
                BottomNavTab.HISTORY -> HistoryTab(
                    weights = settingsViewModel.weights,
                    showWnba = appSettingsViewModel.settings.showWnba,
                    selectedLeague = appSettingsViewModel.settings.selectedLeague,
                    onLeagueSelected = appSettingsViewModel::setSelectedLeague,
                    effectiveLeagueGroup = appSettingsViewModel.settings.effectiveLeagueGroup,
                    showNumericScore = appSettingsViewModel.settings.showNumericScore,
                    onToggleNumericScore = appSettingsViewModel::toggleShowNumericScore,
                    starredIds = starredGamesViewModel.starredIds,
                    onToggleStar = starredGamesViewModel::toggleStar,
                    onWatchHighlights = { videoId -> highlightsVideoId = videoId },
                    onSettingsClick = { showSettings = true }
                )
            }
        }
    }
}

@Composable
private fun GamesTab(
    weights: RubricWeights,
    onSettingsClick: () -> Unit,
    showWnba: Boolean,
    selectedLeague: LeagueGroup,
    onLeagueSelected: (LeagueGroup) -> Unit,
    effectiveLeagueGroup: LeagueGroup,
    showNumericScore: Boolean,
    onToggleNumericScore: () -> Unit,
    starredIds: Set<String>,
    onToggleStar: (com.nbawatchability.app.data.Game) -> Unit,
    onWatchHighlights: (String) -> Unit
) {
    val viewModel: GameListViewModel = viewModel()

    // Fires on first composition and again whenever the effective league
    // changes (toggle flipped, or a different league picked from the
    // dropdown) - a full fresh load, not a merge with whatever was showing.
    LaunchedEffect(effectiveLeagueGroup) { viewModel.load(effectiveLeagueGroup) }

    // Same refresh() pull-to-refresh already uses - cost-safe to fire more
    // often than a user would manually pull, since ensureHighlightsVideo's
    // cache/cooldown and the pregame preview's once-ever gate (both in
    // gamesService.ts) mean a redundant fetch just re-reads already-cached
    // state server-side, not new YouTube searches or LLM calls. Only wired
    // up while this tab is actually composed (selected), so backgrounding
    // the app while on a different tab doesn't refresh Games in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    when (val state = viewModel.uiState) {
        is ScheduleUiState.Loading -> LoadingScreen()
        is ScheduleUiState.Error -> ErrorScreen(state.message, onRetry = { viewModel.load(effectiveLeagueGroup) })
        is ScheduleUiState.Loaded -> DayTabsScreen(
            days = state.days,
            today = viewModel.today,
            selectedDayIndex = viewModel.selectedDayIndex,
            onDaySelected = viewModel::selectDay,
            showNumericScore = showNumericScore,
            onToggleNumericScore = onToggleNumericScore,
            isRefreshing = viewModel.isRefreshing,
            onRefresh = viewModel::refresh,
            weights = weights,
            onSettingsClick = onSettingsClick,
            showWnba = showWnba,
            selectedLeague = selectedLeague,
            onLeagueSelected = onLeagueSelected,
            starredIds = starredIds,
            onToggleStar = onToggleStar,
            onWatchHighlights = onWatchHighlights
        )
    }
}

@Composable
private fun StarredTab(
    starredGamesViewModel: StarredGamesViewModel,
    showWnba: Boolean,
    selectedLeague: LeagueGroup,
    onLeagueSelected: (LeagueGroup) -> Unit,
    showNumericScore: Boolean,
    onToggleNumericScore: () -> Unit,
    sortBestFirst: Boolean,
    onToggleSort: () -> Unit,
    weights: RubricWeights,
    onWatchHighlights: (String) -> Unit,
    onSettingsClick: () -> Unit
) {
    // Fires on first composition and again whenever the starred set changes
    // (a star added/removed anywhere in the app) - re-fetches live data for
    // whatever's currently starred, same idea as GamesTab reloading on
    // league change.
    LaunchedEffect(starredGamesViewModel.starredIds) { starredGamesViewModel.refreshLiveData() }

    // Same cost reasoning as GamesTab's resume effect - refreshLiveData()
    // just re-hits /schedule for already-starred games, gated by the same
    // server-side caching, so refreshing on resume adds no real YouTube/LLM
    // work beyond what's already cached or cooldown-skipped.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { starredGamesViewModel.refreshLiveData() }

    StarredScreen(
        games = starredGamesViewModel.starredGames,
        showNumericScore = showNumericScore,
        onToggleNumericScore = onToggleNumericScore,
        sortBestFirst = sortBestFirst,
        onToggleSort = onToggleSort,
        weights = weights,
        starredIds = starredGamesViewModel.starredIds,
        onToggleStar = starredGamesViewModel::toggleStar,
        onWatchHighlights = onWatchHighlights,
        isRefreshing = starredGamesViewModel.isRefreshing,
        onRefresh = starredGamesViewModel::refreshLiveData,
        showWnba = showWnba,
        selectedLeague = selectedLeague,
        onLeagueSelected = onLeagueSelected,
        onSettingsClick = onSettingsClick
    )
}

@Composable
private fun HistoryTab(
    weights: RubricWeights,
    showWnba: Boolean,
    selectedLeague: LeagueGroup,
    onLeagueSelected: (LeagueGroup) -> Unit,
    effectiveLeagueGroup: LeagueGroup,
    showNumericScore: Boolean,
    onToggleNumericScore: () -> Unit,
    starredIds: Set<String>,
    onToggleStar: (com.nbawatchability.app.data.Game) -> Unit,
    onWatchHighlights: (String) -> Unit,
    onSettingsClick: () -> Unit
) {
    val viewModel: HistoryViewModel = viewModel()
    // The backfill only covers NBA so far - don't fire a request for a
    // league that has nothing to return; HistoryScreen shows a blank state
    // instead once effectiveLeagueGroup isn't NBA.
    LaunchedEffect(effectiveLeagueGroup) {
        if (effectiveLeagueGroup == LeagueGroup.NBA) viewModel.load()
    }

    HistoryScreen(
        uiState = viewModel.uiState,
        presets = viewModel.presets,
        selectedPreset = viewModel.selectedPreset,
        earliestDate = viewModel.earliestDate,
        onPresetSelected = viewModel::load,
        onRetry = viewModel::retry,
        showNumericScore = showNumericScore,
        onToggleNumericScore = onToggleNumericScore,
        weights = weights,
        starredIds = starredIds,
        onToggleStar = onToggleStar,
        onWatchHighlights = onWatchHighlights,
        showWnba = showWnba,
        selectedLeague = selectedLeague,
        onLeagueSelected = onLeagueSelected,
        leagueGroup = effectiveLeagueGroup,
        onSettingsClick = onSettingsClick
    )
}

@Composable
private fun LeadersTab(
    showWnba: Boolean,
    selectedLeague: LeagueGroup,
    onLeagueSelected: (LeagueGroup) -> Unit,
    effectiveLeagueGroup: LeagueGroup,
    onSettingsClick: () -> Unit
) {
    val standingsViewModel: StandingsViewModel = viewModel()
    val statsViewModel: StatsViewModel = viewModel()
    LaunchedEffect(effectiveLeagueGroup) {
        standingsViewModel.load(effectiveLeagueGroup)
        statsViewModel.load(effectiveLeagueGroup)
    }
    LeadersScreen(
        standingsUiState = standingsViewModel.uiState,
        onStandingsRetry = standingsViewModel::retry,
        statsUiState = statsViewModel.uiState,
        onStatsRetry = statsViewModel::retry,
        showWnba = showWnba,
        selectedLeague = selectedLeague,
        onLeagueSelected = onLeagueSelected,
        onSettingsClick = onSettingsClick
    )
}

@Composable
private fun NewsTab(
    showWnba: Boolean,
    selectedLeague: LeagueGroup,
    onLeagueSelected: (LeagueGroup) -> Unit,
    effectiveLeagueGroup: LeagueGroup,
    onSettingsClick: () -> Unit
) {
    val viewModel: NewsViewModel = viewModel()
    LaunchedEffect(effectiveLeagueGroup) { viewModel.load(effectiveLeagueGroup) }
    NewsScreen(
        uiState = viewModel.uiState,
        onRetry = viewModel::retry,
        showWnba = showWnba,
        selectedLeague = selectedLeague,
        onLeagueSelected = onLeagueSelected,
        onSettingsClick = onSettingsClick
    )
}

@Composable
private fun LoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorScreen(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Couldn't load games",
            color = TextPrimary,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = message,
            color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
        )
        Button(onClick = onRetry) { Text("Retry") }
    }
}
