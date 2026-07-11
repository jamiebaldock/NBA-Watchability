package com.nbawatchability.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nbawatchability.app.data.DayGames
import com.nbawatchability.app.data.LeagueGroup
import com.nbawatchability.app.data.RubricWeights
import com.nbawatchability.app.data.effectiveScore
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary
import com.nbawatchability.app.ui.theme.TierWorthYourTime
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val dayTabFormatter = DateTimeFormatter.ofPattern("MMM d")

/**
 * Replaces the static "NBA Watchability" title when the "Show WNBA" setting
 * is on - tapping it reveals an NBA/WNBA choice. Off (the default), the title
 * stays plain static text with no dropdown affordance at all.
 */
@Composable
private fun TitleLeagueSelector(selectedLeague: LeagueGroup, onLeagueSelected: (LeagueGroup) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { expanded = true }
        ) {
            Text("${selectedLeague.displayName} Watchability", color = TextPrimary)
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = "Select league",
                tint = TextPrimary
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LeagueGroup.entries.forEach { league ->
                DropdownMenuItem(
                    text = {
                        AsyncImage(
                            model = league.logoUrl,
                            contentDescription = league.displayName,
                            modifier = Modifier.size(28.dp)
                        )
                    },
                    onClick = {
                        onLeagueSelected(league)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun dayTabLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> "${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${date.format(dayTabFormatter)}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayTabsScreen(
    days: List<DayGames>,
    today: LocalDate,
    selectedDayIndex: Int,
    onDaySelected: (Int) -> Unit,
    showNumericScore: Boolean,
    onToggleNumericScore: () -> Unit,
    sortBestFirst: Boolean,
    onToggleSort: () -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    weights: RubricWeights,
    onSettingsClick: () -> Unit,
    showWnba: Boolean,
    selectedLeague: LeagueGroup,
    onLeagueSelected: (LeagueGroup) -> Unit
) {
    val pagerState = rememberPagerState(initialPage = selectedDayIndex) { days.size }

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != selectedDayIndex) onDaySelected(pagerState.currentPage)
    }
    LaunchedEffect(selectedDayIndex) {
        if (pagerState.currentPage != selectedDayIndex) pagerState.animateScrollToPage(selectedDayIndex)
    }

    Scaffold(
        containerColor = BackgroundBase,
        topBar = {
            TopAppBar(
                title = {
                    if (showWnba) {
                        TitleLeagueSelector(selectedLeague = selectedLeague, onLeagueSelected = onLeagueSelected)
                    } else {
                        Text("NBA Watchability", color = TextPrimary)
                    }
                },
                actions = {
                    IconToggleButton(checked = sortBestFirst, onCheckedChange = { onToggleSort() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Sort,
                            contentDescription = "Best first sort",
                            tint = if (sortBestFirst) TierWorthYourTime else TextSecondary
                        )
                    }
                    IconToggleButton(checked = showNumericScore, onCheckedChange = { onToggleNumericScore() }) {
                        Icon(
                            imageVector = Icons.Default.Tag,
                            contentDescription = "Show numeric score",
                            tint = if (showNumericScore) TierWorthYourTime else TextSecondary
                        )
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = TextSecondary
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            CenteringDayTabRow(
                days = days,
                today = today,
                selectedDayIndex = selectedDayIndex,
                onDaySelected = onDaySelected
            )

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize()
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    DayGamesList(
                        games = days[page].games,
                        sortBestFirst = sortBestFirst,
                        showNumericScore = showNumericScore,
                        weights = weights
                    )
                }
            }
        }
    }
}

/**
 * Material3's ScrollableTabRow only scrolls the selected tab into view (edge
 * aligned), not centered — replaced with a manual horizontalScroll Row that
 * tracks each tab's on-screen position and animates the scroll offset so the
 * selected tab lands centered in the viewport.
 */
@Composable
private fun CenteringDayTabRow(
    days: List<DayGames>,
    today: LocalDate,
    selectedDayIndex: Int,
    onDaySelected: (Int) -> Unit
) {
    val scrollState = rememberScrollState()
    var viewportWidth by remember { mutableStateOf(0) }
    val tabBounds = remember { mutableStateOf(IntArray(days.size)) }
    val tabWidths = remember { mutableStateOf(IntArray(days.size)) }

    LaunchedEffect(selectedDayIndex, viewportWidth) {
        if (viewportWidth == 0) return@LaunchedEffect
        val offset = tabBounds.value.getOrNull(selectedDayIndex) ?: return@LaunchedEffect
        val width = tabWidths.value.getOrNull(selectedDayIndex) ?: 0
        val target = (offset + width / 2) - viewportWidth / 2
        scrollState.animateScrollTo(target.coerceIn(0, scrollState.maxValue))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { viewportWidth = it.width }
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp)
    ) {
        days.forEachIndexed { index, day ->
            val selected = index == selectedDayIndex
            Column(
                modifier = Modifier
                    .clickable { onDaySelected(index) }
                    .onGloballyPositioned { coords ->
                        val bounds = tabBounds.value.copyOf()
                        val widths = tabWidths.value.copyOf()
                        bounds[index] = coords.positionInParent().x.toInt()
                        widths[index] = coords.size.width
                        tabBounds.value = bounds
                        tabWidths.value = widths
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = dayTabLabel(day.date, today),
                    color = if (selected) TextPrimary else TextSecondary,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                )
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(if (selected) TierWorthYourTime else Color.Transparent)
                )
            }
        }
    }
}

@Composable
private fun DayGamesList(
    games: List<com.nbawatchability.app.data.Game>,
    sortBestFirst: Boolean,
    showNumericScore: Boolean,
    weights: RubricWeights
) {
    val ordered = if (sortBestFirst) {
        val (scored, unscored) = games.partition { it.effectiveScore(weights) != null }
        scored.sortedByDescending { it.effectiveScore(weights) } + unscored
    } else {
        games
    }

    if (ordered.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "No games scheduled",
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                style = MaterialTheme.typography.titleLarge
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(ordered, key = { it.id }) { game ->
            GameCard(game = game, showNumericScore = showNumericScore, weights = weights)
        }
    }
}
