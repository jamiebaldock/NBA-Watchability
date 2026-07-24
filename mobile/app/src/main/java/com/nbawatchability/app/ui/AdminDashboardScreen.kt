package com.nbawatchability.app.ui

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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nbawatchability.app.data.AdminMissingGame
import com.nbawatchability.app.data.AdminStats
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextMuted
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary
import com.nbawatchability.app.ui.theme.TierInstantClassic
import com.nbawatchability.app.ui.theme.TierWorthYourTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timestampFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a")

private fun formatUtc(iso: String?): String {
    if (iso == null) return "never"
    return runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(timestampFormatter) }
        .getOrDefault(iso)
}

/**
 * Hidden operational page (About screen's 12x title tap -> AdminPinScreen ->
 * here). Built after investigating why recent games weren't reliably
 * getting highlights - see that investigation for the two real bugs it
 * found (a too-tight retry window, and MLB's "D-BACKS" title abbreviation
 * not matching "Diamondbacks"). Exists so that kind of digging doesn't need
 * a fresh one-off diagnostic route added and torn down every time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboardScreen(
    state: AdminDashboardState,
    resendStateFor: (String) -> ResendState?,
    onResend: (String) -> Unit,
    onRefresh: () -> Unit,
    onLogOut: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = BackgroundBase,
        topBar = {
            TopAppBar(
                title = { Text("Admin", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onLogOut) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.Logout, contentDescription = "Log out")
                    }
                }
            )
        }
    ) { padding ->
        when (state) {
            is AdminDashboardState.Loading -> Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
            }

            is AdminDashboardState.Error -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = state.message, color = TextSecondary, textAlign = TextAlign.Center)
                OutlinedButton(onClick = onRefresh, modifier = Modifier.padding(top = 16.dp)) { Text("Retry") }
            }

            is AdminDashboardState.Loaded -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
            ) {
                StatsSection(state.stats, modifier = Modifier.padding(top = 16.dp))

                HorizontalDivider(color = TextMuted.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 16.dp))

                Text(
                    text = "Missing highlights (${state.missingGames.size})",
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                if (state.missingGames.isEmpty()) {
                    Text(
                        text = "Nothing missing right now.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                } else {
                    state.missingGames.forEach { game ->
                        MissingGameRow(
                            game = game,
                            resendState = resendStateFor(game.eventId),
                            onResend = { onResend(game.eventId) }
                        )
                        HorizontalDivider(color = TextMuted.copy(alpha = 0.2f))
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun StatsSection(stats: AdminStats, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = "YouTube search quota", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
        Text(
            text = "${stats.todayCount} / ${stats.dailyCap} used today",
            color = if (stats.todayCount >= stats.dailyCap) TierInstantClassic else TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (stats.budgetHistory.isNotEmpty()) {
            Text(
                text = stats.budgetHistory.joinToString("   ") { "${it.date.takeLast(5)}: ${it.count}" },
                color = TextMuted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        val outcomes = stats.outcomeCounts
        Text(
            text = "Last 7 days — matched ${outcomes["matched"] ?: 0}, no match ${outcomes["no_match"] ?: 0}, " +
                "errors ${outcomes["api_error"] ?: 0}, quota-skipped ${outcomes["quota_exhausted"] ?: 0}",
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 12.dp)
        )

        Text(
            text = "Median upload lag (learned)",
            color = TextPrimary,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp)
        )
        stats.lagPercentiles.forEach { (league, lag) ->
            // A negative value means final_at (our own "this game just went
            // final" detection) was itself stamped later than the real
            // buzzer - the video really did come out after the game, our
            // clock on "when the game ended" was just late. Not a
            // trustworthy lag reading either way, so it's called out rather
            // than shown as if it were one.
            val display = when {
                lag.sampleCount == 0 -> "no data yet"
                lag.p50Ms < 0 -> "unreliable reading (final-detection lag)"
                else -> "${lag.p50Ms / 60000} min (${lag.sampleCount} samples${if (!lag.fromRealData) ", default" else ""})"
            }
            Text(
                text = "${league.uppercase()}: $display",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun MissingGameRow(game: AdminMissingGame, resendState: ResendState?, onResend: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "${game.away} @ ${game.home}", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "${game.leagueGroup.uppercase()} · ${formatUtc(game.tipoffUtc)} · ${game.ytCheckCount} check(s), last ${formatUtc(game.ytLastCheckedAt)}",
                    color = TextMuted,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            when (resendState) {
                is ResendState.InFlight -> CircularProgressIndicator(modifier = Modifier.height(28.dp).width(28.dp), strokeWidth = 2.dp)
                else -> Button(onClick = onResend) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.height(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Re-search")
                }
            }
        }
        when (resendState) {
            is ResendState.Found -> Text(
                text = "Found: ${resendState.title}",
                color = TierWorthYourTime,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp)
            )
            is ResendState.NotFound -> Text(
                text = "No match found",
                color = TextMuted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp)
            )
            is ResendState.Failed -> Text(
                text = resendState.message,
                color = TierInstantClassic,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp)
            )
            else -> Unit
        }
    }
}
