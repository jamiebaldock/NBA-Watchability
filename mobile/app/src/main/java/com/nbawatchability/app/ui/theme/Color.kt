package com.nbawatchability.app.ui.theme

import androidx.compose.ui.graphics.Color

// nba-app-design-prompt.md palette — exact hex values from the design prompt
val BackgroundBase = Color(0xFF101418)
val SurfaceCard = Color(0xFF1A2027)
val SurfaceCardElevated = Color(0xFF222A33)
val TextPrimary = Color(0xFFE8EAED)
val TextSecondary = Color(0xFF8A94A0)
val TextMuted = Color(0xFF7A8592)

// Status accents
val LiveRed = Color(0xFFE8452C)

// Tier colors
val TierInstantClassic = Color(0xFFFFB020)
val TierWorthYourTime = Color(0xFF33C2A8) // not specified in design prompt; distinct from Solid's blue
val TierSolid = Color(0xFF6FA8DC)
val TierSkippable = Color(0xFF7A8592)

// Favorite-team marking (GameCard's TeamRow) - deliberately a violet/purple,
// nowhere else in this palette (amber/teal/blue/gray/red), so a favorited
// team's tint never reads as a tier color at a glance.
val FavoriteAccent = Color(0xFFB388FF)
