package com.nbawatchability.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nbawatchability.app.data.LeagueGroup
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextMuted
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TierWorthYourTime
import com.nbawatchability.app.ui.theme.themeAwareLogoUrl

// The core US sports set only, for now - every other LeagueGroup entry
// (F1, cricket, other basketball/soccer leagues, etc.) stays defined in the
// enum for later but is hidden from this list and the dropdowns until it's
// actually built out, per James's call. NHL/NFL are still isSupported =
// false placeholders (AppRoot.kt's ComingSoonTab) but are queued next,
// unlike the rest.
private val VISIBLE_LEAGUES = listOf(LeagueGroup.NBA, LeagueGroup.WNBA, LeagueGroup.NHL, LeagueGroup.MLB, LeagueGroup.NFL)

/**
 * Controls which leagues actually list in every tab's league dropdown
 * (TitleLeagueSelector) - restricted to [VISIBLE_LEAGUES] (see its own doc),
 * not every LeagueGroup entry, since this is purely about dropdown
 * visibility, not data availability; selecting a placeholder league
 * elsewhere in the app just shows a "coming soon" state (AppRoot.kt's
 * ComingSoonTab), it doesn't crash. AppSettingsViewModel.toggleLeagueEnabled
 * enforces two safety rules this screen doesn't need to know about: the
 * enabled set can never go fully empty, and turning off the
 * currently-selected league falls back to NBA automatically rather than
 * leaving the dropdown pointed at something no longer in its own list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectedSportsScreen(
    enabledLeagues: Set<LeagueGroup>,
    onToggleLeague: (LeagueGroup) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = BackgroundBase,
        topBar = {
            TopAppBar(
                title = { Text("Selected Leagues", color = TextPrimary) },
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
            // Compact row sizing (smaller logo, tighter padding, a scaled-down
            // Switch) - Switch.scale keeps the full tap target's visual
            // footprint down without touching its actual touch-target logic
            // (Material3 still enforces a minimum interactive size under the
            // hood).
            VISIBLE_LEAGUES.forEachIndexed { index, league ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(model = themeAwareLogoUrl(league.logoUrl), contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(text = league.displayName, color = TextPrimary, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = league in enabledLeagues,
                        onCheckedChange = { onToggleLeague(league) },
                        colors = SwitchDefaults.colors(checkedTrackColor = TierWorthYourTime),
                        modifier = Modifier.scale(0.75f)
                    )
                }
                if (index != VISIBLE_LEAGUES.lastIndex) {
                    HorizontalDivider(color = TextMuted.copy(alpha = 0.3f))
                }
            }
        }
    }
}
