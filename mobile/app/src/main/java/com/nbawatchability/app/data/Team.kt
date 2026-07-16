package com.nbawatchability.app.data

import kotlinx.serialization.Serializable

/** A real team in a league's current roster - backs the favorite-teams search/browse screen (backend/src/teamsService.ts). */
@Serializable
data class Team(
    val name: String,
    val logo: String? = null
)

@Serializable
data class TeamsResponse(
    val teams: List<Team>
)
