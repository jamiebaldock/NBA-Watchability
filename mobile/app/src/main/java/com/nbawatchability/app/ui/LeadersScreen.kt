package com.nbawatchability.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary
import com.nbawatchability.app.ui.theme.TierWorthYourTime
import kotlinx.coroutines.launch

private val LEADERS_PAGE_TITLES = listOf("Standings", "Stats")

/**
 * Merges the former standalone Standings and Stats tabs into one swipeable
 * pair of pages - both were single always-loaded league views with nothing
 * tab-specific in their own app bars, so a shared "Leaders" app bar plus a
 * TabRow-synced HorizontalPager replaces two nearly-identical Scaffolds
 * with one, cutting the bottom nav down by a tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeadersScreen(
    standingsUiState: StandingsUiState,
    onStandingsRetry: () -> Unit,
    statsUiState: StatsUiState,
    onStatsRetry: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = 0) { LEADERS_PAGE_TITLES.size }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = BackgroundBase,
        topBar = {
            Column {
                TopAppBar(title = { Text("Leaders", color = TextPrimary) })
                TabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = BackgroundBase,
                    contentColor = TierWorthYourTime
                ) {
                    LEADERS_PAGE_TITLES.forEachIndexed { index, title ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = {
                                Text(
                                    text = title,
                                    color = if (pagerState.currentPage == index) TierWorthYourTime else TextSecondary
                                )
                            }
                        )
                    }
                }
            }
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
