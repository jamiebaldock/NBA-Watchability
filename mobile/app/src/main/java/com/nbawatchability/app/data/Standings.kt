package com.nbawatchability.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StandingsTeam(
    val id: String,
    @SerialName("n") val name: String,
    @SerialName("ab") val abbreviation: String,
    @SerialName("lg") val logo: String? = null,
    @SerialName("w") val wins: Int,
    @SerialName("l") val losses: Int,
    val pct: String,
    val gb: String,
    val strk: String? = null
)

@Serializable
data class StandingsGroup(
    val name: String,
    val teams: List<StandingsTeam>
)

@Serializable
data class StandingsResponse(
    val season: String,
    val groups: List<StandingsGroup>
)
