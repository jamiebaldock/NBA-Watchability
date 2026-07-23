package com.nbawatchability.app.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Easter egg (AppSettings.playerHaterMode, unlocked via AboutScreen's
 * version-tap counter -> SecretScreen). Read directly by
 * StandoutPerformerCallout (GameCard.kt) instead of threading a parameter
 * through every GameCard call site (DayTabsScreen/StarredScreen/
 * FavoritesScreen/HistoryScreen and the Tab composables wrapping each) -
 * same reasoning as ui/theme/Theme.kt's LocalIsLightTheme, a purely cosmetic
 * cross-cutting toggle with no business-logic dependents. Provided once in
 * AppRoot.kt around the bottom-nav Scaffold (the only place any GameCard
 * actually renders).
 */
val LocalPlayerHaterMode = staticCompositionLocalOf { false }

/**
 * Names of players explicitly marked "hated" (FavoritesViewModel.hatedPlayers,
 * managed from a checkbox on Favorites' Players page and
 * FavoritePlayersScreen's search/browse rows - James's call: only players on
 * this list get roasted when [LocalPlayerHaterMode] is on, not every
 * standout performer). Same CompositionLocal-over-threading reasoning as
 * LocalPlayerHaterMode above - provided at the same call site, alongside it.
 */
val LocalHatedPlayerNames = staticCompositionLocalOf<Set<String>> { emptySet() }
