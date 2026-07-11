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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nbawatchability.app.data.StatCategory
import com.nbawatchability.app.data.StatLeader
import com.nbawatchability.app.data.StatsResponse
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.SurfaceCardElevated
import com.nbawatchability.app.ui.theme.TextMuted
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary
import com.nbawatchability.app.ui.theme.TierWorthYourTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(uiState: StatsUiState, onRetry: () -> Unit) {
    Scaffold(
        containerColor = BackgroundBase,
        topBar = { TopAppBar(title = { Text("League Leaders", color = TextPrimary) }) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (uiState) {
                is StatsUiState.Loading -> CenteredSpinner()
                is StatsUiState.Error -> CenteredError(uiState.message, onRetry)
                is StatsUiState.Loaded ->
                    if (uiState.data.categories.isEmpty()) {
                        CenteredMessage("No stats available yet - check back once the season's underway.")
                    } else {
                        StatsList(uiState.data)
                    }
            }
        }
    }
}

@Composable
private fun StatsList(data: StatsResponse) {
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
        items(data.categories) { category -> StatCategoryCard(category) }
    }
}

@Composable
private fun StatCategoryCard(category: StatCategory) {
    Column {
        Text(
            text = category.label,
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
                category.leaders.forEachIndexed { index, leader ->
                    StatLeaderRow(rank = index + 1, leader = leader, abbr = category.abbr)
                }
            }
        }
    }
}

@Composable
private fun StatLeaderRow(rank: Int, leader: StatLeader, abbr: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = rank.toString(),
            color = if (rank == 1) TierWorthYourTime else TextMuted,
            fontWeight = if (rank == 1) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(24.dp)
        )
        AsyncImage(model = leader.teamLogo, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = leader.name, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
            Text(text = leader.team, color = TextMuted, style = MaterialTheme.typography.labelSmall)
        }
        Text(
            text = "${leader.value} $abbr",
            color = TextSecondary,
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
