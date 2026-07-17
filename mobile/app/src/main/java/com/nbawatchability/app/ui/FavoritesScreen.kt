package com.nbawatchability.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.nbawatchability.app.data.RubricWeights
import com.nbawatchability.app.data.SoccerRubricWeights
import com.nbawatchability.app.data.Team
import com.nbawatchability.app.data.effectiveScore
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextMuted
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary
import com.nbawatchability.app.ui.theme.TierWorthYourTime
import com.nbawatchability.app.ui.theme.themeAwareLogoUrl
import kotlinx.coroutines.launch

private val FAVORITES_PAGE_TITLES = listOf("Games", "Teams", "Players")

/**
 * The Favorites tab (formerly "My Teams") - 3 swipeable pages behind a
 * shared "Favorites" app bar, same TabRow-synced HorizontalPager pattern as
 * LeadersScreen.kt's Standings/Stats pair: games from any favorited team
 * across every league at once, then the existing favorite-teams and
 * favorite-players management lists (view/manage only - adding still
 * happens on their own dedicated Settings sub-screens, so there's one place
 * that owns "every team/player in a league").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    favoriteGamesUiState: FavoriteGamesUiState,
    onFavoriteGamesRetry: () -> Unit,
    showNumericScore: Boolean,
    onToggleNumericScore: () -> Unit,
    weights: RubricWeights,
    soccerWeights: SoccerRubricWeights,
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
    onToggleFavoritePlayer: (FavoritePlayer) -> Unit
) {
    val pagerState = rememberPagerState(initialPage = 0) { FAVORITES_PAGE_TITLES.size }
    val scope = rememberCoroutineScope()
    val favoriteTeamNames = favoriteTeams.map { it.name }.toSet()
    val favoritePlayerNames = favoritePlayers.map { it.name }.toSet()

    Scaffold(
        containerColor = BackgroundBase,
        topBar = {
            Column {
                TopAppBar(title = { Text("Favorites", color = TextPrimary) })
                TabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = BackgroundBase,
                    contentColor = TierWorthYourTime
                ) {
                    FAVORITES_PAGE_TITLES.forEachIndexed { index, title ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = {
                                Text(
                                    text = title,
                                    color = if (pagerState.currentPage == index) TierWorthYourTime else TextSecondary
                                )
                            }
                        )
                    }
                }
            }
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
                    showNumericScore = showNumericScore,
                    onToggleNumericScore = onToggleNumericScore,
                    weights = weights,
                    soccerWeights = soccerWeights,
                    starredIds = starredIds,
                    onToggleStar = onToggleStar,
                    onWatchHighlights = onWatchHighlights,
                    onGameClick = onGameClick,
                    favoriteTeamNames = favoriteTeamNames,
                    onToggleFavoriteTeam = onToggleFavoriteTeam,
                    favoritePlayerNames = favoritePlayerNames,
                    onToggleFavoritePlayer = onToggleFavoritePlayer
                )
                1 -> FavoriteTeamsPage(favoriteTeams, onRemoveFavoriteTeam, onAddTeamClick)
                else -> FavoritePlayersPage(favoritePlayers, onRemoveFavoritePlayer, onAddPlayerClick)
            }
        }
    }
}

/**
 * Every game involving a favorited team, any league, merged into one flat
 * list - defaults to upcoming (+ live) only, since that's the more useful
 * everyday view ("what's coming up for my teams"); the toggle reveals
 * already-finished games too. Sort reuses the exact same SortMenuButton/
 * SortOption control (and rating-sort's same unscored-games-fall-back-to-
 * newest-first treatment) already validated on Starred/History, not a new
 * option set invented for this page.
 */
@Composable
private fun FavoriteGamesPage(
    uiState: FavoriteGamesUiState,
    onRetry: () -> Unit,
    showNumericScore: Boolean,
    onToggleNumericScore: () -> Unit,
    weights: RubricWeights,
    soccerWeights: SoccerRubricWeights,
    starredIds: Set<String>,
    onToggleStar: (Game) -> Unit,
    onWatchHighlights: (String) -> Unit,
    onGameClick: (Game) -> Unit,
    favoriteTeamNames: Set<String>,
    onToggleFavoriteTeam: (Team) -> Unit,
    favoritePlayerNames: Set<String>,
    onToggleFavoritePlayer: (FavoritePlayer) -> Unit
) {
    var sortOption by remember { mutableStateOf(SortOption.DATE_OLDEST_FIRST) }
    var showPastGames by remember { mutableStateOf(false) }

    when (uiState) {
        is FavoriteGamesUiState.Loading -> LoadingBox()
        is FavoriteGamesUiState.Error -> ErrorBox(uiState.message, onRetry)
        is FavoriteGamesUiState.Loaded -> {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Show past games", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = showPastGames,
                            onCheckedChange = { showPastGames = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = TierWorthYourTime),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    SortMenuButton(selected = sortOption, onSelected = { sortOption = it })
                }

                val visibleGames = if (showPastGames) uiState.games else uiState.games.filter { it.status != GameStatus.FINAL }

                if (visibleGames.isEmpty()) {
                    EmptyBox(
                        if (uiState.games.isEmpty()) {
                            "No games yet for your favorited teams - add a favorite team to see their games here."
                        } else {
                            "No upcoming games right now - turn on \"Show past games\" to see finished ones."
                        }
                    )
                    return@Column
                }

                // Same unscored-games-fall-back-to-newest-first treatment as
                // Starred's own rating sort - an upcoming/live game has no
                // score to sort by yet.
                val ordered = when (sortOption) {
                    SortOption.RATING_HIGHEST_FIRST -> {
                        val (scored, unscored) = visibleGames.partition { it.effectiveScore(weights, soccerWeights) != null }
                        scored.sortedByDescending { it.effectiveScore(weights, soccerWeights) } + unscored.sortedByDescending { it.tipoffUtc }
                    }
                    SortOption.RATING_LOWEST_FIRST -> {
                        val (scored, unscored) = visibleGames.partition { it.effectiveScore(weights, soccerWeights) != null }
                        scored.sortedBy { it.effectiveScore(weights, soccerWeights) } + unscored.sortedByDescending { it.tipoffUtc }
                    }
                    SortOption.DATE_OLDEST_FIRST -> visibleGames.sortedBy { it.tipoffUtc }
                    SortOption.DATE_NEWEST_FIRST -> visibleGames.sortedByDescending { it.tipoffUtc }
                }

                val listState = rememberLazyListState()
                LaunchedEffect(sortOption, showPastGames) { listState.animateScrollToTopAdaptively() }

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
                            weights = weights,
                            isStarred = starredIds.contains(game.id),
                            onToggleStar = { onToggleStar(game) },
                            onWatchHighlights = onWatchHighlights,
                            showDate = true,
                            favoriteTeamNames = favoriteTeamNames,
                            onToggleFavoriteTeam = onToggleFavoriteTeam,
                            favoritePlayerNames = favoritePlayerNames,
                            onToggleFavoritePlayer = onToggleFavoritePlayer,
                            soccerWeights = soccerWeights,
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
 * FavoriteTeamsScreen (Settings), reached via [onAddTeamClick].
 */
@Composable
private fun FavoriteTeamsPage(favoriteTeams: List<Team>, onRemoveFavoriteTeam: (Team) -> Unit, onAddTeamClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        if (favoriteTeams.isEmpty()) {
            Text(
                text = "No favorite teams yet - add up to $MAX_FAVORITE_TEAMS per league to see them marked across the app.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
        } else {
            val teamsByLeague = favoriteTeams.groupBy { it.leagueGroup }
            LeagueGroup.entries.forEach { league ->
                val teamsInLeague = teamsByLeague[league.apiValue].orEmpty()
                if (teamsInLeague.isNotEmpty()) {
                    FavoriteTeamsLeagueGroup(league.displayName, teamsInLeague, onRemoveFavoriteTeam)
                }
            }
            val unknownLeagueTeams = teamsByLeague[null].orEmpty()
            if (unknownLeagueTeams.isNotEmpty()) {
                FavoriteTeamsLeagueGroup("Other", unknownLeagueTeams, onRemoveFavoriteTeam)
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
 * FavoritePlayersScreen (Settings), reached via [onAddPlayerClick].
 */
@Composable
private fun FavoritePlayersPage(favoritePlayers: List<FavoritePlayer>, onRemoveFavoritePlayer: (FavoritePlayer) -> Unit, onAddPlayerClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        if (favoritePlayers.isEmpty()) {
            Text(
                text = "No favorite players yet - add up to $MAX_FAVORITE_PLAYERS per league to get called out when they have a big game.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
        } else {
            val playersByLeague = favoritePlayers.groupBy { it.leagueGroup }
            LeagueGroup.entries.forEach { league ->
                val playersInLeague = playersByLeague[league.apiValue].orEmpty()
                if (playersInLeague.isNotEmpty()) {
                    FavoritePlayersLeagueGroup(league.displayName, playersInLeague, onRemoveFavoritePlayer)
                }
            }
            val unknownLeaguePlayers = playersByLeague[null].orEmpty()
            if (unknownLeaguePlayers.isNotEmpty()) {
                FavoritePlayersLeagueGroup("Other", unknownLeaguePlayers, onRemoveFavoritePlayer)
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        Button(onClick = onAddPlayerClick, modifier = Modifier.fillMaxWidth()) {
            Text("Add a favorite player")
        }
    }
}

@Composable
private fun FavoriteTeamsLeagueGroup(leagueLabel: String, teams: List<Team>, onRemoveFavoriteTeam: (Team) -> Unit) {
    Text(
        text = leagueLabel,
        color = TextSecondary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
    teams.forEach { team ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (team.logo != null) {
                    AsyncImage(model = themeAwareLogoUrl(team.logo), contentDescription = null, modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Text(text = team.name, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
            }
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove ${team.name}",
                tint = TextMuted,
                modifier = Modifier.size(22.dp).clickable { onRemoveFavoriteTeam(team) }
            )
        }
    }
}

@Composable
private fun FavoritePlayersLeagueGroup(leagueLabel: String, players: List<FavoritePlayer>, onRemoveFavoritePlayer: (FavoritePlayer) -> Unit) {
    Text(
        text = leagueLabel,
        color = TextSecondary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
    players.forEach { player ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                PlayerAvatar(name = player.name, headshotUrl = player.headshot)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(text = player.name, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                    Text(text = player.team, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove ${player.name}",
                tint = TextMuted,
                modifier = Modifier.size(22.dp).clickable { onRemoveFavoritePlayer(player) }
            )
        }
    }
}

/**
 * A real headshot photo when [headshotUrl] is present (NBA/WNBA only); a
 * tinted initials circle otherwise (EPL/La Liga, or any player favorited
 * before this field existed) - never a broken-image icon.
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
