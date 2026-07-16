package com.nbawatchability.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.nbawatchability.app.data.Team
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextMuted
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary

/**
 * Shows the user's current favorite teams (view/manage - remove one, or jump
 * to the search/browse screen to add more) - adding via search happens on a
 * dedicated Settings sub-screen (FavoriteTeamsScreen), not here, so there's
 * only one place that owns "the full list of every team in a league."
 * Favorite players land in a later phase - this tab shows only teams for now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyTeamsScreen(
    favoriteTeams: List<Team>,
    onRemoveFavorite: (Team) -> Unit,
    onAddTeamClick: () -> Unit
) {
    Scaffold(
        containerColor = BackgroundBase,
        topBar = {
            TopAppBar(title = { Text("My Teams", color = TextPrimary) })
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (favoriteTeams.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No favorite teams yet - add up to $MAX_FAVORITE_TEAMS to see them marked across the app.",
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(onClick = onAddTeamClick) { Text("Add a favorite team") }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(favoriteTeams, key = { it.name }) { team ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (team.logo != null) {
                                    AsyncImage(model = team.logo, contentDescription = null, modifier = Modifier.size(32.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                }
                                Text(text = team.name, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                            }
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove ${team.name}",
                                tint = TextMuted,
                                modifier = Modifier.size(22.dp).clickable { onRemoveFavorite(team) }
                            )
                        }
                    }
                }
                if (favoriteTeams.size < MAX_FAVORITE_TEAMS) {
                    Button(
                        onClick = onAddTeamClick,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text("Add a favorite team")
                    }
                }
            }
        }
    }
}
