package com.nbawatchability.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

@Serializable
private data class DayGamesResponse(val date: String, val games: List<Game>)

@Serializable
private data class ScheduleResponse(val schedule: List<DayGamesResponse>)

@Serializable
private data class ErrorResponse(val error: String? = null)

class BackendRequestException(message: String) : Exception(message)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Talks to the real backend (nba-watchability-spec.md section 4/5). Point
 * [baseUrl] at wherever the backend dev server (or, later, the deployed
 * cloud function) is reachable.
 */
object NetworkGameRepository {

    suspend fun schedule(baseUrl: String, start: LocalDate, end: LocalDate): List<DayGames> =
        withContext(Dispatchers.IO) {
            val url = URL("$baseUrl/schedule?start=$start&end=$end")
            val connection = url.openConnection() as HttpURLConnection
            // Render's free tier cold-starts a sleeping instance in 30-60s+, so
            // these need enough headroom to survive a wake-up rather than a
            // typical request.
            connection.connectTimeout = 45000
            connection.readTimeout = 45000
            connection.requestMethod = "GET"

            try {
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = BufferedReader(InputStreamReader(stream)).use { it.readText() }

                if (status !in 200..299) {
                    val message = runCatching { json.decodeFromString<ErrorResponse>(body).error }.getOrNull()
                    throw BackendRequestException(message ?: "Backend returned HTTP $status")
                }

                val parsed = json.decodeFromString<ScheduleResponse>(body)
                parsed.schedule.map { DayGames(date = LocalDate.parse(it.date), games = it.games) }
            } finally {
                connection.disconnect()
            }
        }
}
