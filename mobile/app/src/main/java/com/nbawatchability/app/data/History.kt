package com.nbawatchability.app.data

import kotlinx.serialization.Serializable

@Serializable
data class HistoryResponse(
    val earliestDate: String,
    val games: List<Game>
)
