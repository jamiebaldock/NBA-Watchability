package com.nbawatchability.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

private val SELECTED_LEAGUE_KEY = stringPreferencesKey("selected_league")
private val SHOW_NUMERIC_SCORE_KEY = booleanPreferencesKey("show_numeric_score")
private val ENABLED_LEAGUES_KEY = stringSetPreferencesKey("enabled_leagues")
private val BUMP_FAVORITE_TEAM_GAMES_KEY = booleanPreferencesKey("bump_favorite_team_games")
// Raw BottomNavTab enum name (e.g. "GAMES") - stored as a plain string rather
// than a typed enum so this data-layer file doesn't need to depend on the ui
// package's BottomNavTab; AppRoot.kt resolves the string back to an enum.
private val DEFAULT_LANDING_TAB_KEY = stringPreferencesKey("default_landing_tab")
private val HISTORY_SHOW_SCORES_BY_DEFAULT_KEY = booleanPreferencesKey("history_show_scores_by_default")
private val MIN_TIER_FILTER_ENABLED_KEY = booleanPreferencesKey("min_tier_filter_enabled")
// Raw Tier enum name (e.g. "SOLID") - Tier already lives in this same data
// package (Game.kt), so no cross-layer dependency issue here.
private val MIN_TIER_FILTER_KEY = stringPreferencesKey("min_tier_filter")
private val WIFI_ONLY_HIGHLIGHTS_KEY = booleanPreferencesKey("wifi_only_highlights")
private val LIGHT_THEME_KEY = booleanPreferencesKey("light_theme")
// Raw GameDetailTab enum name (e.g. "BREAKDOWN") - which tab the game-detail
// popup opens on, same string-not-enum reasoning as DEFAULT_LANDING_TAB_KEY
// above (GameDetailTab lives in the ui package).
private val DEFAULT_GAME_DETAIL_TAB_KEY = stringPreferencesKey("default_game_detail_tab")

// Only the core US sports set ships enabled by default for now - NBA/WNBA/MLB
// (all fully built) plus NHL/NFL (queued next, still placeholders) - every
// other LeagueGroup entry (F1, cricket, other basketball/soccer leagues,
// etc.) stays defined in the enum for later but is hidden from the dropdown
// and Settings' "Selected Leagues" list until then, per James's call.
private val DEFAULT_ENABLED_LEAGUES = setOf(LeagueGroup.NBA, LeagueGroup.WNBA, LeagueGroup.NHL, LeagueGroup.MLB, LeagueGroup.NFL)
    .map { it.apiValue }.toSet()

data class AppSettings(
    val selectedLeague: LeagueGroup = LeagueGroup.NBA,
    val showNumericScore: Boolean = false,
    val enabledLeagues: Set<LeagueGroup> = LeagueGroup.entries.toSet(),
    // Default (false) is "visual marking only" - a favorited team's games
    // still show in whatever order the tab already uses (score/date), just
    // tinted. Turning this on additionally bumps them to the top of that
    // same order, as a stable partition rather than a re-sort by score.
    val bumpFavoriteTeamGames: Boolean = false,
    // BottomNavTab's enum name (e.g. "GAMES") - which tab the app opens on.
    val defaultLandingTab: String = "GAMES",
    // Default (false) preserves History's existing spoiler-safe behavior:
    // scores hidden every time the screen is (re)composed. Turning this on
    // makes History start with scores already showing instead, for a user
    // who's decided they don't mind seeing them.
    val historyShowScoresByDefault: Boolean = false,
    val minTierFilterEnabled: Boolean = false,
    // Tier's enum name (e.g. "SOLID") - the lowest tier still shown when the
    // filter above is enabled; irrelevant while disabled.
    val minTierFilter: String = "SKIPPABLE",
    // Default (false) never restricts highlights playback. Turning this on
    // requires an active Wi-Fi connection before HighlightsPlayerScreen
    // starts loading the video, prompting instead if only cellular is available.
    val wifiOnlyHighlights: Boolean = false,
    // Default (false) keeps the app's original always-dark chrome. See
    // ui/theme/Theme.kt/Color.kt for how this reaches every screen, and
    // ui/theme/ThemeAwareLogo.kt for why some team/league logos need their
    // own per-theme URL swap on top of the chrome itself.
    val lightTheme: Boolean = false,
    // BottomNavTab and GameDetailTab enum name reasoning, see comment above
    // this key's declaration - which of the two tabs the game-detail popup
    // opens on.
    val defaultGameDetailTab: String = "BREAKDOWN"
)

/** Persists the last-selected league, which leagues show in the dropdown, and Games-tab display prefs (numeric score) - on-device only, so they survive an app restart. */
class AppSettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.appSettingsDataStore.data.map { prefs ->
        val enabledApiValues = prefs[ENABLED_LEAGUES_KEY] ?: DEFAULT_ENABLED_LEAGUES
        AppSettings(
            selectedLeague = LeagueGroup.entries.find { it.apiValue == prefs[SELECTED_LEAGUE_KEY] } ?: LeagueGroup.NBA,
            showNumericScore = prefs[SHOW_NUMERIC_SCORE_KEY] ?: false,
            enabledLeagues = LeagueGroup.entries.filter { it.apiValue in enabledApiValues }.toSet(),
            bumpFavoriteTeamGames = prefs[BUMP_FAVORITE_TEAM_GAMES_KEY] ?: false,
            defaultLandingTab = prefs[DEFAULT_LANDING_TAB_KEY] ?: "GAMES",
            historyShowScoresByDefault = prefs[HISTORY_SHOW_SCORES_BY_DEFAULT_KEY] ?: false,
            minTierFilterEnabled = prefs[MIN_TIER_FILTER_ENABLED_KEY] ?: false,
            minTierFilter = prefs[MIN_TIER_FILTER_KEY] ?: "SKIPPABLE",
            wifiOnlyHighlights = prefs[WIFI_ONLY_HIGHLIGHTS_KEY] ?: false,
            lightTheme = prefs[LIGHT_THEME_KEY] ?: false,
            defaultGameDetailTab = prefs[DEFAULT_GAME_DETAIL_TAB_KEY] ?: "BREAKDOWN"
        )
    }

    suspend fun setSelectedLeague(league: LeagueGroup) {
        context.appSettingsDataStore.edit { it[SELECTED_LEAGUE_KEY] = league.apiValue }
    }

    suspend fun setShowNumericScore(value: Boolean) {
        context.appSettingsDataStore.edit { it[SHOW_NUMERIC_SCORE_KEY] = value }
    }

    suspend fun setEnabledLeagues(leagues: Set<LeagueGroup>) {
        context.appSettingsDataStore.edit { it[ENABLED_LEAGUES_KEY] = leagues.map { league -> league.apiValue }.toSet() }
    }

    suspend fun setBumpFavoriteTeamGames(value: Boolean) {
        context.appSettingsDataStore.edit { it[BUMP_FAVORITE_TEAM_GAMES_KEY] = value }
    }

    suspend fun setDefaultLandingTab(tabName: String) {
        context.appSettingsDataStore.edit { it[DEFAULT_LANDING_TAB_KEY] = tabName }
    }

    suspend fun setHistoryShowScoresByDefault(value: Boolean) {
        context.appSettingsDataStore.edit { it[HISTORY_SHOW_SCORES_BY_DEFAULT_KEY] = value }
    }

    suspend fun setMinTierFilterEnabled(value: Boolean) {
        context.appSettingsDataStore.edit { it[MIN_TIER_FILTER_ENABLED_KEY] = value }
    }

    suspend fun setMinTierFilter(tierName: String) {
        context.appSettingsDataStore.edit { it[MIN_TIER_FILTER_KEY] = tierName }
    }

    suspend fun setWifiOnlyHighlights(value: Boolean) {
        context.appSettingsDataStore.edit { it[WIFI_ONLY_HIGHLIGHTS_KEY] = value }
    }

    suspend fun setLightTheme(value: Boolean) {
        context.appSettingsDataStore.edit { it[LIGHT_THEME_KEY] = value }
    }

    suspend fun setDefaultGameDetailTab(tabName: String) {
        context.appSettingsDataStore.edit { it[DEFAULT_GAME_DETAIL_TAB_KEY] = tabName }
    }
}
