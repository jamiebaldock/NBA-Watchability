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
private data class NextGameDateResponse(val date: String? = null)

@Serializable
private data class SeasonWindowResponse(val start: String? = null, val end: String? = null)

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

    suspend fun schedule(
        baseUrl: String,
        start: LocalDate,
        end: LocalDate,
        leagueGroup: LeagueGroup = LeagueGroup.NBA
    ): List<DayGames> =
        withContext(Dispatchers.IO) {
            val url = URL("$baseUrl/schedule?start=$start&end=$end&leagueGroup=${leagueGroup.apiValue}")
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

    /**
     * The next date, strictly after [after], that [leagueGroup] has a real
     * scheduled game - null if none is known yet (e.g. a season boundary
     * where the next schedule hasn't been announced). Backs the Games tab's
     * "jump to next game" action on an empty day.
     */
    suspend fun nextGameDate(
        baseUrl: String,
        after: LocalDate,
        leagueGroup: LeagueGroup = LeagueGroup.NBA
    ): LocalDate? =
        withContext(Dispatchers.IO) {
            val url = URL("$baseUrl/next-game-date?after=$after&leagueGroup=${leagueGroup.apiValue}")
            val connection = url.openConnection() as HttpURLConnection
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

                json.decodeFromString<NextGameDateResponse>(body).date?.let { LocalDate.parse(it) }
            } finally {
                connection.disconnect()
            }
        }

    /**
     * The real start (first regular-season-or-later game, excluding
     * preseason) through end (latest date ESPN's schedule currently knows
     * about) of [leagueGroup]'s current season - null if ESPN doesn't have
     * enough of the season scheduled yet to determine this. Backs the Games
     * tab's full-season day-tab range for leagues that support it.
     */
    suspend fun seasonWindow(
        baseUrl: String,
        leagueGroup: LeagueGroup = LeagueGroup.NBA
    ): Pair<LocalDate, LocalDate>? =
        withContext(Dispatchers.IO) {
            val url = URL("$baseUrl/season-window?leagueGroup=${leagueGroup.apiValue}")
            val connection = url.openConnection() as HttpURLConnection
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

                val parsed = json.decodeFromString<SeasonWindowResponse>(body)
                if (parsed.start != null && parsed.end != null) {
                    LocalDate.parse(parsed.start) to LocalDate.parse(parsed.end)
                } else {
                    null
                }
            } finally {
                connection.disconnect()
            }
        }

    /**
     * A single team's own real schedule (past and upcoming, current season,
     * no client-side date windowing) - backs the Favorites tab's Games page,
     * which fetches this once per favorited team and merges the results
     * rather than scanning a date range per league.
     */
    suspend fun teamSchedule(
        baseUrl: String,
        teamId: String,
        leagueGroup: LeagueGroup
    ): List<Game> =
        withContext(Dispatchers.IO) {
            val url = URL("$baseUrl/team-schedule?teamId=$teamId&leagueGroup=${leagueGroup.apiValue}")
            val connection = url.openConnection() as HttpURLConnection
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

                json.decodeFromString<List<Game>>(body)
            } finally {
                connection.disconnect()
            }
        }

    /**
     * Per-day game counts for one calendar month - backs the Games tab's
     * season-calendar picker. Fetched one month at a time as the user
     * navigates the calendar (not the whole year up front) since the
     * backend's own scheduleCountsService.ts is deliberately cheap per call,
     * not because a bigger request would be rejected - see that file's
     * comment. Days with zero games are simply absent from the map (the
     * calendar renders those cells blank, not "0").
     */
    suspend fun scheduleCounts(
        baseUrl: String,
        year: Int,
        month: Int,
        leagueGroup: LeagueGroup
    ): Map<LocalDate, Int> =
        withContext(Dispatchers.IO) {
            val url = URL("$baseUrl/schedule-counts?year=$year&month=$month&leagueGroup=${leagueGroup.apiValue}")
            val connection = url.openConnection() as HttpURLConnection
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

                json.decodeFromString<Map<String, Int>>(body).mapKeys { (dateStr, _) -> LocalDate.parse(dateStr) }
            } finally {
                connection.disconnect()
            }
        }
}
