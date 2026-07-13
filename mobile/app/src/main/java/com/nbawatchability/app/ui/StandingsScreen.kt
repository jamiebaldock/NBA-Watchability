package com.nbawatchability.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nbawatchability.app.data.StandingsGroup
import com.nbawatchability.app.data.StandingsResponse
import com.nbawatchability.app.data.StandingsTeam
import com.nbawatchability.app.ui.theme.SurfaceCardElevated
import com.nbawatchability.app.ui.theme.TextMuted
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary

// No Scaffold/TopAppBar of its own - this is one page of the merged
// Leaders tab (LeadersScreen.kt), which provides a single shared app bar
// and TabRow above the Standings/Stats HorizontalPager.
@Composable
fun StandingsScreen(uiState: StandingsUiState, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (uiState) {
            is StandingsUiState.Loading -> CenteredSpinner()
            is StandingsUiState.Error -> CenteredError(uiState.message, onRetry)
            is StandingsUiState.Loaded ->
                if (uiState.data.groups.isEmpty()) {
                    CenteredMessage("No standings available yet - check back once the season's underway.")
                } else {
                    StandingsList(uiState.data)
                }
        }
    }
}

@Composable
private fun StandingsList(data: StandingsResponse) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Text(
                text = "${data.season} season",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
        items(data.groups) { group -> StandingsGroupSection(group) }
    }
}

@Composable
private fun StandingsGroupSection(group: StandingsGroup) {
    Column {
        Text(
            text = group.name,
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceCardElevated),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                StandingsHeaderRow()
                group.teams.forEachIndexed { index, team ->
                    StandingsRow(rank = index + 1, team = team)
                }
            }
        }
    }
}

@Composable
private fun StandingsHeaderRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(modifier = Modifier.width(28.dp))
        Spacer(modifier = Modifier.width(32.dp))
        Text("TEAM", color = TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        StandingsStatHeader("W")
        StandingsStatHeader("L")
        StandingsStatHeader("PCT")
        StandingsStatHeader("GB")
    }
}

@Composable
private fun StandingsStatHeader(label: String) {
    Text(
        text = label,
        color = TextMuted,
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.End,
        modifier = Modifier.width(40.dp)
    )
}

@Composable
private fun StandingsRow(rank: Int, team: StandingsTeam) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = rank.toString(),
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(28.dp)
        )
        AsyncImage(model = team.logo, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = team.name, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
            if (!team.strk.isNullOrBlank()) {
                Text(text = team.strk, color = TextMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(
            text = team.wins.toString(),
            color = TextSecondary,
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(40.dp)
        )
        Text(
            text = team.losses.toString(),
            color = TextSecondary,
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(40.dp)
        )
        Text(
            text = team.pct,
            color = TextSecondary,
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(40.dp)
        )
        Text(
            text = team.gb,
            color = TextSecondary,
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(40.dp)
        )
    }
}
