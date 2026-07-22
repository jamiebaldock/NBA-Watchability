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
private data class AlertFavorite(val teamName: String, val leagueGroup: String?)

@Serializable
private data class RegisterDeviceBody(
    val deviceId: String,
    val fcmToken: String?,
    val leagues: List<String>,
    val favorites: List<AlertFavorite>,
    val closeSwingEnabled: Boolean,
    val delivery: String,
    val favoritesOnly: Boolean,
    val tierThreshold: String?
)

@Serializable
private data class GameSubBody(val deviceId: String, val eventId: String, val subscribed: Boolean)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Talks to the Alerts phase-1 backend routes (alertsService.ts). Same
 * HttpURLConnection + 45s-timeout shape as NetworkGameRepository (Render
 * free-tier cold-start headroom), just with doOutput=true and a JSON body -
 * these are this app's first POST calls.
 */
object AlertsNetworkRepository {

    suspend fun registerDevice(
        baseUrl: String,
        deviceId: String,
        fcmToken: String?,
        leagues: List<String>,
        favorites: List<Team>,
        closeSwingEnabled: Boolean = true,
        delivery: AlertDelivery = AlertDelivery.BOTH,
        favoritesOnly: Boolean = true,
        tierThreshold: AlertTierThreshold? = null
    ): Unit = withContext(Dispatchers.IO) {
        val body = RegisterDeviceBody(
            deviceId = deviceId,
            fcmToken = fcmToken,
            leagues = leagues,
            favorites = favorites.map { AlertFavorite(teamName = it.name, leagueGroup = it.leagueGroup) },
            closeSwingEnabled = closeSwingEnabled,
            delivery = delivery.apiValue,
            favoritesOnly = favoritesOnly,
            tierThreshold = tierThreshold?.apiValue
        )
        post("$baseUrl/alerts/register", json.encodeToString(RegisterDeviceBody.serializer(), body))
    }

    suspend fun setGameSub(baseUrl: String, deviceId: String, eventId: String, subscribed: Boolean): Unit =
        withContext(Dispatchers.IO) {
            val body = GameSubBody(deviceId = deviceId, eventId = eventId, subscribed = subscribed)
            post("$baseUrl/alerts/game-sub", json.encodeToString(GameSubBody.serializer(), body))
        }

    private fun post(urlString: String, jsonBody: String) {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = 45000
        connection.readTimeout = 45000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(jsonBody) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = BufferedReader(InputStreamReader(stream)).use { it.readText() }
            if (status !in 200..299) {
                throw BackendRequestException("Alerts backend returned HTTP $status: $responseBody")
            }
        } finally {
            connection.disconnect()
        }
    }
}
