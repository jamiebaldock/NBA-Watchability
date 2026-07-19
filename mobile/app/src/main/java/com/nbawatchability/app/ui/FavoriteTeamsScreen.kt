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
import com.nbawatchability.app.data.LeagueGroup
import com.nbawatchability.app.data.Team
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextMuted
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary
import com.nbawatchability.app.ui.theme.TierInstantClassic
import com.nbawatchability.app.ui.theme.TierWorthYourTime
import com.nbawatchability.app.ui.theme.themeAwareLogoUrl

// Same core set as Settings' "Selected Leagues" (NBA/WNBA/NHL/MLB/NFL) -
// but /teams only actually has real data for NBA/WNBA today, so picking one
// of the still-isSupported=false leagues below shows the same "not built
// yet" message as ComingSoonTab rather than firing a network call the
// backend has no route for.
private val BROWSABLE_LEAGUES = listOf(LeagueGroup.NBA, LeagueGroup.WNBA, LeagueGroup.NHL, LeagueGroup.MLB, LeagueGroup.NFL)

/**
 * Search/browse screen for favoriting teams - reachable from Settings. Has
 * its own league picker (not tied to the app's shared league dropdown),
 * since a user browsing to add a favorite shouldn't need to first change
 * what the rest of the app is showing. Favorites are capped at
 * MAX_FAVORITE_TEAMS *per league* (enforced by FavoritesViewModel.
 * toggleFavoriteTeam, which surfaces a Toast if this screen's tap would
 * exceed it) - so up to MAX_FAVORITE_TEAMS in NBA and MAX_FAVORITE_TEAMS in
 * EPL simultaneously, not MAX_FAVORITE_TEAMS total. The team passed to
 * onToggleFavoriteTeam is tagged with [selectedLeague] right here (the
 * backend's /teams response has no leagueGroup field of its own), since
 * this chip is the only place that actually knows which league a team on
 * this screen belongs to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoriteTeamsScreen(
    favoriteTeamNames: Set<String>,
    onToggleFavoriteTeam: (Team) -> Unit,
    onBack: () -> Unit
) {
    var selectedLeague by rememberSaveable { mutableStateOf(LeagueGroup.NBA) }
    var query by rememberSaveable { mutableStateOf("") }
    val viewModel: TeamsViewModel = viewModel()

    LaunchedEffect(selectedLeague) {
        if (selectedLeague.isSupported) viewModel.load(selectedLeague)
    }

    Scaffold(
        containerColor = BackgroundBase,
        topBar = {
            TopAppBar(
                title = { Text("Favorite Teams", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BROWSABLE_LEAGUES.forEach { league ->
                    FilterChip(
                        selected = league == selectedLeague,
                        onClick = { selectedLeague = league },
                        label = { Text(league.shortDisplayName) },
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

            if (!selectedLeague.isSupported) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "${selectedLeague.displayName} isn't built yet - check back later.",
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                return@Column
            }

            when (val state = viewModel.uiState) {
                is TeamsUiState.Loading -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) { CircularProgressIndicator() }

                is TeamsUiState.Error -> Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Couldn't load teams", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.message,
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                    )
                    Button(onClick = viewModel::retry) { Text("Retry") }
                }

                is TeamsUiState.Loaded -> {
                    val filtered = remember(state.data, query) {
                        state.data.teams.filter { it.name.contains(query, ignoreCase = true) }
                    }
                    if (filtered.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "No teams match \"$query\".",
                                color = TextSecondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(24.dp)
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            items(filtered, key = { it.name }) { team ->
                                TeamRow(
                                    team = team,
                                    isFavorite = team.name in favoriteTeamNames,
                                    onToggle = { onToggleFavoriteTeam(team.copy(leagueGroup = selectedLeague.apiValue)) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TeamRow(team: Team, isFavorite: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            if (team.logo != null) {
                AsyncImage(model = themeAwareLogoUrl(team.logo), contentDescription = null, modifier = Modifier.size(28.dp))
                Spacer(modifier = Modifier.width(12.dp))
            }
            Text(text = team.name, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
        }
        Icon(
            imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (isFavorite) "Remove favorite" else "Add favorite",
            tint = if (isFavorite) TierInstantClassic else TextMuted,
            modifier = Modifier.size(26.dp).clickable(onClick = onToggle)
        )
    }
}
