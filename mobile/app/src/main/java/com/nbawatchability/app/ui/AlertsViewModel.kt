package com.nbawatchability.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import com.nbawatchability.app.data.AlertDelivery
import com.nbawatchability.app.data.AlertTierThreshold
import com.nbawatchability.app.data.AlertsNetworkRepository
import com.nbawatchability.app.data.AlertsRepository
import com.nbawatchability.app.data.AlertsSettings
import com.nbawatchability.app.data.BACKEND_BASE_URL
import com.nbawatchability.app.data.BelledGameSnapshot
import com.nbawatchability.app.data.Game
import com.nbawatchability.app.data.Team
import com.nbawatchability.app.alerts.StartingSoonRefreshWorker
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Device registration (favorites/leagues sync + FCM token), the alert-
 * preferences Settings sub-screen, and the per-game bell. Registration is
 * fire-and-forget from the UI's perspective - a failed register (e.g. Render
 * cold-starting) just means alerts stay off until the next trigger (app
 * resume, a favorites change, a settings change, token rotation via
 * AlertsFirebaseMessagingService.onNewToken), never a blocking error the user sees.
 */
class AlertsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AlertsRepository(application.applicationContext)

    var belledGameIds by mutableStateOf<Set<String>>(emptySet())
        private set

    var settings by mutableStateOf(AlertsSettings())
        private set

    // Cached from the most recent registerDevice() call (AppRoot's
    // LaunchedEffect on favorites/enabledLeagues) so a settings-only change
    // from AlertsSettingsScreen can re-register without the caller needing
    // to pass favorites/leagues again.
    private var lastFavorites: List<Team> = emptyList()
    private var lastLeagues: List<String> = emptyList()

    init {
        viewModelScope.launch {
            repository.belledGameIds.collect { belledGameIds = it }
        }
        viewModelScope.launch {
            repository.settings.collect { settings = it }
        }
    }

    fun isBelled(eventId: String): Boolean = eventId in belledGameIds

    /**
     * Takes the whole Game (not just the id) so the bell stores an offline
     * tipoff/name snapshot alongside - what lets the starting-soon alarm for
     * a belled non-favorite game work with no further network. No-op for a
     * game with no eventId (pre-eventId cached rows; the bell isn't shown
     * for those anyway - GameCard gates on eventId != null).
     */
    fun toggleBell(game: Game) {
        val eventId = game.eventId ?: return
        val subscribe = eventId !in belledGameIds
        viewModelScope.launch {
            repository.setBelled(BelledGameSnapshot(eventId, game.away, game.home, game.tipoffUtc), subscribe)
            val deviceId = repository.getOrCreateDeviceId()
            runCatching { AlertsNetworkRepository.setGameSub(BACKEND_BASE_URL, deviceId, eventId, subscribe) }
            // A newly belled game inside the 48h alarm horizon needs its
            // alarm now, not at the next 12-hourly pass (and un-belling
            // needs the cancel now).
            StartingSoonRefreshWorker.enqueueOneShot(getApplication())
        }
    }

    fun setStartingSoonEnabled(value: Boolean) {
        viewModelScope.launch {
            repository.setStartingSoonEnabled(value)
            StartingSoonRefreshWorker.enqueueOneShot(getApplication())
        }
    }

    fun setLeadTimeMinutes(value: Int) {
        viewModelScope.launch {
            repository.setLeadTimeMinutes(value)
            StartingSoonRefreshWorker.enqueueOneShot(getApplication())
        }
    }

    /** Called whenever favorites or the enabled-leagues set changes, plus once on app start (AppRoot's LaunchedEffect). */
    fun registerDevice(favorites: List<Team>, leagues: List<String>) {
        lastFavorites = favorites
        lastLeagues = leagues
        viewModelScope.launch { doRegister() }
        // Favorites changing changes which games deserve starting-soon
        // alarms - reschedule alongside the server re-register.
        StartingSoonRefreshWorker.enqueueOneShot(getApplication())
    }

    fun setCloseSwingEnabled(value: Boolean) {
        viewModelScope.launch {
            repository.setCloseSwingEnabled(value)
            doRegister()
        }
    }

    fun setDelivery(value: AlertDelivery) {
        viewModelScope.launch {
            repository.setDelivery(value)
            doRegister()
        }
    }

    fun setFavoritesOnly(value: Boolean) {
        viewModelScope.launch {
            repository.setFavoritesOnly(value)
            doRegister()
        }
    }

    fun setTierThreshold(value: AlertTierThreshold?) {
        viewModelScope.launch {
            repository.setTierThreshold(value)
            doRegister()
        }
    }

    private suspend fun doRegister() {
        val deviceId = repository.getOrCreateDeviceId()
        val token = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull()
        if (token != null) repository.setFcmToken(token)
        val current = settings
        runCatching {
            AlertsNetworkRepository.registerDevice(
                baseUrl = BACKEND_BASE_URL,
                deviceId = deviceId,
                fcmToken = token,
                leagues = lastLeagues,
                favorites = lastFavorites,
                closeSwingEnabled = current.closeSwingEnabled,
                delivery = current.delivery,
                favoritesOnly = current.favoritesOnly,
                tierThreshold = current.tierThreshold
            )
        }
    }
}
