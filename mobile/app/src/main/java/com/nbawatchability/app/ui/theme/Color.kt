package com.nbawatchability.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// nba-app-design-prompt.md palette — exact hex values from the design prompt
val DarkBackgroundBase = Color(0xFF101418)
val DarkSurfaceCard = Color(0xFF1A2027)
val DarkSurfaceCardElevated = Color(0xFF222A33)
val DarkTextPrimary = Color(0xFFE8EAED)
val DarkTextSecondary = Color(0xFF8A94A0)
val DarkTextMuted = Color(0xFF7A8592)

// Light theme's own palette - not just an inverted dark scheme, since a
// straight invert (e.g. pure white background) reads harsher than a design
// deliberately built for light surfaces. Chosen to keep the same relative
// contrast steps (primary/secondary/muted text) the dark palette already has.
val LightBackgroundBase = Color(0xFFF4F5F7)
val LightSurfaceCard = Color(0xFFFFFFFF)
val LightSurfaceCardElevated = Color(0xFFEBEDF1)
val LightTextPrimary = Color(0xFF14181D)
val LightTextSecondary = Color(0xFF4B5563)
val LightTextMuted = Color(0xFF6B7280)

// Below: theme-aware properties every screen actually imports/reads (e.g.
// `import com.nbawatchability.app.ui.theme.TextPrimary`) - a composable
// property getter (not a plain val) so every existing call site keeps
// working unchanged while still reacting to LocalIsLightTheme. This is the
// same pattern MaterialTheme.colorScheme.background itself uses internally.
val BackgroundBase: Color @Composable get() = if (LocalIsLightTheme.current) LightBackgroundBase else DarkBackgroundBase
val SurfaceCard: Color @Composable get() = if (LocalIsLightTheme.current) LightSurfaceCard else DarkSurfaceCard
val SurfaceCardElevated: Color @Composable get() = if (LocalIsLightTheme.current) LightSurfaceCardElevated else DarkSurfaceCardElevated
val TextPrimary: Color @Composable get() = if (LocalIsLightTheme.current) LightTextPrimary else DarkTextPrimary
val TextSecondary: Color @Composable get() = if (LocalIsLightTheme.current) LightTextSecondary else DarkTextSecondary
val TextMuted: Color @Composable get() = if (LocalIsLightTheme.current) LightTextMuted else DarkTextMuted

// Status accents
val LiveRed = Color(0xFFE8452C)

// Tier colors - kept identical across both themes. Each already carries
// enough saturation/contrast to read clearly against both a near-black and
// a near-white surface (verified visually on-device in both modes), so a
// separate light variant per tier would be a distinction without a
// difference.
val TierInstantClassic = Color(0xFFFFB020)
val TierWorthYourTime = Color(0xFF33C2A8) // not specified in design prompt; distinct from Solid's blue
val TierSolid = Color(0xFF6FA8DC)
val TierSkippable = Color(0xFF7A8592)

// Favorite-team marking (GameCard's TeamRow) - deliberately a violet/purple,
// nowhere else in this palette (amber/teal/blue/gray/red), so a favorited
// team's tint never reads as a tier color at a glance.
val FavoriteAccent = Color(0xFFB388FF)
