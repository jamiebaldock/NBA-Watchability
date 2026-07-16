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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ChecklistRtl
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import com.nbawatchability.app.data.Tier
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextMuted
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary
import com.nbawatchability.app.ui.theme.TierWorthYourTime

/** Top-level settings screen - now a bottom-nav tab in its own right (the rightmost item, after News), not a drill-down from a gear icon - links to the rating-weights and About sub-pages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    showAllLeaguesInStarred: Boolean,
    onToggleShowAllLeaguesInStarred: () -> Unit,
    onSelectedSportsClick: () -> Unit,
    onRubricWeightsClick: () -> Unit,
    onAboutClick: () -> Unit,
    onFavoriteTeamsClick: () -> Unit,
    onFavoritePlayersClick: () -> Unit,
    bumpFavoriteTeamGames: Boolean,
    onToggleBumpFavoriteTeamGames: () -> Unit,
    defaultLandingTab: BottomNavTab,
    onDefaultLandingTabChange: (BottomNavTab) -> Unit,
    historyShowScoresByDefault: Boolean,
    onToggleHistoryShowScoresByDefault: () -> Unit,
    minTierFilterEnabled: Boolean,
    onToggleMinTierFilterEnabled: () -> Unit,
    minTierFilter: Tier,
    onMinTierFilterChange: (Tier) -> Unit,
    wifiOnlyHighlights: Boolean,
    onToggleWifiOnlyHighlights: () -> Unit,
    lightTheme: Boolean,
    onToggleLightTheme: () -> Unit,
    defaultGameDetailTab: GameDetailTab,
    onDefaultGameDetailTabChange: (GameDetailTab) -> Unit
) {
    Scaffold(
        containerColor = BackgroundBase,
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = TextPrimary) }
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onFavoriteTeamsClick)
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Favorite, contentDescription = null, tint = TextSecondary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = "Favorite Teams", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                }
                Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted)
            }

            HorizontalDivider(color = TextMuted.copy(alpha = 0.3f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onFavoritePlayersClick)
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Favorite, contentDescription = null, tint = TextSecondary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = "Favorite Players", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                }
                Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted)
            }

            HorizontalDivider(color = TextMuted.copy(alpha = 0.3f))

            // Default (off) is "visual marking only" - a favorited team's
            // games still show in whatever order Games/Starred/History
            // already use, just tinted (GameCard's TeamRow). Turning this on
            // additionally bumps them to the top of that same order.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Favorite, contentDescription = null, tint = TextSecondary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = "Bump favorite teams' games to top", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                }
                Switch(
                    checked = bumpFavoriteTeamGames,
                    onCheckedChange = { onToggleBumpFavoriteTeamGames() },
                    colors = SwitchDefaults.colors(checkedTrackColor = TierWorthYourTime)
                )
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

            DefaultLandingTabRow(selectedTab = defaultLandingTab, onTabSelected = onDefaultLandingTabChange)

            HorizontalDivider(color = TextMuted.copy(alpha = 0.3f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.VisibilityOff, contentDescription = null, tint = TextSecondary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = "Show scores by default in History", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                }
                Switch(
                    checked = historyShowScoresByDefault,
                    onCheckedChange = { onToggleHistoryShowScoresByDefault() },
                    colors = SwitchDefaults.colors(checkedTrackColor = TierWorthYourTime)
                )
            }

            HorizontalDivider(color = TextMuted.copy(alpha = 0.3f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = if (minTierFilterEnabled) 8.dp else 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.FilterAlt, contentDescription = null, tint = TextSecondary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = "Minimum tier to show", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                }
                Switch(
                    checked = minTierFilterEnabled,
                    onCheckedChange = { onToggleMinTierFilterEnabled() },
                    colors = SwitchDefaults.colors(checkedTrackColor = TierWorthYourTime)
                )
            }
            if (minTierFilterEnabled) {
                MinTierFilterChips(
                    selected = minTierFilter,
                    onSelected = onMinTierFilterChange,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            HorizontalDivider(color = TextMuted.copy(alpha = 0.3f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Wifi, contentDescription = null, tint = TextSecondary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = "Wi-Fi only for highlights video", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                }
                Switch(
                    checked = wifiOnlyHighlights,
                    onCheckedChange = { onToggleWifiOnlyHighlights() },
                    colors = SwitchDefaults.colors(checkedTrackColor = TierWorthYourTime)
                )
            }

            HorizontalDivider(color = TextMuted.copy(alpha = 0.3f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.LightMode, contentDescription = null, tint = TextSecondary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = "Light theme", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                }
                Switch(
                    checked = lightTheme,
                    onCheckedChange = { onToggleLightTheme() },
                    colors = SwitchDefaults.colors(checkedTrackColor = TierWorthYourTime)
                )
            }

            HorizontalDivider(color = TextMuted.copy(alpha = 0.3f))

            DefaultGameDetailTabRow(selectedTab = defaultGameDetailTab, onTabSelected = onDefaultGameDetailTabChange)

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

/** Which tab the app opens on - a plain tap-to-open DropdownMenu rather than a full picker screen, since there are only 7 options. */
@Composable
private fun DefaultLandingTabRow(selectedTab: BottomNavTab, onTabSelected: (BottomNavTab) -> Unit) {
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
                Icon(imageVector = Icons.AutoMirrored.Filled.List, contentDescription = null, tint = TextSecondary)
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = "Default tab", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = selectedTab.label, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            BottomNavTab.entries.forEach { tab ->
                DropdownMenuItem(
                    text = { Text(tab.label) },
                    onClick = {
                        onTabSelected(tab)
                        expanded = false
                    }
                )
            }
        }
    }
}

private val GameDetailTab.label: String
    get() = when (this) {
        GameDetailTab.BREAKDOWN -> "Breakdown"
        GameDetailTab.TOP_PERFORMERS -> "Top Performers"
    }

/** Which of the game-detail popup's two tabs shows first when it opens - same tap-to-open dropdown pattern as DefaultLandingTabRow above, just 2 options instead of 7. */
@Composable
private fun DefaultGameDetailTabRow(selectedTab: GameDetailTab, onTabSelected: (GameDetailTab) -> Unit) {
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
                Icon(imageVector = Icons.AutoMirrored.Filled.List, contentDescription = null, tint = TextSecondary)
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = "Default game-detail tab", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = selectedTab.label, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            GameDetailTab.entries.forEach { tab ->
                DropdownMenuItem(
                    text = { Text(tab.label) },
                    onClick = {
                        onTabSelected(tab)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** One chip per tier, "at least this good" - selecting a chip sets the filter's floor; the enabling Switch above this (in SettingsScreen) is what actually turns the filter on/off. */
@Composable
private fun MinTierFilterChips(selected: Tier, onSelected: (Tier) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Reverse so the row reads worst-to-best, left-to-right (Skippable
        // first) - matches "how permissive is this filter" reading better
        // than the tier enum's own best-first declaration order.
        Tier.entries.reversed().forEach { tier ->
            FilterChip(
                selected = tier == selected,
                onClick = { onSelected(tier) },
                label = { Text(tier.label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = TierWorthYourTime,
                    selectedLabelColor = BackgroundBase
                )
            )
        }
    }
}
