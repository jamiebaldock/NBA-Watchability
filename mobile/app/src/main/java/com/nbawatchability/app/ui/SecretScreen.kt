package com.nbawatchability.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextMuted
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary
import com.nbawatchability.app.ui.theme.TierWorthYourTime

/**
 * Unlocked by tapping the version number on AboutScreen 7 times - not
 * reachable from the normal Settings list or any nav element, by design.
 * One toggle for now (Player Hater Mode); more can join this same Row+Switch
 * pattern later without needing a new unlock mechanism.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretScreen(
    playerHaterMode: Boolean,
    onTogglePlayerHaterMode: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = BackgroundBase,
        topBar = {
            TopAppBar(
                title = { Text("🤫 Secret Settings", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Text(
                text = "You found it. Nobody else knows this screen exists - flip the switch below, " +
                    "then go tick the players you want roasted from Favorites > Players or the Add Player " +
                    "search screen. Up to 10 at once. Only the players you've ticked get the treatment - " +
                    "everyone else's standout callout stays normal.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 16.dp)
            )
            HorizontalDivider(color = TextMuted.copy(alpha = 0.3f))

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.ThumbDown, contentDescription = null, tint = TextSecondary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(text = "Player Hater Mode", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "Tick players on Favorites or Add Player to get roasted instead of celebrated.",
                            color = TextMuted,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                Switch(
                    checked = playerHaterMode,
                    onCheckedChange = { onTogglePlayerHaterMode() },
                    colors = SwitchDefaults.colors(checkedTrackColor = TierWorthYourTime),
                    modifier = Modifier.padding(end = 24.dp)
                )
            }
            HorizontalDivider(color = TextMuted.copy(alpha = 0.3f))
        }
    }
}
