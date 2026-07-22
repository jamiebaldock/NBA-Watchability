package com.nbawatchability.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.alertsDataStore: DataStore<Preferences> by preferencesDataStore(name = "alerts")

private val DEVICE_ID_KEY = stringPreferencesKey("device_id")
private val FCM_TOKEN_KEY = stringPreferencesKey("fcm_token")
private val BELLED_GAME_IDS_KEY = stringSetPreferencesKey("belled_game_ids")
private val BELLED_SNAPSHOTS_KEY = stringPreferencesKey("belled_snapshots_json")
private val SCHEDULED_ALARM_IDS_KEY = stringSetPreferencesKey("scheduled_alarm_event_ids")
private val CLOSE_SWING_ENABLED_KEY = booleanPreferencesKey("close_swing_enabled")
private val STARTING_SOON_ENABLED_KEY = booleanPreferencesKey("starting_soon_enabled")
private val LEAD_TIME_MINUTES_KEY = intPreferencesKey("lead_time_minutes")
private val DELIVERY_KEY = stringPreferencesKey("delivery")
private val FAVORITES_ONLY_KEY = booleanPreferencesKey("favorites_only")
private val TIER_THRESHOLD_KEY = stringPreferencesKey("tier_threshold")

private val json = Json { ignoreUnknownKeys = true }

/**
 * Everything StartingSoonScheduler needs to alarm a belled game without any
 * network call - captured from the Game object at bell-toggle time (the
 * tile has it right there), since a belled non-favorite game has no other
 * offline source for its tipoff/names.
 */
@Serializable
data class BelledGameSnapshot(
    val eventId: String,
    val away: String,
    val home: String,
    val tipoffUtc: String
)

/** Mirrors alertStore.ts's AlertDelivery ("push" | "in_app" | "both"). */
enum class AlertDelivery(val apiValue: String, val label: String) {
    PUSH("push", "Push notification only"),
    IN_APP("in_app", "In-app banner only"),
    BOTH("both", "Push and in-app")
}

/** Mirrors alertStore.ts's AlertTierThreshold - null means "off" (favorites-only alerts, no non-favorite close-swing alerts at all). */
enum class AlertTierThreshold(val apiValue: String, val label: String) {
    SOLID("solid", "Solid or better"),
    WORTH_YOUR_TIME("worth_your_time", "Worth Your Time or better"),
    INSTANT_CLASSIC("instant_classic", "Instant Classic only")
}

data class AlertsSettings(
    val closeSwingEnabled: Boolean = true,
    // Starting-soon is entirely client-side (local alarms) - synced nowhere,
    // enforced by StartingSoonScheduler/AlarmReceiver alone.
    val startingSoonEnabled: Boolean = true,
    val leadTimeMinutes: Int = 15,
    val delivery: AlertDelivery = AlertDelivery.BOTH,
    val favoritesOnly: Boolean = true,
    val tierThreshold: AlertTierThreshold? = null
)

/** The lead-time choices offered in AlertsSettingsScreen - default 15 (James's confirmed presets). */
val LEAD_TIME_OPTIONS_MINUTES = listOf(5, 15, 30, 60)

/**
 * On-device half of the Alerts feature's storage (server half: alertStore.ts).
 * Deliberately minimal for this first client pass - no Settings UI for
 * delivery/tier-threshold/close-swing-enabled yet, so registration always
 * sends favorites + leagues and lets the backend's own defaults (both delivery,
 * favoritesOnly true, no tier threshold, close-swing on) apply.
 */
class AlertsRepository(private val context: Context) {

    /**
     * Stable per-install identity for /alerts/register and /alerts/game-sub -
     * generated once on first read and persisted forever after, independent of
     * the FCM token (which rotates). Suspends because DataStore's edit is
     * suspend; safe to call from a ViewModel's init coroutine.
     */
    suspend fun getOrCreateDeviceId(): String {
        val existing = context.alertsDataStore.data.map { it[DEVICE_ID_KEY] }.first()
        if (existing != null) return existing
        val generated = UUID.randomUUID().toString()
        context.alertsDataStore.edit { it[DEVICE_ID_KEY] = generated }
        return generated
    }

    val fcmToken: Flow<String?> = context.alertsDataStore.data.map { it[FCM_TOKEN_KEY] }

    suspend fun setFcmToken(token: String) {
        context.alertsDataStore.edit { it[FCM_TOKEN_KEY] = token }
    }

    /** The per-game bell - which eventIds this device has subscribed to, mirrored to alert_game_subs server-side. */
    val belledGameIds: Flow<Set<String>> = context.alertsDataStore.data.map { it[BELLED_GAME_IDS_KEY] ?: emptySet() }

    /** The offline tipoff/name snapshots behind [belledGameIds] - same set of games, richer shape, for the starting-soon alarms. */
    val belledSnapshots: Flow<List<BelledGameSnapshot>> = context.alertsDataStore.data.map { prefs ->
        prefs[BELLED_SNAPSHOTS_KEY]?.let { raw ->
            runCatching { json.decodeFromString<List<BelledGameSnapshot>>(raw) }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    suspend fun setBelled(snapshot: BelledGameSnapshot, belled: Boolean) {
        context.alertsDataStore.edit { prefs ->
            val currentIds = prefs[BELLED_GAME_IDS_KEY] ?: emptySet()
            prefs[BELLED_GAME_IDS_KEY] = if (belled) currentIds + snapshot.eventId else currentIds - snapshot.eventId
            val currentSnapshots = prefs[BELLED_SNAPSHOTS_KEY]?.let { raw ->
                runCatching { json.decodeFromString<List<BelledGameSnapshot>>(raw) }.getOrDefault(emptyList())
            } ?: emptyList()
            val updated = currentSnapshots.filterNot { it.eventId == snapshot.eventId } + if (belled) listOf(snapshot) else emptyList()
            prefs[BELLED_SNAPSHOTS_KEY] = json.encodeToString(updated)
        }
    }

    /** Bulk bell reset (ended games) - StartingSoonScheduler's prune path. */
    suspend fun removeBelledEventIds(eventIds: Set<String>) {
        if (eventIds.isEmpty()) return
        context.alertsDataStore.edit { prefs ->
            prefs[BELLED_GAME_IDS_KEY] = (prefs[BELLED_GAME_IDS_KEY] ?: emptySet()) - eventIds
            val currentSnapshots = prefs[BELLED_SNAPSHOTS_KEY]?.let { raw ->
                runCatching { json.decodeFromString<List<BelledGameSnapshot>>(raw) }.getOrDefault(emptyList())
            } ?: emptyList()
            prefs[BELLED_SNAPSHOTS_KEY] = json.encodeToString(currentSnapshots.filterNot { it.eventId in eventIds })
        }
    }

    /**
     * Which eventIds currently have a live AlarmManager alarm - the
     * scheduler's own bookkeeping so a refresh can cancel exactly the stale
     * ones without holding alarm handles across process death.
     */
    val scheduledAlarmIds: Flow<Set<String>> = context.alertsDataStore.data.map { it[SCHEDULED_ALARM_IDS_KEY] ?: emptySet() }

    suspend fun setScheduledAlarmIds(eventIds: Set<String>) {
        context.alertsDataStore.edit { it[SCHEDULED_ALARM_IDS_KEY] = eventIds }
    }

    val settings: Flow<AlertsSettings> = context.alertsDataStore.data.map { prefs ->
        AlertsSettings(
            closeSwingEnabled = prefs[CLOSE_SWING_ENABLED_KEY] ?: true,
            startingSoonEnabled = prefs[STARTING_SOON_ENABLED_KEY] ?: true,
            leadTimeMinutes = prefs[LEAD_TIME_MINUTES_KEY] ?: 15,
            delivery = AlertDelivery.entries.find { it.apiValue == prefs[DELIVERY_KEY] } ?: AlertDelivery.BOTH,
            favoritesOnly = prefs[FAVORITES_ONLY_KEY] ?: true,
            tierThreshold = AlertTierThreshold.entries.find { it.apiValue == prefs[TIER_THRESHOLD_KEY] }
        )
    }

    suspend fun setCloseSwingEnabled(value: Boolean) {
        context.alertsDataStore.edit { it[CLOSE_SWING_ENABLED_KEY] = value }
    }

    suspend fun setStartingSoonEnabled(value: Boolean) {
        context.alertsDataStore.edit { it[STARTING_SOON_ENABLED_KEY] = value }
    }

    suspend fun setLeadTimeMinutes(value: Int) {
        context.alertsDataStore.edit { it[LEAD_TIME_MINUTES_KEY] = value }
    }

    suspend fun setDelivery(value: AlertDelivery) {
        context.alertsDataStore.edit { it[DELIVERY_KEY] = value.apiValue }
    }

    suspend fun setFavoritesOnly(value: Boolean) {
        context.alertsDataStore.edit { it[FAVORITES_ONLY_KEY] = value }
    }

    /** Null clears the threshold (stored by simply removing the key). */
    suspend fun setTierThreshold(value: AlertTierThreshold?) {
        context.alertsDataStore.edit { prefs ->
            if (value == null) prefs.remove(TIER_THRESHOLD_KEY) else prefs[TIER_THRESHOLD_KEY] = value.apiValue
        }
    }
}
