package com.nbawatchability.app.data

/**
 * Which mutually-exclusive slate the Games tab is currently showing - never
 * both at once, unlike NBA + Summer League which are already unioned
 * server-side within the "nba" group. Mirrors backend/src/types.ts's
 * LeagueGroup; [apiValue] is the exact query-string value the backend expects.
 */
enum class LeagueGroup(val apiValue: String, val displayName: String) {
    NBA("nba", "NBA"),
    WNBA("wnba", "WNBA")
}
