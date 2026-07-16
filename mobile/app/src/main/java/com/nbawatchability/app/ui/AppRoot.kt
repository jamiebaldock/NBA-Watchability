package com.nbawatchability.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SportsBasketball
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.nbawatchability.app.data.SoccerRubricWeights
import com.nbawatchability.app.data.Tier
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.SurfaceCardElevated
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary
import com.nbawatchability.app.ui.theme.TierWorthYourTime

// Not private: AppSettingsRepository stores the default-landing-tab choice
// as a plain string (its .name) to avoid a data-layer -> ui-layer type
// dependency, but SettingsScreen.kt (same package) still needs the enum
// itself to build the picker UI.
enum class BottomNavTab(val label: String, val icon: ImageVector) {
    GAMES("Games", Icons.Default.SportsBasketball),
    STARRED("Starred", Icons.Default.Star),
    // Team-crest icon, distinct from Starred's star (favorite games) - this
    // tab is for favorite teams/players, a separate concept.
    MY_TEAMS("My Teams", Icons.Default.Shield),
    // Icon is a flame ("barn burner") even though the nav label stays
    // "History" - the in-screen title is the one that says "Past Barn
    // Burners" (HistoryScreen.kt).
    HISTORY("History", Icons.Default.LocalFireDepartment),
    // Standings + Stats merged into one swipeable tab (LeadersScreen.kt) -
    // "3 horizontal lines" reads as a list/menu, matching what's inside.
    LEADERS("Leaders", Icons.Default.Menu),
    NEWS("News", Icons.AutoMirrored.Filled.Article),
    // Rightmost, after News - moved here from a per-screen top-bar gear icon
    // so it's reachable from any tab with one tap rather than needing that
    // icon re-added to every screen individually.
    SETTINGS("Settings", Icons.Default.Settings)
}

/**
 * App-wide root: a persistent bottom navigation bar with 7 tabs, horizontally
 * scrollable (ScrollableBottomNavBar below) rather than shrinking each item
 * to fit - Material3's NavigationBar distributes equal width across however
 * many items it holds, which would visibly cramp 7 tabs onto one screen
 * width. My Teams and Settings are both global (not scoped to a league), so
 * they're dispatched before the isSupported gate below and never show
 * ComingSoonTab. Every other tab's top bar shows the same tappable league
 * selector so the league can be switched from anywhere, not just Games -
 * which leagues actually list there is controlled by Settings' "Selected
 * Sports" section (AppSettingsViewModel.settings.enabledLeagues). Four of
 * the six LeagueGroup entries (NBL, UFC, and others added since) are
 * placeholders with no backend data at all yet - selecting one
 * short-circuits every league-scoped tab straight to ComingSoonTab below
 * rather than firing a network call the backend doesn't recognize. "About"
 * isn't a tab - it's a slot's worth of nav real estate that Starred needed
 * more, so it lives one level deeper, opened from Settings instead.
 */
@Composable
fun AppRoot() {
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showRubricWeights by rememberSaveable { mutableStateOf(false) }
    var showSelectedSports by rememberSaveable { mutableStateOf(false) }
    var showFavoriteTeams by rememberSaveable { mutableStateOf(false) }
    var showFavoritePlayers by rememberSaveable { mutableStateOf(false) }
    var highlightsVideoId by rememberSaveable { mutableStateOf<String?>(null) }
    val settingsViewModel: RubricSettingsViewModel = viewModel()
    val soccerSettingsViewModel: SoccerRubricSettingsViewModel = viewModel()
    val appSettingsViewModel: AppSettingsViewModel = viewModel()
    val starredGamesViewModel: StarredGamesViewModel = viewModel()
    val favoritesViewModel: FavoritesViewModel = viewModel()

    // Persisted settings (last league, sort, numeric score) load from
    // DataStore asynchronously - rendering a tab before they arrive would
    // fire a request for the hardcoded default (NBA) that can then race
    // against, and sometimes overwrite, the real persisted choice.
    if (!appSettingsViewModel.isLoaded) {
        LoadingScreen()
        return
    }

    // Declared after the isLoaded gate above (not before, like every other
    // rememberSaveable here) so its initial value can actually read the
    // user's persisted default-landing-tab choice - this composable never
    // reaches this line at all while settings are still loading, so there's
    // no race between "first composition" and "settings arrived".
    var selectedTab by rememberSaveable {
        mutableStateOf(BottomNavTab.entries.find { it.name == appSettingsViewModel.settings.defaultLandingTab } ?: BottomNavTab.GAMES)
    }

    // Back from About/rating weights/Selected Sports returns to whatever tab
    // was active underneath (the Settings tab, typically) rather than
    // exiting the app.
    BackHandler(enabled = showAbout) { showAbout = false }
    BackHandler(enabled = showRubricWeights) { showRubricWeights = false }
    BackHandler(enabled = showSelectedSports) { showSelectedSports = false }
    BackHandler(enabled = showFavoriteTeams) { showFavoriteTeams = false }
    BackHandler(enabled = showFavoritePlayers) { showFavoritePlayers = false }
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
            soccerWeights = soccerSettingsViewModel.weights,
            onSoccerWeightChange = soccerSettingsViewModel::updateWeight,
            onSoccerReset = soccerSettingsViewModel::resetToDefaults,
            onBack = { showRubricWeights = false }
        )
        return
    }

    if (showSelectedSports) {
        SelectedSportsScreen(
            enabledLeagues = appSettingsViewModel.settings.enabledLeagues,
            onToggleLeague = appSettingsViewModel::toggleLeagueEnabled,
            onBack = { showSelectedSports = false }
        )
        return
    }

    if (showFavoriteTeams) {
        FavoriteTeamsScreen(
            favoriteTeamNames = favoritesViewModel.favoriteTeams.map { it.name }.toSet(),
            onToggleFavoriteTeam = favoritesViewModel::toggleFavoriteTeam,
            onBack = { showFavoriteTeams = false }
        )
        return
    }

    if (showFavoritePlayers) {
        FavoritePlayersScreen(
            favoritePlayerNames = favoritesViewModel.favoritePlayers.map { it.name }.toSet(),
            onToggleFavoritePlayer = favoritesViewModel::toggleFavoritePlayer,
            onBack = { showFavoritePlayers = false }
        )
        return
    }

    highlightsVideoId?.let { videoId ->
        HighlightsPlayerScreen(
            videoId = videoId,
            onBack = { highlightsVideoId = null },
            wifiOnlyEnabled = appSettingsViewModel.settings.wifiOnlyHighlights
        )
        return
    }

    Scaffold(
        containerColor = BackgroundBase,
        bottomBar = {
            ScrollableBottomNavBar(selectedTab = selectedTab, onTabSelected = { selectedTab = it })
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            val selectedLeague = appSettingsViewModel.settings.selectedLeague
            val enabledLeagues = appSettingsViewModel.settings.enabledLeagues

            // Settings and My Teams are both global (not scoped to a
            // league), so they're resolved before the isSupported gate below
            // - neither should ever show ComingSoonTab just because the
            // dropdown happens to be sitting on a placeholder league.
            when (selectedTab) {
                BottomNavTab.SETTINGS -> SettingsScreen(
                    showAllLeaguesInStarred = appSettingsViewModel.settings.showAllLeaguesInStarred,
                    onToggleShowAllLeaguesInStarred = appSettingsViewModel::toggleShowAllLeaguesInStarred,
                    onSelectedSportsClick = { showSelectedSports = true },
                    onRubricWeightsClick = { showRubricWeights = true },
                    onAboutClick = { showAbout = true },
                    onFavoriteTeamsClick = { showFavoriteTeams = true },
                    onFavoritePlayersClick = { showFavoritePlayers = true },
                    bumpFavoriteTeamGames = appSettingsViewModel.settings.bumpFavoriteTeamGames,
                    onToggleBumpFavoriteTeamGames = appSettingsViewModel::toggleBumpFavoriteTeamGames,
                    defaultLandingTab = BottomNavTab.entries.find { it.name == appSettingsViewModel.settings.defaultLandingTab } ?: BottomNavTab.GAMES,
                    onDefaultLandingTabChange = { appSettingsViewModel.setDefaultLandingTab(it.name) },
                    historyShowScoresByDefault = appSettingsViewModel.settings.historyShowScoresByDefault,
                    onToggleHistoryShowScoresByDefault = appSettingsViewModel::toggleHistoryShowScoresByDefault,
                    minTierFilterEnabled = appSettingsViewModel.settings.minTierFilterEnabled,
                    onToggleMinTierFilterEnabled = appSettingsViewModel::toggleMinTierFilterEnabled,
                    minTierFilter = Tier.entries.find { it.name == appSettingsViewModel.settings.minTierFilter } ?: Tier.SKIPPABLE,
                    onMinTierFilterChange = { appSettingsViewModel.setMinTierFilter(it.name) },
                    wifiOnlyHighlights = appSettingsViewModel.settings.wifiOnlyHighlights,
                    onToggleWifiOnlyHighlights = appSettingsViewModel::toggleWifiOnlyHighlights,
                    lightTheme = appSettingsViewModel.settings.lightTheme,
                    onToggleLightTheme = appSettingsViewModel::toggleLightTheme
                )
                BottomNavTab.MY_TEAMS -> MyTeamsScreen(
                    favoriteTeams = favoritesViewModel.favoriteTeams,
                    onRemoveFavoriteTeam = favoritesViewModel::toggleFavoriteTeam,
                    onAddTeamClick = { showFavoriteTeams = true },
                    favoritePlayers = favoritesViewModel.favoritePlayers,
                    onRemoveFavoritePlayer = favoritesViewModel::toggleFavoritePlayer,
                    onAddPlayerClick = { showFavoritePlayers = true }
                )
                else -> if (!selectedLeague.isSupported) {
                    ComingSoonTab(
                        selectedLeague = selectedLeague,
                        onLeagueSelected = appSettingsViewModel::setSelectedLeague,
                        enabledLeagues = enabledLeagues
                    )
                } else {
                    when (selectedTab) {
                        BottomNavTab.GAMES -> GamesTab(
                            weights = settingsViewModel.weights,
                            selectedLeague = selectedLeague,
                            onLeagueSelected = appSettingsViewModel::setSelectedLeague,
                            enabledLeagues = enabledLeagues,
                            showNumericScore = appSettingsViewModel.settings.showNumericScore,
                            onToggleNumericScore = appSettingsViewModel::toggleShowNumericScore,
                            starredIds = starredGamesViewModel.starredIds,
                            onToggleStar = starredGamesViewModel::toggleStar,
                            onWatchHighlights = { videoId -> highlightsVideoId = videoId },
                            favoriteTeamNames = favoritesViewModel.favoriteTeams.map { it.name }.toSet(),
                            bumpFavoriteTeamGames = appSettingsViewModel.settings.bumpFavoriteTeamGames,
                            onToggleFavoriteTeam = favoritesViewModel::toggleFavoriteTeam,
                            favoritePlayerNames = favoritesViewModel.favoritePlayers.map { it.name }.toSet(),
                            minTierFilterEnabled = appSettingsViewModel.settings.minTierFilterEnabled,
                            minTierFilter = Tier.entries.find { it.name == appSettingsViewModel.settings.minTierFilter } ?: Tier.SKIPPABLE,
                            soccerWeights = soccerSettingsViewModel.weights
                        )
                        BottomNavTab.LEADERS -> LeadersTab(
                            selectedLeague = selectedLeague,
                            onLeagueSelected = appSettingsViewModel::setSelectedLeague,
                            enabledLeagues = enabledLeagues
                        )
                        BottomNavTab.NEWS -> NewsTab(
                            selectedLeague = selectedLeague,
                            onLeagueSelected = appSettingsViewModel::setSelectedLeague,
                            enabledLeagues = enabledLeagues
                        )
                        BottomNavTab.STARRED -> StarredTab(
                            starredGamesViewModel = starredGamesViewModel,
                            selectedLeague = selectedLeague,
                            onLeagueSelected = appSettingsViewModel::setSelectedLeague,
                            enabledLeagues = enabledLeagues,
                            showNumericScore = appSettingsViewModel.settings.showNumericScore,
                            onToggleNumericScore = appSettingsViewModel::toggleShowNumericScore,
                            showAllLeagues = appSettingsViewModel.settings.showAllLeaguesInStarred,
                            onToggleAllLeagues = appSettingsViewModel::toggleShowAllLeaguesInStarred,
                            weights = settingsViewModel.weights,
                            onWatchHighlights = { videoId -> highlightsVideoId = videoId },
                            favoriteTeamNames = favoritesViewModel.favoriteTeams.map { it.name }.toSet(),
                            bumpFavoriteTeamGames = appSettingsViewModel.settings.bumpFavoriteTeamGames,
                            onToggleFavoriteTeam = favoritesViewModel::toggleFavoriteTeam,
                            favoritePlayerNames = favoritesViewModel.favoritePlayers.map { it.name }.toSet(),
                            minTierFilterEnabled = appSettingsViewModel.settings.minTierFilterEnabled,
                            minTierFilter = Tier.entries.find { it.name == appSettingsViewModel.settings.minTierFilter } ?: Tier.SKIPPABLE,
                            soccerWeights = soccerSettingsViewModel.weights
                        )
                        BottomNavTab.HISTORY -> HistoryTab(
                            weights = settingsViewModel.weights,
                            selectedLeague = selectedLeague,
                            onLeagueSelected = appSettingsViewModel::setSelectedLeague,
                            enabledLeagues = enabledLeagues,
                            showNumericScore = appSettingsViewModel.settings.showNumericScore,
                            onToggleNumericScore = appSettingsViewModel::toggleShowNumericScore,
                            starredIds = starredGamesViewModel.starredIds,
                            onToggleStar = starredGamesViewModel::toggleStar,
                            onWatchHighlights = { videoId -> highlightsVideoId = videoId },
                            favoriteTeamNames = favoritesViewModel.favoriteTeams.map { it.name }.toSet(),
                            bumpFavoriteTeamGames = appSettingsViewModel.settings.bumpFavoriteTeamGames,
                            onToggleFavoriteTeam = favoritesViewModel::toggleFavoriteTeam,
                            favoritePlayerNames = favoritesViewModel.favoritePlayers.map { it.name }.toSet(),
                            minTierFilterEnabled = appSettingsViewModel.settings.minTierFilterEnabled,
                            minTierFilter = Tier.entries.find { it.name == appSettingsViewModel.settings.minTierFilter } ?: Tier.SKIPPABLE,
                            showScoresByDefault = appSettingsViewModel.settings.historyShowScoresByDefault,
                            soccerWeights = soccerSettingsViewModel.weights
                        )
                        else -> {} // unreachable: SETTINGS/MY_TEAMS handled above
                    }
                }
            }
        }
    }
}

/**
 * Bottom nav, but horizontally scrollable rather than Material3's fixed
 * NavigationBar (which distributes equal width across however many items it
 * holds - 7 tabs at that would visibly cramp everything to fit one screen
 * width). Each item keeps a fixed, comfortable width instead, so the row is
 * simply wider than the screen and scrolls - same selected/unselected color
 * treatment as the NavigationBarItem this replaces (no visible "pill"
 * background behind the selected item, just an icon/label color change).
 */
@Composable
private fun ScrollableBottomNavBar(selectedTab: BottomNavTab, onTabSelected: (BottomNavTab) -> Unit) {
    Surface(color = SurfaceCardElevated) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomNavTab.entries.forEach { tab ->
                val selected = tab == selectedTab
                val tint = if (selected) TierWorthYourTime else TextSecondary
                Column(
                    modifier = Modifier
                        .width(76.dp)
                        .clickable { onTabSelected(tab) }
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(imageVector = tab.icon, contentDescription = tab.label, tint = tint)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = tab.label, color = tint, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun GamesTab(
    weights: RubricWeights,
    selectedLeague: LeagueGroup,
    onLeagueSelected: (LeagueGroup) -> Unit,
    enabledLeagues: Set<LeagueGroup>,
    showNumericScore: Boolean,
    onToggleNumericScore: () -> Unit,
    starredIds: Set<String>,
    onToggleStar: (com.nbawatchability.app.data.Game) -> Unit,
    onWatchHighlights: (String) -> Unit,
    favoriteTeamNames: Set<String>,
    bumpFavoriteTeamGames: Boolean,
    onToggleFavoriteTeam: (com.nbawatchability.app.data.Team) -> Unit,
    favoritePlayerNames: Set<String>,
    minTierFilterEnabled: Boolean,
    minTierFilter: Tier,
    soccerWeights: SoccerRubricWeights
) {
    val viewModel: GameListViewModel = viewModel()

    // Fires on first composition and again whenever the selected league
    // changes (a different league picked from the dropdown) - a full fresh
    // load, not a merge with whatever was showing.
    LaunchedEffect(selectedLeague) { viewModel.load(selectedLeague) }

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
        is ScheduleUiState.Error -> ErrorScreen(state.message, onRetry = { viewModel.load(selectedLeague) })
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
            selectedLeague = selectedLeague,
            onLeagueSelected = onLeagueSelected,
            enabledLeagues = enabledLeagues,
            starredIds = starredIds,
            onToggleStar = onToggleStar,
            onWatchHighlights = onWatchHighlights,
            isJumpingToNextGame = viewModel.isJumping,
            jumpToNextGameError = viewModel.jumpError,
            onJumpToNextGame = viewModel::jumpToNextGame,
            onJumpToNextGameErrorShown = viewModel::clearJumpError,
            isJumpingToToday = viewModel.isJumpingToToday,
            onJumpToToday = viewModel::jumpToToday,
            fullSeasonRange = viewModel.fullSeasonRange,
            datesWithGames = viewModel.datesWithGames,
            isJumpingToDate = viewModel.isJumpingToDate,
            onJumpToDate = viewModel::jumpToDate,
            favoriteTeamNames = favoriteTeamNames,
            bumpFavoriteTeamGames = bumpFavoriteTeamGames,
            onToggleFavoriteTeam = onToggleFavoriteTeam,
            favoritePlayerNames = favoritePlayerNames,
            minTierFilterEnabled = minTierFilterEnabled,
            minTierFilter = minTierFilter,
            soccerWeights = soccerWeights
        )
    }
}

@Composable
private fun StarredTab(
    starredGamesViewModel: StarredGamesViewModel,
    selectedLeague: LeagueGroup,
    onLeagueSelected: (LeagueGroup) -> Unit,
    enabledLeagues: Set<LeagueGroup>,
    showNumericScore: Boolean,
    onToggleNumericScore: () -> Unit,
    showAllLeagues: Boolean,
    onToggleAllLeagues: () -> Unit,
    weights: RubricWeights,
    onWatchHighlights: (String) -> Unit,
    favoriteTeamNames: Set<String>,
    bumpFavoriteTeamGames: Boolean,
    onToggleFavoriteTeam: (com.nbawatchability.app.data.Team) -> Unit,
    favoritePlayerNames: Set<String>,
    minTierFilterEnabled: Boolean,
    minTierFilter: Tier,
    soccerWeights: SoccerRubricWeights
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
        weights = weights,
        starredIds = starredGamesViewModel.starredIds,
        onToggleStar = starredGamesViewModel::toggleStar,
        onWatchHighlights = onWatchHighlights,
        isRefreshing = starredGamesViewModel.isRefreshing,
        onRefresh = starredGamesViewModel::refreshLiveData,
        selectedLeague = selectedLeague,
        onLeagueSelected = onLeagueSelected,
        enabledLeagues = enabledLeagues,
        showAllLeagues = showAllLeagues,
        onToggleAllLeagues = onToggleAllLeagues,
        favoriteTeamNames = favoriteTeamNames,
        bumpFavoriteTeamGames = bumpFavoriteTeamGames,
        onToggleFavoriteTeam = onToggleFavoriteTeam,
        favoritePlayerNames = favoritePlayerNames,
        minTierFilterEnabled = minTierFilterEnabled,
        minTierFilter = minTierFilter,
        soccerWeights = soccerWeights
    )
}

@Composable
private fun HistoryTab(
    weights: RubricWeights,
    selectedLeague: LeagueGroup,
    onLeagueSelected: (LeagueGroup) -> Unit,
    enabledLeagues: Set<LeagueGroup>,
    showNumericScore: Boolean,
    onToggleNumericScore: () -> Unit,
    starredIds: Set<String>,
    onToggleStar: (com.nbawatchability.app.data.Game) -> Unit,
    onWatchHighlights: (String) -> Unit,
    favoriteTeamNames: Set<String>,
    bumpFavoriteTeamGames: Boolean,
    onToggleFavoriteTeam: (com.nbawatchability.app.data.Team) -> Unit,
    favoritePlayerNames: Set<String>,
    minTierFilterEnabled: Boolean,
    minTierFilter: Tier,
    showScoresByDefault: Boolean,
    soccerWeights: SoccerRubricWeights
) {
    val viewModel: HistoryViewModel = viewModel()
    // Both leagues have their own backfill now - always resets to "This
    // season" on a league switch (rather than reusing whatever preset was
    // selected before), since a preset like a named NBA season ("2024-25")
    // isn't valid for WNBA (whose season labels are plain years) and vice
    // versa.
    LaunchedEffect(selectedLeague) {
        viewModel.load(selectedLeague, HistoryRangePreset.ThisSeason)
    }

    HistoryScreen(
        uiState = viewModel.uiState,
        presets = viewModel.presets,
        selectedPreset = viewModel.selectedPreset,
        earliestDate = viewModel.earliestDate,
        onPresetSelected = { viewModel.load(selectedLeague, it) },
        onRetry = viewModel::retry,
        showNumericScore = showNumericScore,
        onToggleNumericScore = onToggleNumericScore,
        weights = weights,
        starredIds = starredIds,
        onToggleStar = onToggleStar,
        onWatchHighlights = onWatchHighlights,
        selectedLeague = selectedLeague,
        onLeagueSelected = onLeagueSelected,
        enabledLeagues = enabledLeagues,
        favoriteTeamNames = favoriteTeamNames,
        bumpFavoriteTeamGames = bumpFavoriteTeamGames,
        onToggleFavoriteTeam = onToggleFavoriteTeam,
        favoritePlayerNames = favoritePlayerNames,
        minTierFilterEnabled = minTierFilterEnabled,
        minTierFilter = minTierFilter,
        showScoresByDefault = showScoresByDefault,
        soccerWeights = soccerWeights
    )
}

@Composable
private fun LeadersTab(
    selectedLeague: LeagueGroup,
    onLeagueSelected: (LeagueGroup) -> Unit,
    enabledLeagues: Set<LeagueGroup>
) {
    val standingsViewModel: StandingsViewModel = viewModel()
    val statsViewModel: StatsViewModel = viewModel()
    LaunchedEffect(selectedLeague) {
        standingsViewModel.load(selectedLeague)
        statsViewModel.load(selectedLeague)
    }
    LeadersScreen(
        standingsUiState = standingsViewModel.uiState,
        onStandingsRetry = standingsViewModel::retry,
        statsUiState = statsViewModel.uiState,
        onStatsRetry = statsViewModel::retry,
        selectedLeague = selectedLeague,
        onLeagueSelected = onLeagueSelected,
        enabledLeagues = enabledLeagues
    )
}

@Composable
private fun NewsTab(
    selectedLeague: LeagueGroup,
    onLeagueSelected: (LeagueGroup) -> Unit,
    enabledLeagues: Set<LeagueGroup>
) {
    val viewModel: NewsViewModel = viewModel()
    LaunchedEffect(selectedLeague) { viewModel.load(selectedLeague) }
    NewsScreen(
        uiState = viewModel.uiState,
        onRetry = viewModel::retry,
        selectedLeague = selectedLeague,
        onLeagueSelected = onLeagueSelected,
        enabledLeagues = enabledLeagues
    )
}

/**
 * Shown instead of any tab's real content when [selectedLeague] isn't
 * [LeagueGroup.isSupported] - one shared "coming soon" body for the 5
 * league-scoped tabs (Games, Starred, History, Leaders, News) rather than
 * special-casing each Screen file individually, since there's no per-tab
 * distinction to make anyway. My Teams and Settings are resolved before this
 * gate even runs (see AppRoot's dispatch above), so they're never replaced
 * by this. Keeps its own minimal top bar (league selector only - no gear
 * icon anymore, since Settings is a persistent bottom-nav tab reachable
 * regardless of which league is selected) so switching back to a supported
 * league is always one tap away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComingSoonTab(
    selectedLeague: LeagueGroup,
    onLeagueSelected: (LeagueGroup) -> Unit,
    enabledLeagues: Set<LeagueGroup>
) {
    Scaffold(
        containerColor = BackgroundBase,
        topBar = {
            TopAppBar(
                title = { TitleLeagueSelector(selectedLeague, onLeagueSelected, enabledLeagues) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "${selectedLeague.displayName} isn't built yet - check back later.",
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                style = MaterialTheme.typography.titleMedium
            )
        }
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
