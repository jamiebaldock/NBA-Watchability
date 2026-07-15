package com.nbawatchability.app.data

/**
 * Which mutually-exclusive slate the Games tab is currently showing - never
 * both at once, unlike NBA + Summer League which are already unioned
 * server-side within the "nba" group. Mirrors backend/src/types.ts's
 * LeagueGroup; [apiValue] is the exact query-string value the backend expects
 * - only meaningful for [isSupported] leagues, since the backend has no
 * route logic for the placeholder ones at all yet.
 *
 * [isSupported] gates every tab (Games/Starred/History/Leaders/News) to a
 * shared "coming soon" state instead of firing a network call the backend
 * doesn't recognize - see AppRoot.kt's ComingSoonTab. These four are
 * placeholders only: no game tiles, scoring, or backend data exists for
 * them yet. Logo URLs verified directly against ESPN's site API per league
 * (not guessed), same as every other logo in this codebase.
 */
enum class LeagueGroup(
    val apiValue: String,
    val displayName: String,
    val logoUrl: String,
    val isSupported: Boolean = true
) {
    NBA("nba", "NBA", "https://a.espncdn.com/i/teamlogos/leagues/500/nba.png"),
    WNBA("wnba", "WNBA", "https://a.espncdn.com/i/teamlogos/leagues/500/wnba.png"),
    EPL("epl", "English Premier League", "https://a.espncdn.com/i/leaguelogos/soccer/500/23.png", isSupported = false),
    LA_LIGA("la-liga", "La Liga", "https://a.espncdn.com/i/leaguelogos/soccer/500/15.png", isSupported = false),
    NBL("nbl", "NBL", "https://a.espncdn.com/i/teamlogos/leagues/500/nbl.png", isSupported = false),
    UFC("ufc", "UFC", "https://a.espncdn.com/i/teamlogos/leagues/500/ufc.png", isSupported = false)
}
