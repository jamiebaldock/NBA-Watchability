package com.nbawatchability.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AppDarkColorScheme = darkColorScheme(
    background = BackgroundBase,
    surface = SurfaceCard,
    surfaceVariant = SurfaceCardElevated,
    primary = TierWorthYourTime,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    error = LiveRed
)

@Composable
fun NbaWatchabilityTheme(content: @Composable () -> Unit) {
    // Always dark per spec section 3 — this is not a light/dark-adaptive app.
    MaterialTheme(
        colorScheme = AppDarkColorScheme,
        typography = AppTypography,
        content = content
    )
}
