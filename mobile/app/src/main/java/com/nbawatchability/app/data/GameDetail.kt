package com.nbawatchability.app.data

import kotlinx.serialization.Serializable

/** Mirrors backend/src/types.ts's GameDetailResponseJson - fetched on-demand when the user taps a finished tile (Phase G), never persisted. */
@Serializable
data class GameDetail(
    val topPerformers: List<TopPerformer> = emptyList(),
    val headToHead: List<HeadToHeadGame> = emptyList(),
    val awayStandings: TeamStandingsContext = TeamStandingsContext(),
    val homeStandings: TeamStandingsContext = TeamStandingsContext()
)

@Serializable
data class TopPerformer(
    val name: String,
    val team: String,
    val line: String
)

@Serializable
data class HeadToHeadGame(
    val eventId: String,
    val utc: String,
    val away: String,
    val home: String,
    val awayScore: Int,
    val homeScore: Int
)

/** Best-effort context, not a true "clinches playoff spot" computation - see backend/src/types.ts's TeamStandingsContextJson doc comment for why. Empty (all fields null) whenever the league doesn't have real standings data yet (e.g. soccer today). */
@Serializable
data class TeamStandingsContext(
    val rank: Int? = null,
    val record: String? = null,
    val groupName: String? = null
)
