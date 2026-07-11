package com.nbawatchability.app.data

import kotlinx.serialization.Serializable

@Serializable
data class StatLeader(
    val name: String,
    val team: String,
    val teamLogo: String? = null,
    val value: String
)

@Serializable
data class StatCategory(
    val key: String,
    val label: String,
    val abbr: String,
    val leaders: List<StatLeader>
)

@Serializable
data class StatsResponse(
    val season: String,
    val categories: List<StatCategory>
)
