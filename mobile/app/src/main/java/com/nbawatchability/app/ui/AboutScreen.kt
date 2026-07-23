package com.nbawatchability.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nbawatchability.app.BuildConfig
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextMuted
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary

// Tap the version number this many times to reach SecretScreen - deliberately
// undocumented anywhere in the app's own UI. 8, not a round number - Kobe's
// number after he switched from 8 to 24.
private const val TAPS_TO_UNLOCK_SECRET = 8

/** Reached from Settings rather than the bottom nav - frees up a nav slot for Starred. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, onSecretUnlocked: () -> Unit) {
    // Plain remember, not rememberSaveable/persisted - resets every time this
    // screen is (re)entered, same as a real hidden-menu tap counter should.
    var tapCount by remember { mutableIntStateOf(0) }

    Scaffold(
        containerColor = BackgroundBase,
        topBar = {
            TopAppBar(
                title = { Text("About", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "About — coming soon.",
                color = TextSecondary,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp)
            )
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                color = TextMuted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 8.dp).clickable {
                    tapCount++
                    if (tapCount >= TAPS_TO_UNLOCK_SECRET) {
                        tapCount = 0
                        onSecretUnlocked()
                    }
                }
            )
        }
    }
}
