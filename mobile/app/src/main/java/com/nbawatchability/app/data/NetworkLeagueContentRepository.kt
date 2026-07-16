package com.nbawatchability.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

private val json = Json { ignoreUnknownKeys = true }

/** Talks to the Standings/Stats/News/History endpoints - same request shape and error handling as [NetworkGameRepository]. */
object NetworkLeagueContentRepository {

    suspend fun standings(baseUrl: String, leagueGroup: LeagueGroup): StandingsResponse =
        get("$baseUrl/standings?leagueGroup=${leagueGroup.apiValue}")

    suspend fun stats(baseUrl: String, leagueGroup: LeagueGroup): StatsResponse =
        get("$baseUrl/stats?leagueGroup=${leagueGroup.apiValue}")

    suspend fun news(baseUrl: String, leagueGroup: LeagueGroup): NewsResponse =
        get("$baseUrl/news?leagueGroup=${leagueGroup.apiValue}")

    suspend fun history(baseUrl: String, start: LocalDate, end: LocalDate, leagueGroup: LeagueGroup): HistoryResponse =
        get("$baseUrl/api/history?start=$start&end=$end&leagueGroup=${leagueGroup.apiValue}")

    /** The real start of "This season" - the day after the most recently completed Finals game, per gameStore.ts's getMostRecentFinalsEnd. */
    suspend fun currentSeasonStart(baseUrl: String, leagueGroup: LeagueGroup): CurrentSeasonStartResponse =
        get("$baseUrl/current-season-start?leagueGroup=${leagueGroup.apiValue}")

    /** Real per-league team roster - backs the favorite-teams search/browse screen. */
    suspend fun teams(baseUrl: String, leagueGroup: LeagueGroup): TeamsResponse =
        get("$baseUrl/teams?leagueGroup=${leagueGroup.apiValue}")

    private suspend inline fun <reified T> get(url: String): T =
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            // Render's free/starter tier cold-starts a sleeping instance in
            // 30-60s+, so these need enough headroom to survive a wake-up.
            connection.connectTimeout = 45000
            connection.readTimeout = 45000
            connection.requestMethod = "GET"

            try {
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = BufferedReader(InputStreamReader(stream)).use { it.readText() }

                if (status !in 200..299) {
                    throw BackendRequestException("Backend returned HTTP $status")
                }

                json.decodeFromString(body)
            } finally {
                connection.disconnect()
            }
        }
}
