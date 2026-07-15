package com.nbawatchability.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChecklistRtl
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Tune
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

/** Top-level settings screen (spec: settings gear, top app bar) - links to the rating-weights and About sub-pages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    showAllLeaguesInStarred: Boolean,
    onToggleShowAllLeaguesInStarred: () -> Unit,
    onSelectedSportsClick: () -> Unit,
    onRubricWeightsClick: () -> Unit,
    onAboutClick: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = BackgroundBase,
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onRubricWeightsClick)
                    .padding(top = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Tune, contentDescription = null, tint = TextSecondary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = "Watchability rating weights", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                }
                Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted)
            }

            HorizontalDivider(color = TextMuted.copy(alpha = 0.3f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSelectedSportsClick)
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.ChecklistRtl, contentDescription = null, tint = TextSecondary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = "Selected Sports", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                }
                Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted)
            }

            HorizontalDivider(color = TextMuted.copy(alpha = 0.3f))

            // Starred normally follows the shared league dropdown like every
            // other tab; this ON state makes it show every league's starred
            // games combined instead, ignoring that dropdown entirely (see
            // StarredScreen.kt).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Public, contentDescription = null, tint = TextSecondary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = "Show all leagues in Starred", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                }
                Switch(
                    checked = showAllLeaguesInStarred,
                    onCheckedChange = { onToggleShowAllLeaguesInStarred() },
                    colors = SwitchDefaults.colors(checkedTrackColor = TierWorthYourTime)
                )
            }

            HorizontalDivider(color = TextMuted.copy(alpha = 0.3f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onAboutClick)
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = TextSecondary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = "About", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                }
                Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted)
            }
        }
    }
}
