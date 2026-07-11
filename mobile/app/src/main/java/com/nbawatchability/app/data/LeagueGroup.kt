package com.nbawatchability.app.data

/**
 * Which mutually-exclusive slate the Games tab is currently showing - never
 * both at once, unlike NBA + Summer League which are already unioned
 * server-side within the "nba" group. Mirrors backend/src/types.ts's
 * LeagueGroup; [apiValue] is the exact query-string value the backend expects.
 */
enum class LeagueGroup(val apiValue: String, val displayName: String, val logoUrl: String) {
    NBA("nba", "NBA", "https://a.espncdn.com/i/teamlogos/leagues/500/nba.png"),
    WNBA("wnba", "WNBA", "https://a.espncdn.com/i/teamlogos/leagues/500/wnba.png")
}
