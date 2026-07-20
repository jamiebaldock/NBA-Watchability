package com.nbawatchability.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nbawatchability.app.data.Game
import com.nbawatchability.app.data.MlbRubricWeights
import com.nbawatchability.app.data.NflRubricWeights
import com.nbawatchability.app.data.RubricWeights
import com.nbawatchability.app.data.effectiveScore
import com.nbawatchability.app.data.rubricBreakdown
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextMuted
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary
import com.nbawatchability.app.ui.theme.TierWorthYourTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

enum class GameDetailTab { BREAKDOWN, TOP_PERFORMERS }

private val headToHeadDateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")

/**
 * Tap-a-tile popup (Phase G) - only ever opened for a FINAL, already-rated
 * game (AppRoot.kt gates on game.hasBreakdown before showing this at all),
 * since an upcoming/live game has neither a rubric breakdown nor real
 * top-performer stats to show yet. [defaultTab] is a persisted Settings
 * choice; the in-screen tab selection itself doesn't persist across a
 * re-open, same as every other "expanded state" in this app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailScreen(
    game: Game,
    nbaWeights: RubricWeights,
    wnbaWeights: RubricWeights,
    mlbWeights: MlbRubricWeights,
    nflWeights: NflRubricWeights,
    defaultTab: GameDetailTab,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by rememberSaveable(game.id) { mutableStateOf(defaultTab) }
    val viewModel: GameDetailViewModel = viewModel()

    LaunchedEffect(game.eventId) {
        game.eventId?.let { viewModel.load(it) }
    }

    Scaffold(
        containerColor = BackgroundBase,
        topBar = {
            TopAppBar(
                title = { Text("${game.away} at ${game.home}", color = TextPrimary, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val shareText = buildShareText(game, nbaWeights, wnbaWeights, mlbWeights, nflWeights)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareText)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share result"))
                    }) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = "Share result")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab.ordinal, containerColor = BackgroundBase) {
                Tab(
                    selected = selectedTab == GameDetailTab.BREAKDOWN,
                    onClick = { selectedTab = GameDetailTab.BREAKDOWN },
                    text = { Text("Breakdown") }
                )
                Tab(
                    selected = selectedTab == GameDetailTab.TOP_PERFORMERS,
                    onClick = { selectedTab = GameDetailTab.TOP_PERFORMERS },
                    text = { Text("Top Performers") }
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                if (game.youtubeVideoId != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    ) {
                        Icon(imageVector = Icons.Default.PlayCircleFilled, contentDescription = null, tint = TierWorthYourTime)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Watch highlights (opens YouTube)",
                            color = TierWorthYourTime,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = {
                            val videoId = game.youtubeVideoId
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId"))
                            context.startActivity(intent)
                        }) {
                            Text("Watch")
                        }
                    }
                    HorizontalDivider(color = TextMuted.copy(alpha = 0.3f), modifier = Modifier.padding(bottom = 16.dp))
                }

                when (selectedTab) {
                    GameDetailTab.BREAKDOWN -> BreakdownTab(game, nbaWeights, wnbaWeights, mlbWeights, nflWeights)
                    GameDetailTab.TOP_PERFORMERS -> TopPerformersTab(viewModel)
                }

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider(color = TextMuted.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(16.dp))
                ContextSection(viewModel)
            }
        }
    }
}

private fun buildShareText(
    game: Game,
    nbaWeights: RubricWeights,
    wnbaWeights: RubricWeights,
    mlbWeights: MlbRubricWeights,
    nflWeights: NflRubricWeights
): String {
    val score = game.effectiveScore(nbaWeights, wnbaWeights, mlbWeights, nflWeights)
    val tier = game.tier
    return buildString {
        append("${game.away} @ ${game.home}")
        if (game.awayScore != null && game.homeScore != null) {
            append(" (${game.awayScore}-${game.homeScore})")
        }
        if (tier != null && score != null) {
            append(" - ${tier.label} (${score}/100)")
        }
    }
}

@Composable
private fun BreakdownTab(
    game: Game,
    nbaWeights: RubricWeights,
    wnbaWeights: RubricWeights,
    mlbWeights: MlbRubricWeights,
    nflWeights: NflRubricWeights
) {
    val entries = remember(game.id, nbaWeights, wnbaWeights, mlbWeights, nflWeights) {
        game.rubricBreakdown(nbaWeights, wnbaWeights, mlbWeights, nflWeights)
    }
    val total = game.effectiveScore(nbaWeights, wnbaWeights, mlbWeights, nflWeights) ?: 0

    Column {
        entries.forEach { entry ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = entry.label, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                Text(
                    // "X/Y pts" (points earned out of that dimension's own
                    // weighted max), not a bare number - a bare "13 pts" read
                    // as ambiguous with a real-world stat (e.g. the actual
                    // final score margin) rather than a rating contribution.
                    text = String.format("%.0f/%.0f pts", entry.points, entry.maxPoints),
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
                )
            }
        }
        HorizontalDivider(color = TextMuted.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = "Total watchability", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "$total/100",
                color = TierWorthYourTime,
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum")
            )
        }
    }
}

@Composable
private fun TopPerformersTab(viewModel: GameDetailViewModel) {
    when (val state = viewModel.uiState) {
        is GameDetailUiState.Loading -> LoadingRow()
        is GameDetailUiState.Error -> ErrorRow(state.message, viewModel::retry)
        is GameDetailUiState.Loaded -> {
            val performers = state.data.topPerformers
            if (performers.isEmpty()) {
                Text(
                    text = "No standout individual stats for this game.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                performers.forEach { performer ->
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(text = performer.name, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                        Text(text = "${performer.team} - ${performer.line}", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextSection(viewModel: GameDetailViewModel) {
    when (val state = viewModel.uiState) {
        is GameDetailUiState.Loading -> LoadingRow()
        is GameDetailUiState.Error -> {} // Top Performers tab already surfaces this same error/retry
        is GameDetailUiState.Loaded -> {
            val data = state.data
            if (data.awayStandings.record != null || data.homeStandings.record != null) {
                Text(text = "Standings", color = TextPrimary, style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                data.awayStandings.record?.let {
                    Text(
                        text = "${data.awayStandings.groupName ?: ""}: #${data.awayStandings.rank} ($it)",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                data.homeStandings.record?.let {
                    Text(
                        text = "${data.homeStandings.groupName ?: ""}: #${data.homeStandings.rank} ($it)",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (data.headToHead.isNotEmpty()) {
                Text(text = "Head-to-head this season", color = TextPrimary, style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                data.headToHead.forEach { meeting ->
                    val date = runCatching { OffsetDateTime.parse(meeting.utc).format(headToHeadDateFormatter) }.getOrDefault(meeting.utc)
                    Text(
                        text = "$date: ${meeting.away} ${meeting.awayScore} - ${meeting.homeScore} ${meeting.home}",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingRow() {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorRow(message: String, onRetry: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Text(text = message, color = TextSecondary, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Retry") }
    }
}
