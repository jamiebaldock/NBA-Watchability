package com.nbawatchability.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Schedule
import com.nbawatchability.app.data.AlertDelivery
import com.nbawatchability.app.data.AlertTierThreshold
import com.nbawatchability.app.data.LEAD_TIME_OPTIONS_MINUTES
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextMuted
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary
import com.nbawatchability.app.ui.theme.TierWorthYourTime

/**
 * Preferences for both alert types: starting-soon (client-local alarms,
 * StartingSoonScheduler) and close-swing (phase 4 backend detection) - the
 * per-game bell has no settings of its own (it's an explicit per-game
 * opt-in, toggled from GameCard directly). Close-swing/delivery/scope
 * changes re-register the device so the backend's next poll picks them up;
 * starting-soon changes never leave the device, they just reschedule the
 * local alarms.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsSettingsScreen(
    startingSoonEnabled: Boolean,
    onToggleStartingSoonEnabled: (Boolean) -> Unit,
    leadTimeMinutes: Int,
    onLeadTimeChange: (Int) -> Unit,
    closeSwingEnabled: Boolean,
    onToggleCloseSwingEnabled: (Boolean) -> Unit,
    delivery: AlertDelivery,
    onDeliveryChange: (AlertDelivery) -> Unit,
    favoritesOnly: Boolean,
    onToggleFavoritesOnly: (Boolean) -> Unit,
    tierThreshold: AlertTierThreshold?,
    onTierThresholdChange: (AlertTierThreshold?) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = BackgroundBase,
        topBar = {
            TopAppBar(
                title = { Text("Alerts", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.NotificationsActive, contentDescription = null, tint = TextSecondary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(text = "Starting-soon alerts", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "Get notified before your favorites' (and belled) games start",
                            color = TextMuted,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                Switch(
                    checked = startingSoonEnabled,
                    onCheckedChange = onToggleStartingSoonEnabled,
                    colors = SwitchDefaults.colors(checkedTrackColor = TierWorthYourTime)
                )
            }

            if (startingSoonEnabled) {
                HorizontalDivider(color = TextMuted.copy(alpha = 0.3f))
                LeadTimeRow(selectedMinutes = leadTimeMinutes, onSelected = onLeadTimeChange)
            }

            HorizontalDivider(color = TextMuted.copy(alpha = 0.3f))

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.NotificationsActive, contentDescription = null, tint = TextSecondary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(text = "Close-swing alerts", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "Get notified when a live game gets close in crunch time",
                            color = TextMuted,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                Switch(
                    checked = closeSwingEnabled,
                    onCheckedChange = onToggleCloseSwingEnabled,
                    colors = SwitchDefaults.colors(checkedTrackColor = TierWorthYourTime)
                )
            }

            HorizontalDivider(color = TextMuted.copy(alpha = 0.3f))

            DeliveryRow(selected = delivery, onSelected = onDeliveryChange)

            HorizontalDivider(color = TextMuted.copy(alpha = 0.3f))

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Shield, contentDescription = null, tint = TextSecondary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(text = "Favorite teams only", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "Off also alerts on other close games, scoped by the rating below",
                            color = TextMuted,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                Switch(
                    checked = favoritesOnly,
                    onCheckedChange = onToggleFavoritesOnly,
                    colors = SwitchDefaults.colors(checkedTrackColor = TierWorthYourTime)
                )
            }

            if (!favoritesOnly) {
                HorizontalDivider(color = TextMuted.copy(alpha = 0.3f))
                TierThresholdRow(selected = tierThreshold, onSelected = onTierThresholdChange)
            }
        }
    }
}

@Composable
private fun LeadTimeRow(selectedMinutes: Int, onSelected: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.Schedule, contentDescription = null, tint = TextSecondary)
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = "Lead time", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "$selectedMinutes min before", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LEAD_TIME_OPTIONS_MINUTES.forEach { minutes ->
                DropdownMenuItem(
                    text = { Text("$minutes min before") },
                    onClick = {
                        onSelected(minutes)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun DeliveryRow(selected: AlertDelivery, onSelected: (AlertDelivery) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.NotificationsActive, contentDescription = null, tint = TextSecondary)
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = "Delivery", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = selected.label, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AlertDelivery.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun TierThresholdRow(selected: AlertTierThreshold?, onSelected: (AlertTierThreshold?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.Tune, contentDescription = null, tint = TextSecondary)
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = "Minimum rating", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = selected?.label ?: "Off", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Off") },
                onClick = {
                    onSelected(null)
                    expanded = false
                }
            )
            AlertTierThreshold.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}
