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
 * App-wide root: a persistent bottom navigation bar with 5 tabs. "Games"
 * hosts the existing schedule/day-tabs experience; the other four are
 * placeholder screens until those features get built out.
 */
@Composable
fun AppRoot() {
    var selectedTab by rememberSaveable { mutableStateOf(BottomNavTab.GAMES) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val settingsViewModel: RubricSettingsViewModel = viewModel()

    BackHandler(enabled = showSettings) { showSettings = false }

    if (showSettings) {
        SettingsScreen(
            weights = settingsViewModel.weights,
            onWeightChange = settingsViewModel::updateWeight,
            onReset = settingsViewModel::resetToDefaults,
            onBack = { showSettings = false }
        )
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
                    onSettingsClick = { showSettings = true }
                )
                BottomNavTab.STANDINGS -> PlaceholderScreen("Standings — coming soon.")
                BottomNavTab.STATS -> PlaceholderScreen("Stats — coming soon.")
                BottomNavTab.NEWS -> PlaceholderScreen("News — coming soon.")
                BottomNavTab.ABOUT -> PlaceholderScreen("About — coming soon.")
            }
        }
    }
}

@Composable
private fun GamesTab(weights: RubricWeights, onSettingsClick: () -> Unit) {
    val viewModel: GameListViewModel = viewModel()
    when (val state = viewModel.uiState) {
        is ScheduleUiState.Loading -> LoadingScreen()
        is ScheduleUiState.Error -> ErrorScreen(state.message, onRetry = viewModel::load)
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
            onSettingsClick = onSettingsClick
        )
    }
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
