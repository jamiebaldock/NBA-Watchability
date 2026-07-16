package com.nbawatchability.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.nbawatchability.app.data.FavoritePlayer
import com.nbawatchability.app.data.LeagueGroup
import com.nbawatchability.app.data.Player
import com.nbawatchability.app.data.Team
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextMuted
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary
import com.nbawatchability.app.ui.theme.TierInstantClassic
import com.nbawatchability.app.ui.theme.TierWorthYourTime
import com.nbawatchability.app.ui.theme.themeAwareLogoUrl

// Same 4-league scope as FavoriteTeamsScreen - /roster only has real data
// wherever /teams does, since both hit the same ESPN teams-list endpoint
// for the team-id step.
private enum class RosterBrowsableLeague(val apiValue: String, val label: String) {
    NBA("nba", "NBA"),
    WNBA("wnba", "WNBA"),
    EPL("epl", "EPL"),
    LA_LIGA("la-liga", "La Liga")
}

/**
 * Search/browse screen for favoriting players - reachable from Settings,
 * mirrors FavoriteTeamsScreen's shape but adds one more drill-down level
 * (league -> team -> player) since there's no single "every player in a
 * league" endpoint, only per-team rosters. Picking a team pushes into a
 * second in-screen step rather than a separate navigation destination, so
 * "back" from the player list returns to the team list, not out of the
 * whole screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritePlayersScreen(
    favoritePlayerNames: Set<String>,
    onToggleFavoritePlayer: (FavoritePlayer) -> Unit,
    onBack: () -> Unit
) {
    var selectedLeague by rememberSaveable { mutableStateOf(RosterBrowsableLeague.NBA) }
    var selectedTeam by rememberSaveable { mutableStateOf<Team?>(null) }
    var query by rememberSaveable { mutableStateOf("") }

    val teamsViewModel: TeamsViewModel = viewModel()
    val rosterViewModel: RosterViewModel = viewModel()

    LaunchedEffect(selectedLeague) {
        teamsViewModel.load(LeagueGroup.entries.first { it.apiValue == selectedLeague.apiValue })
    }
    LaunchedEffect(selectedTeam) {
        selectedTeam?.let { team ->
            rosterViewModel.load(LeagueGroup.entries.first { it.apiValue == selectedLeague.apiValue }, team.id)
        }
    }

    Scaffold(
        containerColor = BackgroundBase,
        topBar = {
            TopAppBar(
                title = { Text(selectedTeam?.name ?: "Favorite Players", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = { if (selectedTeam != null) { selectedTeam = null; query = "" } else onBack() }) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val team = selectedTeam
            if (team == null) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RosterBrowsableLeague.entries.forEach { league ->
                        FilterChip(
                            selected = league == selectedLeague,
                            onClick = { selectedLeague = league },
                            label = { Text(league.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = TierWorthYourTime,
                                selectedLabelColor = BackgroundBase
                            )
                        )
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search teams") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = TierWorthYourTime,
                        unfocusedBorderColor = TextMuted
                    )
                )

                when (val state = teamsViewModel.uiState) {
                    is TeamsUiState.Loading -> LoadingBox()
                    is TeamsUiState.Error -> ErrorBox(state.message, teamsViewModel::retry)
                    is TeamsUiState.Loaded -> {
                        val filtered = remember(state.data, query) {
                            state.data.teams.filter { it.name.contains(query, ignoreCase = true) }
                        }
                        if (filtered.isEmpty()) {
                            EmptyBox("No teams match \"$query\".")
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                items(filtered, key = { it.name }) { t ->
                                    PickableTeamRow(team = t, onClick = { selectedTeam = t; query = "" })
                                }
                            }
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search players") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = TierWorthYourTime,
                        unfocusedBorderColor = TextMuted
                    )
                )

                when (val state = rosterViewModel.uiState) {
                    is RosterUiState.Loading -> LoadingBox()
                    is RosterUiState.Error -> ErrorBox(state.message, rosterViewModel::retry)
                    is RosterUiState.Loaded -> {
                        val filtered = remember(state.data, query) {
                            state.data.players.filter { it.name.contains(query, ignoreCase = true) }
                        }
                        if (filtered.isEmpty()) {
                            EmptyBox("No players match \"$query\".")
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                items(filtered, key = { it.id }) { player ->
                                    PlayerRow(
                                        player = player,
                                        isFavorite = player.name in favoritePlayerNames,
                                        onToggle = { onToggleFavoritePlayer(FavoritePlayer(name = player.name, team = team.name)) }
                                    )
                                }
                            }
                        }
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
        Text("Couldn't load that", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
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
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            message,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(24.dp)
        )
    }
}

@Composable
private fun PickableTeamRow(team: Team, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (team.logo != null) {
            AsyncImage(model = themeAwareLogoUrl(team.logo), contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.width(12.dp))
        }
        Text(text = team.name, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PlayerRow(player: Player, isFavorite: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = player.name, color = TextPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(
            imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (isFavorite) "Remove favorite" else "Add favorite",
            tint = if (isFavorite) TierInstantClassic else TextMuted,
            modifier = Modifier.size(26.dp).clickable(onClick = onToggle)
        )
    }
}
