package com.nbawatchability.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary

/**
 * Placeholder body for the nav-restructure phase - the tab itself, its bottom-nav
 * slot, and the scrollable-nav plumbing are real; favorite teams/players storage
 * and UI land in the next phase. No league selector here (favorites are global
 * across leagues, unlike every other tab), matching Settings' own scope.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyTeamsScreen() {
    Scaffold(
        containerColor = BackgroundBase,
        topBar = {
            TopAppBar(title = { Text("My Teams", color = TextPrimary) })
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Favorite teams and players are coming soon.",
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}
