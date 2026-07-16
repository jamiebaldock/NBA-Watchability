package com.nbawatchability.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nbawatchability.app.data.FavoritePlayer
import com.nbawatchability.app.data.LeagueGroup
import com.nbawatchability.app.data.Team
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextMuted
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary
import com.nbawatchability.app.ui.theme.themeAwareLogoUrl

/**
 * Shows the user's current favorite teams and players (view/manage - remove
 * one, or jump to the relevant search/browse screen to add more) - adding
 * happens on dedicated Settings sub-screens (FavoriteTeamsScreen /
 * FavoritePlayersScreen), not here, so there's only one place that owns
 * "the full list of every team/player in a league." Both sections stack in
 * one scrollable column rather than nested LazyColumns, since each list is
 * capped at 3 per league - never enough entries to need its own
 * virtualized scroll even with every league maxed out.
 *
 * Favorite teams are grouped under a small league header (James' per-league
 * cap makes this worth surfacing - "3 favorites" no longer means "3 total,"
 * so which league each one belongs to is now genuinely useful context, not
 * just decoration). A team favorited before this field existed (leagueGroup
 * null) falls into its own "Other" bucket rather than being dropped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyTeamsScreen(
    favoriteTeams: List<Team>,
    onRemoveFavoriteTeam: (Team) -> Unit,
    onAddTeamClick: () -> Unit,
    favoritePlayers: List<FavoritePlayer>,
    onRemoveFavoritePlayer: (FavoritePlayer) -> Unit,
    onAddPlayerClick: () -> Unit
) {
    Scaffold(
        containerColor = BackgroundBase,
        topBar = {
            TopAppBar(title = { Text("My Teams", color = TextPrimary) })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(text = "Favorite Teams", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

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

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = TextMuted.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(24.dp))

            Text(text = "Favorite Players", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            if (favoritePlayers.isEmpty()) {
                Text(
                    text = "No favorite players yet - add up to $MAX_FAVORITE_PLAYERS to get called out when they have a big game.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
            } else {
                favoritePlayers.forEach { player ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(text = player.name, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                            Text(text = player.team, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove ${player.name}",
                            tint = TextMuted,
                            modifier = Modifier.size(22.dp).clickable { onRemoveFavoritePlayer(player) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            if (favoritePlayers.size < MAX_FAVORITE_PLAYERS) {
                Button(onClick = onAddPlayerClick, modifier = Modifier.fillMaxWidth()) {
                    Text("Add a favorite player")
                }
            }
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
