package com.nbawatchability.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Read by Color.kt's composable-getter palette properties (TextPrimary,
 * BackgroundBase, etc.) and by ThemeAwareLogo.kt's per-theme logo URL swap -
 * this is how a setting stored two composition levels up in AppRoot.kt
 * reaches every screen's color/logo choices without threading a parameter
 * through every intermediate composable. staticCompositionLocalOf (not
 * compositionLocalOf) since a theme flip is rare enough that a full
 * recomposition of everything below the provider is an acceptable cost for
 * the simpler, faster reads everywhere else.
 */
val LocalIsLightTheme = staticCompositionLocalOf { false }

private val AppDarkColorScheme = darkColorScheme(
    background = DarkBackgroundBase,
    surface = DarkSurfaceCard,
    surfaceVariant = DarkSurfaceCardElevated,
    primary = TierWorthYourTime,
    onBackground = DarkTextPrimary,
    onSurface = DarkTextPrimary,
    onSurfaceVariant = DarkTextSecondary,
    error = LiveRed
)

private val AppLightColorScheme = lightColorScheme(
    background = LightBackgroundBase,
    surface = LightSurfaceCard,
    surfaceVariant = LightSurfaceCardElevated,
    primary = TierWorthYourTime,
    onBackground = LightTextPrimary,
    onSurface = LightTextPrimary,
    onSurfaceVariant = LightTextSecondary,
    error = LiveRed
)

/**
 * [isLightTheme] is a persisted Settings choice (AppSettings.lightTheme),
 * read once in MainActivity before this composable's content is ever built -
 * unlike every other setting in this app, chrome color can't wait for a
 * screen to compose and then react, since MaterialTheme itself needs the
 * answer up front.
 */
@Composable
fun NbaWatchabilityTheme(isLightTheme: Boolean = false, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIsLightTheme provides isLightTheme) {
        MaterialTheme(
            colorScheme = if (isLightTheme) AppLightColorScheme else AppDarkColorScheme,
            typography = AppTypography,
            content = content
        )
    }
}
