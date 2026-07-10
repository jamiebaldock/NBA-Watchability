package com.nbawatchability.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nbawatchability.app.ui.DayTabsScreen
import com.nbawatchability.app.ui.GameListViewModel
import com.nbawatchability.app.ui.ScheduleUiState
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary
import com.nbawatchability.app.ui.theme.NbaWatchabilityTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NbaWatchabilityTheme {
                val viewModel: GameListViewModel = viewModel()
                when (val state = viewModel.uiState) {
                    is ScheduleUiState.Loading -> LoadingScreen()
                    is ScheduleUiState.Error -> ErrorScreen(state.message, onRetry = viewModel::load)
                    is ScheduleUiState.Loaded -> DayTabsScreen(
                        days = state.days,
                        today = viewModel.today,
                        selectedDayIndex = viewModel.selectedDayIndex,
                        onDaySelected = viewModel::selectDay,
                        showNumericScore = viewModel.showNumericScore,
                        onToggleNumericScore = viewModel::toggleNumericScore,
                        sortBestFirst = viewModel.sortBestFirst,
                        onToggleSort = viewModel::toggleSortBestFirst,
                        isRefreshing = viewModel.isRefreshing,
                        onRefresh = viewModel::refresh
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Scaffold(containerColor = BackgroundBase) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun ErrorScreen(message: String, onRetry: () -> Unit) {
    Scaffold(containerColor = BackgroundBase) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Couldn't load games",
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = message,
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
            )
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}
