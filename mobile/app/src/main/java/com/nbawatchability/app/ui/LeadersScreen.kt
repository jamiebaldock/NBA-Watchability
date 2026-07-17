package com.nbawatchability.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.nbawatchability.app.data.LeagueGroup
import com.nbawatchability.app.ui.theme.BackgroundBase
import kotlinx.coroutines.launch

private val LEADERS_PAGE_TITLES = listOf("Standings", "Stats")

/**
 * Merges the former standalone Standings and Stats tabs into one swipeable
 * pair of pages - both were single always-loaded league views with nothing
 * tab-specific in their own app bars, so a shared "Leaders" app bar plus a
 * chip-row-synced HorizontalPager (the same shared [AppTopBar]/[NavChipRow]
 * every other tab's secondary row now uses) replaces two nearly-identical
 * Scaffolds with one, cutting the bottom nav down by a tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeadersScreen(
    standingsUiState: StandingsUiState,
    onStandingsRetry: () -> Unit,
    statsUiState: StatsUiState,
    onStatsRetry: () -> Unit,
    selectedLeague: LeagueGroup,
    onLeagueSelected: (LeagueGroup) -> Unit,
    enabledLeagues: Set<LeagueGroup>
) {
    val pagerState = rememberPagerState(initialPage = 0) { LEADERS_PAGE_TITLES.size }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = BackgroundBase,
        topBar = {
            AppTopBar(
                leading = { TitleLeagueSelector(selectedLeague, onLeagueSelected, enabledLeagues) },
                secondary = {
                    NavChipRow(
                        items = LEADERS_PAGE_TITLES,
                        selected = LEADERS_PAGE_TITLES[pagerState.currentPage],
                        onSelected = { title ->
                            scope.launch { pagerState.animateScrollToPage(LEADERS_PAGE_TITLES.indexOf(title)) }
                        },
                        label = { it }
                    )
                }
            )
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) { page ->
            when (page) {
                0 -> StandingsScreen(uiState = standingsUiState, onRetry = onStandingsRetry)
                else -> StatsScreen(uiState = statsUiState, onRetry = onStatsRetry)
            }
        }
    }
}
