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
    val id: String = ""
)

@Serializable
data class TeamsResponse(
    val teams: List<Team>
)

/** A real player on a team's current roster - backs the favorite-players search/browse screen (backend/src/rosterService.ts). */
@Serializable
data class Player(
    val id: String,
    val name: String
)

@Serializable
data class RosterResponse(
    val players: List<Player>
)

/**
 * A favorited player, as persisted by FavoritesRepository - stores the
 * team's display name alongside the player's own name (not just an id)
 * since My Teams needs to show "which team is this player on" without a
 * fresh network round-trip, same reasoning as Team's persisted logo.
 */
@Serializable
data class FavoritePlayer(
    val name: String,
    val team: String
)
