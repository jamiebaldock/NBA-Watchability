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
    val isSupported: Boolean = true,
    // The big top-bar title (LeagueSelector.kt's TitleLeagueSelector) uses
    // this instead of [displayName] - most leagues' full names are already
    // this short (NBA, WNBA, NBL, UFC), so only the ones with a genuinely
    // long official name need a distinct value here. The dropdown list and
    // Settings' "Selected Sports" toggle both keep using [displayName] in
    // full, since there's room for it there.
    val shortDisplayName: String = displayName
) {
    NBA("nba", "NBA", "https://a.espncdn.com/i/teamlogos/leagues/500/nba.png"),
    WNBA("wnba", "WNBA", "https://a.espncdn.com/i/teamlogos/leagues/500/wnba.png"),
    // The default crest is a dark purple lion that reads poorly against this
    // app's near-black surfaces - ESPN's own "-dark" variant (a white
    // version, built for exactly this) is used instead, verified directly
    // against ESPN's asset set rather than assumed.
    EPL(
        "epl",
        "English Premier League",
        "https://a.espncdn.com/i/leaguelogos/soccer/500-dark/23.png",
        isSupported = false,
        shortDisplayName = "EPL"
    ),
    LA_LIGA("la-liga", "La Liga", "https://a.espncdn.com/i/leaguelogos/soccer/500/15.png", isSupported = false),
    NBL("nbl", "NBL", "https://a.espncdn.com/i/teamlogos/leagues/500/nbl.png", isSupported = false),
    UFC("ufc", "UFC", "https://a.espncdn.com/i/teamlogos/leagues/500/ufc.png", isSupported = false)
}
