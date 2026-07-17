package com.nbawatchability.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextSecondary
import com.nbawatchability.app.ui.theme.TierWorthYourTime

/**
 * The single top-bar shape shared by every one of the app's 6 main tabs
 * (Games, Starred, Favorites, History, Leaders, News) - a leading area (a
 * league dropdown via [TitleLeagueSelector], a plain title, or anything
 * else a tab's identity needs), a right-aligned row of action icons drawn
 * from one consistent icon-button set regardless of which icons a given
 * tab needs, and an optional second row underneath for whatever that tab
 * pages through (day tabs, season chips, page tabs - see [NavChipRow]).
 * Consolidates what used to be 6 independently-evolved
 * Scaffold+TopAppBar(+ad-hoc secondary row) blocks into one component, so
 * every tab's header renders/recomposes the same well-tested way and a
 * future addition (e.g. extending History's per-preset cache/prefetch
 * pattern to another tab) only ever has to plug into one place.
 *
 * [secondary] defaults to nothing - tabs without a second row (Starred,
 * News) simply don't pass one, rather than needing an explicit "no
 * secondary row" flag.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    leading: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    secondary: @Composable () -> Unit = {}
) {
    Column {
        TopAppBar(title = leading, actions = actions)
        secondary()
    }
}

/**
 * A single selectable pill, styled to match History's original season-chip
 * look (the most polished existing variant, per James) - the shared visual
 * atom behind every tab's secondary row. [NavChipRow] below wraps this for
 * the common case of a small, fixed set of items in a plain scrollable Row
 * (History's seasons, Leaders' Standings/Stats, Favorites' 4 pages); Games'
 * day-tab strip needs a different layout algorithm entirely (a centering,
 * lazily-rendered row built for up to ~140 WNBA-season tabs) and renders
 * this same [NavChip] per-item inside that specialized layout instead of
 * using [NavChipRow].
 */
@Composable
fun NavChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = TierWorthYourTime,
            selectedLabelColor = BackgroundBase
        )
    )
}

/**
 * A horizontally-scrollable row of [NavChip]s for a small, fixed item set -
 * the shared secondary row for History's season presets, Leaders'
 * Standings/Stats pages, and Favorites' 4 pages. Each tab keeps its own
 * pager-sync logic (settled-page/selection two-way binding) - this is
 * purely the visual shell and tap-to-select interaction, not a pager
 * itself, so it fits equally well behind a HorizontalPager (History,
 * Favorites, Leaders) or nothing at all.
 */
@Composable
fun <T> NavChipRow(items: List<T>, selected: T, onSelected: (T) -> Unit, label: (T) -> String) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { item ->
            NavChip(label = label(item), selected = item == selected, onClick = { onSelected(item) })
        }
    }
}

/** The hashtag icon - toggles whether GameCard's tier badge also shows the raw numeric score. Shared by every tab that renders GameCard tiles (Games, Starred, History, Favorites' Games pages). */
@Composable
fun NumericScoreToggleButton(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    IconToggleButton(checked = checked, onCheckedChange = onCheckedChange) {
        Icon(
            imageVector = Icons.Default.Tag,
            contentDescription = "Show numeric score",
            tint = if (checked) TierWorthYourTime else TextSecondary
        )
    }
}

/** The eye icon - toggles whether already-finished games' scores are shown or hidden. Only meaningful where scores are actually spoiler-safe to reveal at all (History today). */
@Composable
fun HideScoresToggleButton(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    IconToggleButton(checked = checked, onCheckedChange = onCheckedChange) {
        Icon(
            imageVector = if (checked) Icons.Default.Visibility else Icons.Default.VisibilityOff,
            contentDescription = if (checked) "Hide scores" else "Show scores",
            tint = if (!checked) TierWorthYourTime else TextSecondary
        )
    }
}
