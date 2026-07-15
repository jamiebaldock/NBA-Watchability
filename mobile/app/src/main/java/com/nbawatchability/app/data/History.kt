package com.nbawatchability.app.data

import kotlinx.serialization.Serializable

@Serializable
data class HistoryResponse(
    val earliestDate: String,
    val seasons: List<String> = emptyList(),
    val games: List<Game>
)

@Serializable
data class CurrentSeasonStartResponse(val date: String)
