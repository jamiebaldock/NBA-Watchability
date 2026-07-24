package com.nbawatchability.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nbawatchability.app.data.FavoritePlayer
import com.nbawatchability.app.data.Game
import com.nbawatchability.app.data.GameStatus
import com.nbawatchability.app.data.LeagueGroup
import com.nbawatchability.app.data.MlbRubricWeights
import com.nbawatchability.app.data.NflRubricWeights
import com.nbawatchability.app.data.NhlRubricWeights
import com.nbawatchability.app.data.RubricWeights
import com.nbawatchability.app.data.Team
import com.nbawatchability.app.data.effectiveScore
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextMuted
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary
import com.nbawatchability.app.ui.theme.TierWorthYourTime
import com.nbawatchability.app.ui.theme.themeAwareLogoUrl
import kotlinx.coroutines.launch

private val FAVORITES_PAGE_TITLES = listOf("Upcoming Games", "Past Games", "Teams", "Players")
// Player Hater Mode easter egg - a 5th page, appended only while
// LocalPlayerHaterMode is on (see pageTitles below). Never appears, never
// swipeable to, while the mode is off. Tab label is deliberately the emoji,
// not the word "hated" - James's call to keep the tab/copy softer even
// though the mode itself keeps its name.
private const val HATED_PLAYERS_PAGE_TITLE = "👎 Players"

/**
 * The Favorites tab (formerly "My Teams") - 4 swipeable pages (5 with
 * Player Hater Mode on) behind a shared "Favorites" app bar, same
 * TabRow-synced HorizontalPager pattern as LeadersScreen.kt's Standings/
 * Stats pair: favorited teams' upcoming games, their past games, then the
 * existing favorite-teams and favorite-players management lists (view/
 * manage only - adding still happens on their own dedicated Settings
 * sub-screens, so there's one place that owns "every team/player in a
 * league"), and finally - only while the easter egg is on - the hated-
 * players list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    favoriteGamesUiState: FavoriteGamesUiState,
    onFavoriteGamesRetry: () -> Unit,
    selectedLeague: LeagueGroup,
    onLeagueSelected: (LeagueGroup) -> Unit,
    isAllLeaguesSelected: Boolean,
    onAllLeaguesSelected: () -> Unit,
    enabledLeagues: Set<LeagueGroup>,
    showNumericScore: Boolean,
    onToggleNumericScore: () -> Unit,
    nbaWeights: RubricWeights,
    wnbaWeights: RubricWeights,
    mlbWeights: MlbRubricWeights,
    nflWeights: NflRubricWeights,
    nhlWeights: NhlRubricWeights,
    starredIds: Set<String>,
    onToggleStar: (Game) -> Unit,
    onWatchHighlights: (String) -> Unit,
    onGameClick: (Game) -> Unit,
    favoriteTeams: List<Team>,
    onRemoveFavoriteTeam: (Team) -> Unit,
    onAddTeamClick: () -> Unit,
    favoritePlayers: List<FavoritePlayer>,
    onRemoveFavoritePlayer: (FavoritePlayer) -> Unit,
    onAddPlayerClick: () -> Unit,
    onToggleFavoriteTeam: (Team) -> Unit,
    onToggleFavoritePlayer: (FavoritePlayer) -> Unit,
    // Hated Players page (5th page, Player Hater Mode only) - the on/off
    // flag itself comes from LocalPlayerHaterMode (read directly, already
    // in scope since this whole screen renders inside AppRoot's
    // CompositionLocalProvider); hatedPlayers is threaded explicitly since
    // this page needs the actual list to render, not just names.
    hatedPlayers: List<FavoritePlayer> = emptyList(),
    onRemoveHatedPlayer: (FavoritePlayer) -> Unit = {},
    onAddHatedPlayerClick: () -> Unit = {},
    belledGameIds: Set<String> = emptySet(),
    onToggleBell: (Game) -> Unit = {}
) {
    val playerHaterMode = LocalPlayerHaterMode.current
    val pageTitles = if (playerHaterMode) FAVORITES_PAGE_TITLES + HATED_PLAYERS_PAGE_TITLE else FAVORITES_PAGE_TITLES
    val pagerState = rememberPagerState(initialPage = 0) { pageTitles.size }
    val scope = rememberCoroutineScope()
    val favoriteTeamNames = favoriteTeams.map { it.name }.toSet()
    val favoritePlayerNames = favoritePlayers.map { it.name }.toSet()
    // Defensive: if Player Hater Mode gets turned off (from the hidden
    // SecretScreen, in a different tab) while the pager is actually sitting
    // on the now-removed 5th page, snap back to the first page rather than
    // leaving the pager pointed past the end of its own (now shorter) list.
    LaunchedEffect(playerHaterMode) {
        if (!playerHaterMode && pagerState.currentPage >= FAVORITES_PAGE_TITLES.size) {
            pagerState.scrollToPage(0)
        }
    }
    // Each Games page keeps its own independent sort - unlike
    // isAllLeaguesSelected (shared app-wide, see the param above), "oldest
    // first" for Upcoming and "newest first" for Past are both meaningful
    // defaults in their own right, not one shared decision.
    // Lifted up here (rather than owned locally per page, as before) since
    // the sort icon now lives in the single shared action row, which needs
    // to know which page's sort state it's currently controlling.
    var sortOptionUpcoming by remember { mutableStateOf(SortOption.DATE_OLDEST_FIRST) }
    var sortOptionPast by remember { mutableStateOf(SortOption.DATE_NEWEST_FIRST) }

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
                        onAllLeaguesSelected = onAllLeaguesSelected
                    )
                },
                actions = {
                    // Sort/hashtag only make sense on the two Games pages -
                    // Teams/Players have no sortable list and never show a
                    // GameCard tile.
                    if (pagerState.currentPage == 0 || pagerState.currentPage == 1) {
                        val onUpcoming = pagerState.currentPage == 0
                        SortMenuButton(
                            selected = if (onUpcoming) sortOptionUpcoming else sortOptionPast,
                            onSelected = { if (onUpcoming) sortOptionUpcoming = it else sortOptionPast = it },
                            // Upcoming games are always unscored, so rating
                            // order isn't a meaningful choice there - only
                            // Past Games keeps the # toggle.
                            showRatingToggle = !onUpcoming
                        )
                        NumericScoreToggleButton(checked = showNumericScore, onCheckedChange = { onToggleNumericScore() })
                    }
                },
                secondary = {
                    NavChipRow(
                        items = pageTitles,
                        // getOrElse, not direct indexing - momentarily safe
                        // against the one-frame window right after Player
                        // Hater Mode turns off, where pagerState.currentPage
                        // can still report the now-removed 5th page's index
                        // until the LaunchedEffect above's scrollToPage
                        // actually lands.
                        selected = pageTitles.getOrElse(pagerState.currentPage) { pageTitles.first() },
                        onSelected = { title ->
                            scope.launch { pagerState.animateScrollToPage(pageTitles.indexOf(title)) }
                        },
                        label = { it }
                    )
                }
            )
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) { page ->
            when (page) {
                0 -> FavoriteGamesPage(
                    uiState = favoriteGamesUiState,
                    onRetry = onFavoriteGamesRetry,
                    onlyPast = false,
                    selectedLeague = selectedLeague,
                    favoriteTeams = favoriteTeams,
                    showAllLeagues = isAllLeaguesSelected,
                    sortOption = sortOptionUpcoming,
                    showNumericScore = showNumericScore,
                    nbaWeights = nbaWeights,
                    wnbaWeights = wnbaWeights,
                    mlbWeights = mlbWeights,
                    nflWeights = nflWeights,
                    nhlWeights = nhlWeights,
                    starredIds = starredIds,
                    onToggleStar = onToggleStar,
                    onWatchHighlights = onWatchHighlights,
                    onGameClick = onGameClick,
                    favoriteTeamNames = favoriteTeamNames,
                    onToggleFavoriteTeam = onToggleFavoriteTeam,
                    favoritePlayerNames = favoritePlayerNames,
                    onToggleFavoritePlayer = onToggleFavoritePlayer,
                    belledGameIds = belledGameIds,
                    onToggleBell = onToggleBell
                )
                1 -> FavoriteGamesPage(
                    uiState = favoriteGamesUiState,
                    onRetry = onFavoriteGamesRetry,
                    onlyPast = true,
                    selectedLeague = selectedLeague,
                    favoriteTeams = favoriteTeams,
                    showAllLeagues = isAllLeaguesSelected,
                    sortOption = sortOptionPast,
                    showNumericScore = showNumericScore,
                    nbaWeights = nbaWeights,
                    wnbaWeights = wnbaWeights,
                    mlbWeights = mlbWeights,
                    nflWeights = nflWeights,
                    nhlWeights = nhlWeights,
                    starredIds = starredIds,
                    onToggleStar = onToggleStar,
                    onWatchHighlights = onWatchHighlights,
                    onGameClick = onGameClick,
                    favoriteTeamNames = favoriteTeamNames,
                    onToggleFavoriteTeam = onToggleFavoriteTeam,
                    favoritePlayerNames = favoritePlayerNames,
                    onToggleFavoritePlayer = onToggleFavoritePlayer,
                    belledGameIds = belledGameIds,
                    onToggleBell = onToggleBell
                )
                2 -> FavoriteTeamsPage(
                    favoriteTeams = favoriteTeams,
                    selectedLeague = selectedLeague,
                    showAllLeagues = isAllLeaguesSelected,
                    onRemoveFavoriteTeam = onRemoveFavoriteTeam,
                    onAddTeamClick = onAddTeamClick
                )
                3 -> FavoritePlayersPage(
                    favoritePlayers = favoritePlayers,
                    selectedLeague = selectedLeague,
                    showAllLeagues = isAllLeaguesSelected,
                    onRemoveFavoritePlayer = onRemoveFavoritePlayer,
                    onAddPlayerClick = onAddPlayerClick
                )
                // Only reachable while playerHaterMode is on (pageTitles/
                // pagerState's own pageCount excludes this page otherwise) -
                // the else branch (rather than an explicit "4") is just
                // defensive bounds safety for the one-frame window covered
                // by the LaunchedEffect above.
                else -> HatedPlayersPage(
                    hatedPlayers = hatedPlayers,
                    selectedLeague = selectedLeague,
                    showAllLeagues = isAllLeaguesSelected,
                    onRemoveHatedPlayer = onRemoveHatedPlayer,
                    onAddHatedPlayerClick = onAddHatedPlayerClick
                )
            }
        }
    }
}

/**
 * Every game involving a favorited team, any league, merged into one flat
 * list - filtered down to just [onlyPast]'s half (upcoming+live, or
 * finished), since Upcoming Games and Past Games are separate pages rather
 * than one list with a reveal toggle. [showAllLeagues] scopes this further:
 * off restricts to favorited teams whose own leagueGroup matches
 * [selectedLeague] (the app's shared league dropdown), entirely client-side
 * against the already-fetched merged list - no refetch on toggle, since the
 * data for every league is already in memory. Sort reuses the exact same
 * SortMenuButton/SortOption control (and rating-sort's same
 * unscored-games-fall-back-to-newest-first treatment) already validated on
 * Starred/History, not a new option set invented for this page.
 */
@Composable
private fun FavoriteGamesPage(
    uiState: FavoriteGamesUiState,
    onRetry: () -> Unit,
    onlyPast: Boolean,
    selectedLeague: LeagueGroup,
    favoriteTeams: List<Team>,
    showAllLeagues: Boolean,
    sortOption: SortOption,
    showNumericScore: Boolean,
    nbaWeights: RubricWeights,
    wnbaWeights: RubricWeights,
    mlbWeights: MlbRubricWeights,
    nflWeights: NflRubricWeights,
    nhlWeights: NhlRubricWeights,
    starredIds: Set<String>,
    onToggleStar: (Game) -> Unit,
    onWatchHighlights: (String) -> Unit,
    onGameClick: (Game) -> Unit,
    favoriteTeamNames: Set<String>,
    onToggleFavoriteTeam: (Team) -> Unit,
    favoritePlayerNames: Set<String>,
    onToggleFavoritePlayer: (FavoritePlayer) -> Unit,
    belledGameIds: Set<String>,
    onToggleBell: (Game) -> Unit
) {
    when (uiState) {
        is FavoriteGamesUiState.Loading -> LoadingBox()
        is FavoriteGamesUiState.Error -> ErrorBox(uiState.message, onRetry)
        is FavoriteGamesUiState.Loaded -> {
            Column(modifier = Modifier.fillMaxSize()) {
                val namesInSelectedLeague = remember(favoriteTeams, selectedLeague) {
                    favoriteTeams.filter { it.leagueGroup == selectedLeague.apiValue }.map { it.name }.toSet()
                }
                val scopedGames = if (showAllLeagues) {
                    uiState.games
                } else {
                    uiState.games.filter { it.away in namesInSelectedLeague || it.home in namesInSelectedLeague }
                }
                val visibleGames = scopedGames.filter { (it.status == GameStatus.FINAL) == onlyPast }

                if (visibleGames.isEmpty()) {
                    EmptyBox(
                        when {
                            uiState.games.isEmpty() ->
                                "No games yet for your favorited teams - add a favorite team to see their games here."
                            scopedGames.isEmpty() ->
                                "No games for your favorited teams in ${selectedLeague.shortDisplayName} right now - pick \"All Leagues\" from the dropdown to see the rest."
                            onlyPast ->
                                "No past games yet - check the Upcoming Games page to see what's next."
                            else ->
                                "No upcoming games right now - check the Past Games page to see finished ones."
                        }
                    )
                    return@Column
                }

                // Same unscored-games-fall-back-to-newest-first treatment as
                // Starred's own rating sort - an upcoming/live game has no
                // score to sort by yet.
                val ordered = when (sortOption) {
                    SortOption.RATING_HIGHEST_FIRST -> {
                        val (scored, unscored) = visibleGames.partition { it.effectiveScore(nbaWeights, wnbaWeights, mlbWeights, nflWeights, nhlWeights) != null }
                        scored.sortedByDescending { it.effectiveScore(nbaWeights, wnbaWeights, mlbWeights, nflWeights, nhlWeights) } + unscored.sortedByDescending { it.tipoffUtc }
                    }
                    SortOption.RATING_LOWEST_FIRST -> {
                        val (scored, unscored) = visibleGames.partition { it.effectiveScore(nbaWeights, wnbaWeights, mlbWeights, nflWeights, nhlWeights) != null }
                        scored.sortedBy { it.effectiveScore(nbaWeights, wnbaWeights, mlbWeights, nflWeights, nhlWeights) } + unscored.sortedByDescending { it.tipoffUtc }
                    }
                    SortOption.DATE_OLDEST_FIRST -> visibleGames.sortedBy { it.tipoffUtc }
                    SortOption.DATE_NEWEST_FIRST -> visibleGames.sortedByDescending { it.tipoffUtc }
                }

                val listState = rememberLazyListState()
                LaunchedEffect(sortOption, showAllLeagues) { listState.animateScrollToTopAdaptively() }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(ordered, key = { it.id ?: "${it.tipoffUtc}-${it.away}-${it.home}" }) { game ->
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
                            showBell = true,
                            isBelled = game.eventId != null && belledGameIds.contains(game.eventId),
                            onToggleBell = { onToggleBell(game) },
                            onWatchHighlights = onWatchHighlights,
                            showDate = true,
                            favoriteTeamNames = favoriteTeamNames,
                            onToggleFavoriteTeam = onToggleFavoriteTeam,
                            favoritePlayerNames = favoritePlayerNames,
                            onToggleFavoritePlayer = onToggleFavoritePlayer,
                            onGameClick = onGameClick
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingBox() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) { CircularProgressIndicator() }
}

@Composable
private fun ErrorBox(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Couldn't load your favorites' games", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
        Text(
            message,
            color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
        )
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun EmptyBox(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            message,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(24.dp)
        )
    }
}

/**
 * The favorite-teams management list (view/remove, grouped by league) -
 * moved as-is from the old MyTeamsScreen, now one page of the Favorites
 * pager instead of half of a single stacked screen. Adding still happens on
 * FavoriteTeamsScreen (Settings), reached via [onAddTeamClick]. Scoped by
 * [selectedLeague]/[showAllLeagues] the same way FavoriteGamesPage already
 * was - this page used to ignore the shared league dropdown entirely and
 * always show every league's group (James's bug report, 2026-07-24).
 */
@Composable
private fun FavoriteTeamsPage(
    favoriteTeams: List<Team>,
    selectedLeague: LeagueGroup,
    showAllLeagues: Boolean,
    onRemoveFavoriteTeam: (Team) -> Unit,
    onAddTeamClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        val leaguesToShow = if (showAllLeagues) LeagueGroup.entries else listOf(selectedLeague)
        val teamsByLeague = favoriteTeams.groupBy { it.leagueGroup }
        val visibleTeamCount = leaguesToShow.sumOf { teamsByLeague[it.apiValue].orEmpty().size }

        if (favoriteTeams.isEmpty()) {
            Text(
                text = "No favorite teams yet - add up to $MAX_FAVORITE_TEAMS per league to see them marked across the app.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
        } else if (visibleTeamCount == 0) {
            Text(
                text = "No favorite teams in ${selectedLeague.shortDisplayName} - pick \"All Leagues\" from the dropdown to see the rest.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
        } else {
            leaguesToShow.forEach { league ->
                val teamsInLeague = teamsByLeague[league.apiValue].orEmpty()
                if (teamsInLeague.isNotEmpty()) {
                    FavoriteTeamsLeagueGroup(league.displayName, teamsInLeague, onRemoveFavoriteTeam)
                }
            }
            if (showAllLeagues) {
                val unknownLeagueTeams = teamsByLeague[null].orEmpty()
                if (unknownLeagueTeams.isNotEmpty()) {
                    FavoriteTeamsLeagueGroup("Other", unknownLeagueTeams, onRemoveFavoriteTeam)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        Button(onClick = onAddTeamClick, modifier = Modifier.fillMaxWidth()) {
            Text("Add a favorite team")
        }
    }
}

/**
 * The favorite-players management list (view/remove, grouped by league) -
 * moved as-is from the old MyTeamsScreen. Adding still happens on
 * FavoritePlayersScreen (Settings), reached via [onAddPlayerClick]. Scoped
 * by [selectedLeague]/[showAllLeagues] the same way FavoriteTeamsPage now
 * is - same bug, same fix.
 */
@Composable
private fun FavoritePlayersPage(
    favoritePlayers: List<FavoritePlayer>,
    selectedLeague: LeagueGroup,
    showAllLeagues: Boolean,
    onRemoveFavoritePlayer: (FavoritePlayer) -> Unit,
    onAddPlayerClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        val leaguesToShow = if (showAllLeagues) LeagueGroup.entries else listOf(selectedLeague)
        val playersByLeague = favoritePlayers.groupBy { it.leagueGroup }
        val visiblePlayerCount = leaguesToShow.sumOf { playersByLeague[it.apiValue].orEmpty().size }

        if (favoritePlayers.isEmpty()) {
            Text(
                text = "No favorite players yet - add up to $MAX_FAVORITE_PLAYERS per league to get called out when they have a big game.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
        } else if (visiblePlayerCount == 0) {
            Text(
                text = "No favorite players in ${selectedLeague.shortDisplayName} - pick \"All Leagues\" from the dropdown to see the rest.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
        } else {
            leaguesToShow.forEach { league ->
                val playersInLeague = playersByLeague[league.apiValue].orEmpty()
                if (playersInLeague.isNotEmpty()) {
                    FavoritePlayersLeagueGroup(league.displayName, playersInLeague, onRemoveFavoritePlayer)
                }
            }
            if (showAllLeagues) {
                val unknownLeaguePlayers = playersByLeague[null].orEmpty()
                if (unknownLeaguePlayers.isNotEmpty()) {
                    FavoritePlayersLeagueGroup("Other", unknownLeaguePlayers, onRemoveFavoritePlayer)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        Button(onClick = onAddPlayerClick, modifier = Modifier.fillMaxWidth()) {
            Text("Add a favorite player")
        }
    }
}

/**
 * Swipe-left-to-reveal-delete (SwipeToRevealRow) replaced the old persistent
 * "X" icon here - see that file's own doc comment for why a hand-rolled drag
 * implementation instead of material3's SwipeToDismissBox.
 */
@Composable
private fun FavoriteTeamsLeagueGroup(leagueLabel: String, teams: List<Team>, onRemoveFavoriteTeam: (Team) -> Unit) {
    Text(
        text = leagueLabel,
        color = TextSecondary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
    teams.forEach { team ->
        SwipeToRevealRow(onDelete = { onRemoveFavoriteTeam(team) }, modifier = Modifier.padding(vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (team.logo != null) {
                    AsyncImage(model = themeAwareLogoUrl(team.logo), contentDescription = null, modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Text(text = team.name, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/**
 * Same swipe-to-reveal-delete as FavoriteTeamsLeagueGroup above - that's the
 * only removal gesture this list needs. An earlier version also had a
 * Player Hater Mode "hate on this player" checkbox here, letting you mark a
 * player hated without leaving this screen - removed (James's call) once it
 * became redundant clutter: it wasn't wired to anything removal-related,
 * and the dedicated Hated Players page + its own add flow (a thumbs-down,
 * FavoritePlayersScreen's PlayerPickerMode.HATE) already covers that.
 */
@Composable
private fun FavoritePlayersLeagueGroup(leagueLabel: String, players: List<FavoritePlayer>, onRemoveFavoritePlayer: (FavoritePlayer) -> Unit) {
    Text(
        text = leagueLabel,
        color = TextSecondary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
    players.forEach { player ->
        SwipeToRevealRow(onDelete = { onRemoveFavoritePlayer(player) }, modifier = Modifier.padding(vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayerAvatar(name = player.name, headshotUrl = player.headshot)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(text = player.name, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                    Text(text = player.team, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * Player Hater Mode's own management list (view/remove, grouped by league) -
 * the 5th Favorites page, mirrors FavoriteTeamsPage/FavoritePlayersPage's
 * league-scoping shape exactly. Adding happens on FavoritePlayersScreen in
 * PlayerPickerMode.HATE (reached via [onAddHatedPlayerClick]), same
 * search/browse flow as favoriting a player, just a thumbs-down instead of
 * a heart as the selection indicator there.
 */
@Composable
private fun HatedPlayersPage(
    hatedPlayers: List<FavoritePlayer>,
    selectedLeague: LeagueGroup,
    showAllLeagues: Boolean,
    onRemoveHatedPlayer: (FavoritePlayer) -> Unit,
    onAddHatedPlayerClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        val leaguesToShow = if (showAllLeagues) LeagueGroup.entries else listOf(selectedLeague)
        val playersByLeague = hatedPlayers.groupBy { it.leagueGroup }
        val visiblePlayerCount = leaguesToShow.sumOf { playersByLeague[it.apiValue].orEmpty().size }

        if (hatedPlayers.isEmpty()) {
            Text(
                text = "Nobody here yet - add up to $MAX_HATED_PLAYERS players you're not a fan of to get them roasted instead of celebrated.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
        } else if (visiblePlayerCount == 0) {
            Text(
                text = "None in ${selectedLeague.shortDisplayName} - pick \"All Leagues\" from the dropdown to see the rest.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
        } else {
            leaguesToShow.forEach { league ->
                val playersInLeague = playersByLeague[league.apiValue].orEmpty()
                if (playersInLeague.isNotEmpty()) {
                    HatedPlayersLeagueGroup(league.displayName, playersInLeague, onRemoveHatedPlayer)
                }
            }
            if (showAllLeagues) {
                val unknownLeaguePlayers = playersByLeague[null].orEmpty()
                if (unknownLeaguePlayers.isNotEmpty()) {
                    HatedPlayersLeagueGroup("Other", unknownLeaguePlayers, onRemoveHatedPlayer)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        Button(onClick = onAddHatedPlayerClick, modifier = Modifier.fillMaxWidth()) {
            Text("Add a player you can't stand 👎")
        }
    }
}

/**
 * Same swipe-to-reveal-delete shape as FavoriteTeamsLeagueGroup/
 * FavoritePlayersLeagueGroup - no hate checkbox here (unlike
 * FavoritePlayersLeagueGroup's), since every row on this page is already
 * hated by definition; swiping to delete *is* the un-hate action.
 */
@Composable
private fun HatedPlayersLeagueGroup(leagueLabel: String, players: List<FavoritePlayer>, onRemoveHatedPlayer: (FavoritePlayer) -> Unit) {
    Text(
        text = leagueLabel,
        color = TextSecondary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
    players.forEach { player ->
        SwipeToRevealRow(onDelete = { onRemoveHatedPlayer(player) }, modifier = Modifier.padding(vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayerAvatar(name = player.name, headshotUrl = player.headshot)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(text = player.name, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                    Text(text = player.team, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * A real headshot photo when [headshotUrl] is present (NBA/WNBA/MLB/NFL/NHL
 * all carry one); a tinted initials circle otherwise (any player favorited
 * before this field existed, or one ESPN itself has no photo for) - never a
 * broken-image icon.
 */
@Composable
private fun PlayerAvatar(name: String, headshotUrl: String?) {
    if (headshotUrl != null) {
        AsyncImage(
            model = headshotUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(36.dp).clip(CircleShape)
        )
    } else {
        val initials = name.split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString("")
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape).background(TierWorthYourTime.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = initials, color = TierWorthYourTime, style = MaterialTheme.typography.labelMedium, fontSize = 13.sp)
        }
    }
}
