package com.nbawatchability.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.SportsBasketball
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
    STANDINGS("Standings", Icons.Default.Leaderboard),
    STATS("Stats", Icons.Default.BarChart),
    NEWS("News", Icons.AutoMirrored.Filled.Article),
    ABOUT("About", Icons.Default.Info)
}

/**
 * App-wide root: a persistent bottom navigation bar with 5 tabs. Games,
 * Standings, Stats, and News all react to the same global league selection
 * (Settings' "Show WNBA" toggle + the title dropdown); "About" remains a
 * placeholder.
 */
@Composable
fun AppRoot() {
    var selectedTab by rememberSaveable { mutableStateOf(BottomNavTab.GAMES) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var highlightsVideoId by rememberSaveable { mutableStateOf<String?>(null) }
    val settingsViewModel: RubricSettingsViewModel = viewModel()
    val appSettingsViewModel: AppSettingsViewModel = viewModel()

    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = highlightsVideoId != null) { highlightsVideoId = null }

    if (showSettings) {
        SettingsScreen(
            weights = settingsViewModel.weights,
            onWeightChange = settingsViewModel::updateWeight,
            onReset = settingsViewModel::resetToDefaults,
            showWnba = appSettingsViewModel.settings.showWnba,
            onShowWnbaChange = appSettingsViewModel::setShowWnba,
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
                    onWatchHighlights = { videoId -> highlightsVideoId = videoId }
                )
                BottomNavTab.STANDINGS -> StandingsTab(appSettingsViewModel.settings.effectiveLeagueGroup)
                BottomNavTab.STATS -> StatsTab(appSettingsViewModel.settings.effectiveLeagueGroup)
                BottomNavTab.NEWS -> NewsTab(appSettingsViewModel.settings.effectiveLeagueGroup)
                BottomNavTab.ABOUT -> PlaceholderScreen("About — coming soon.")
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
    onWatchHighlights: (String) -> Unit
) {
    val viewModel: GameListViewModel = viewModel()

    // Fires on first composition and again whenever the effective league
    // changes (toggle flipped, or a different league picked from the
    // dropdown) - a full fresh load, not a merge with whatever was showing.
    LaunchedEffect(effectiveLeagueGroup) { viewModel.load(effectiveLeagueGroup) }

    when (val state = viewModel.uiState) {
        is ScheduleUiState.Loading -> LoadingScreen()
        is ScheduleUiState.Error -> ErrorScreen(state.message, onRetry = { viewModel.load(effectiveLeagueGroup) })
        is ScheduleUiState.Loaded -> DayTabsScreen(
            days = state.days,
            today = viewModel.today,
            selectedDayIndex = viewModel.selectedDayIndex,
            onDaySelected = viewModel::selectDay,
            showNumericScore = viewModel.showNumericScore,
            onToggleNumericScore = viewModel::toggleNumericScore,
            sortBestFirst = viewModel.sortBestFirst,
            onToggleSort = viewModel::toggleSortBestFirst,
            isRefreshing = viewModel.isRefreshing,
            onRefresh = viewModel::refresh,
            weights = weights,
            onSettingsClick = onSettingsClick,
            showWnba = showWnba,
            selectedLeague = selectedLeague,
            onLeagueSelected = onLeagueSelected,
            onWatchHighlights = onWatchHighlights
        )
    }
}

@Composable
private fun StandingsTab(leagueGroup: LeagueGroup) {
    val viewModel: StandingsViewModel = viewModel()
    LaunchedEffect(leagueGroup) { viewModel.load(leagueGroup) }
    StandingsScreen(uiState = viewModel.uiState, onRetry = viewModel::retry)
}

@Composable
private fun StatsTab(leagueGroup: LeagueGroup) {
    val viewModel: StatsViewModel = viewModel()
    LaunchedEffect(leagueGroup) { viewModel.load(leagueGroup) }
    StatsScreen(uiState = viewModel.uiState, onRetry = viewModel::retry)
}

@Composable
private fun NewsTab(leagueGroup: LeagueGroup) {
    val viewModel: NewsViewModel = viewModel()
    LaunchedEffect(leagueGroup) { viewModel.load(leagueGroup) }
    NewsScreen(uiState = viewModel.uiState, onRetry = viewModel::retry)
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

@Composable
private fun PlaceholderScreen(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = text,
            color = TextSecondary,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp)
        )
    }
}
