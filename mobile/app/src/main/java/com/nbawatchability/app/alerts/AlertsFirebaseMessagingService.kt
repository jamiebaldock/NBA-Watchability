package com.nbawatchability.app.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.nbawatchability.app.MainActivity
import com.nbawatchability.app.R
import com.nbawatchability.app.data.AlertDelivery
import com.nbawatchability.app.data.AlertsNetworkRepository
import com.nbawatchability.app.data.AlertsRepository
import com.nbawatchability.app.data.AppSettingsRepository
import com.nbawatchability.app.data.BACKEND_BASE_URL
import com.nbawatchability.app.data.FavoritesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val CHANNEL_ID = "alerts"
private const val NOTIFICATION_ID = 1001

/**
 * Receives Alerts pushes (fcm.ts sends data-only messages, so there's no
 * system-drawn notification unless this builds one itself) and re-registers
 * this device whenever FCM rotates its token. The backend (alertsPoller.ts,
 * phase 4) sends every alert as the same data-only push regardless of the
 * device's delivery preference - it's this client's job to decide whether
 * that becomes a system notification or something quieter, per fcm.ts's
 * design ("client decides notification vs in-app banner from its own
 * delivery pref"). There's no in-app banner surface built yet, so IN_APP
 * currently means "received, but nothing shown" rather than a real banner -
 * a known gap, not a bug.
 */
class AlertsFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val appContext = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val alertsRepository = AlertsRepository(appContext)
            alertsRepository.setFcmToken(token)
            val deviceId = alertsRepository.getOrCreateDeviceId()
            val favorites = FavoritesRepository(appContext).favoriteTeams.first()
            val enabledLeagues = AppSettingsRepository(appContext).settings.first().enabledLeagues.map { it.apiValue }
            val alertSettings = alertsRepository.settings.first()
            runCatching {
                AlertsNetworkRepository.registerDevice(
                    baseUrl = BACKEND_BASE_URL,
                    deviceId = deviceId,
                    fcmToken = token,
                    leagues = enabledLeagues,
                    favorites = favorites,
                    closeSwingEnabled = alertSettings.closeSwingEnabled,
                    delivery = alertSettings.delivery,
                    favoritesOnly = alertSettings.favoritesOnly,
                    tierThreshold = alertSettings.tierThreshold
                )
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        // Data-only payload by design (fcm.ts) - title/body are this
        // service's own contract with whatever phase 4 eventually sends,
        // not FCM's built-in notification block.
        val title = message.data["title"] ?: "Big4 Watchability"
        val body = message.data["body"] ?: return
        val appContext = applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            val delivery = AlertsRepository(appContext).settings.first().delivery
            if (delivery == AlertDelivery.IN_APP) return@launch // no in-app banner surface yet - see class doc comment

            ensureChannel()

            val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setContentIntent(
                    android.app.PendingIntent.getActivity(
                        appContext,
                        0,
                        android.content.Intent(appContext, MainActivity::class.java),
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .build()

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ActivityCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Game alerts", NotificationManager.IMPORTANCE_HIGH)
        )
    }
}
