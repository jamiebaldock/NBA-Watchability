package com.nbawatchability.app.data

import kotlinx.serialization.Serializable

/** A real team in a league's current roster - backs the favorite-teams search/browse screen (backend/src/teamsService.ts). */
@Serializable
data class Team(
    val name: String,
    val logo: String? = null,
    // ESPN's own team id - absent on Team snapshots persisted before this
    // field existed (a favorited team saved under the old shape decodes with
    // id = ""), which only matters for the roster drill-down entry point,
    // not for rendering an already-favorited team's name/logo/tint. Kept
    // last (with a default) so every existing positional Team(name, logo)
    // call site keeps compiling unchanged.
    val id: String = "",
    // LeagueGroup's own apiValue (e.g. "nba", "mlb") - stored as a plain
    // string rather than the LeagueGroup type itself (which isn't
    // @Serializable), same reasoning as AppSettingsRepository's enum-as-
    // string keys. Null on any Team favorited before this field existed;
    // FavoritesViewModel's per-league cap check treats a null leagueGroup
    // as its own bucket rather than crashing or silently merging it into
    // a real league's count.
    val leagueGroup: String? = null
)

@Serializable
data class TeamsResponse(
    val teams: List<Team>
)

/** A real player on a team's current roster - backs the favorite-players search/browse screen (backend/src/rosterService.ts). */
@Serializable
data class Player(
    val id: String,
    val name: String,
    // Real ESPN headshot photo URL - present for NBA/WNBA/MLB/NFL/NHL (all
    // five roster endpoints carry a headshot.href field, confirmed directly
    // against real responses). Null only if ESPN itself doesn't have a photo
    // for a specific player.
    val headshot: String? = null
)

@Serializable
data class RosterResponse(
    val players: List<Player>
)

/**
 * A favorited player, as persisted by FavoritesRepository - stores the
 * team's display name alongside the player's own name (not just an id)
 * since the Favorites tab needs to show "which team is this player on" without a
 * fresh network round-trip, same reasoning as Team's persisted logo.
 */
@Serializable
data class FavoritePlayer(
    val name: String,
    val team: String,
    // LeagueGroup's own apiValue - same reasoning as Team.leagueGroup. Null
    // on any FavoritePlayer favorited before this field existed;
    // FavoritesViewModel's per-league cap check treats a null leagueGroup as
    // its own bucket rather than crashing or silently merging it into a real
    // league's count.
    val leagueGroup: String? = null,
    // Snapshotted from Player.headshot at favorite-time, same reasoning as
    // Team.logo - avoids a network round-trip just to render the Favorites
    // tab's Players page. Present for NBA/WNBA/MLB; null for NFL/NHL (no
    // roster route built yet), and for anyone favorited via the
    // standout-performance callout (GameCard.kt), which has no roster
    // headshot data available at that point.
    val headshot: String? = null
)
