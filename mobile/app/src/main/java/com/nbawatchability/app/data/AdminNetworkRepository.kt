package com.nbawatchability.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class LagPercentiles(val p50Ms: Long, val sampleCount: Int, val fromRealData: Boolean)

@Serializable
data class BudgetDay(val date: String, val count: Int)

@Serializable
data class AdminStats(
    val todayCount: Int,
    val dailyCap: Int,
    val budgetHistory: List<BudgetDay>,
    val lagPercentiles: Map<String, LagPercentiles>,
    val outcomeCounts: Map<String, Int>
)

@Serializable
data class AdminMissingGame(
    val eventId: String,
    val league: String,
    val leagueGroup: String,
    val away: String,
    val home: String,
    val tipoffUtc: String,
    val ytCheckCount: Int,
    val ytLastCheckedAt: String? = null
)

@Serializable
private data class MissingHighlightsResponse(val games: List<AdminMissingGame>)

@Serializable
private data class LoginRequestBody(val pin: String)

@Serializable
private data class LoginResponse(val token: String)

@Serializable
private data class ResendRequestBody(val eventId: String)

@Serializable
data class ResendResult(val matched: Boolean, val videoId: String? = null, val title: String? = null)

@Serializable
private data class AdminErrorResponse(val error: String? = null)

/** Thrown for a bad/expired token specifically, so the ViewModel can fall back to the PIN screen rather than showing a generic error. */
class AdminUnauthorizedException(message: String) : Exception(message)

private val json = Json { ignoreUnknownKeys = true }

/** Talks to devServer.ts's admin routes (adminService.ts) - the hidden Admin page's own backend. */
object AdminNetworkRepository {

    suspend fun login(baseUrl: String, pin: String): String = withContext(Dispatchers.IO) {
        val body = json.encodeToString(LoginRequestBody.serializer(), LoginRequestBody(pin))
        val response = post("$baseUrl/admin/login", body, token = null)
        json.decodeFromString(LoginResponse.serializer(), response).token
    }

    suspend fun stats(baseUrl: String, token: String): AdminStats = withContext(Dispatchers.IO) {
        val response = get("$baseUrl/admin/stats", token)
        json.decodeFromString(AdminStats.serializer(), response)
    }

    suspend fun missingHighlights(baseUrl: String, token: String): List<AdminMissingGame> = withContext(Dispatchers.IO) {
        val response = get("$baseUrl/admin/missing-highlights", token)
        json.decodeFromString(MissingHighlightsResponse.serializer(), response).games
    }

    suspend fun resendHighlights(baseUrl: String, token: String, eventId: String): ResendResult = withContext(Dispatchers.IO) {
        val body = json.encodeToString(ResendRequestBody.serializer(), ResendRequestBody(eventId))
        val response = post("$baseUrl/admin/resend-highlights", body, token)
        json.decodeFromString(ResendResult.serializer(), response)
    }

    private fun get(urlString: String, token: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = 45000
        connection.readTimeout = 45000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Authorization", "Bearer $token")
        return readResponse(connection)
    }

    private fun post(urlString: String, jsonBody: String, token: String?): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = 45000
        connection.readTimeout = 45000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(jsonBody) }
        return readResponse(connection)
    }

    private fun readResponse(connection: HttpURLConnection): String {
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = BufferedReader(InputStreamReader(stream)).use { it.readText() }
            if (status == 401) {
                val message = runCatching { json.decodeFromString(AdminErrorResponse.serializer(), body).error }.getOrNull()
                throw AdminUnauthorizedException(message ?: "unauthorized")
            }
            if (status !in 200..299) {
                val message = runCatching { json.decodeFromString(AdminErrorResponse.serializer(), body).error }.getOrNull()
                throw BackendRequestException(message ?: "Backend returned HTTP $status")
            }
            return body
        } finally {
            connection.disconnect()
        }
    }
}
