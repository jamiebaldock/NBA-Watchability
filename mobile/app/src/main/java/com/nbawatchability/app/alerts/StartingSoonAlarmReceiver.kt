package com.nbawatchability.app.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nbawatchability.app.MainActivity
import com.nbawatchability.app.R
import com.nbawatchability.app.data.AlertDelivery
import com.nbawatchability.app.data.AlertsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Its own channel, separate from the FCM service's "alerts" one, so the OS
// notification settings give per-type control (mute starting-soon, keep
// close-swing) on top of the in-app toggles.
private const val CHANNEL_ID = "starting_soon"

/**
 * Fires when a StartingSoonScheduler alarm goes off - builds the local
 * "starting soon" notification. Settings are re-checked at fire time (not
 * trusted from schedule time): the user may have toggled starting-soon off
 * or switched delivery to in-app-only since the alarm was set, and a
 * pending cancel from the next refresh pass may simply not have happened
 * yet. Same delivery semantics as AlertsFirebaseMessagingService: IN_APP
 * with no app open means nothing shows (known gap, not a bug).
 */
class StartingSoonAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_AWAY = "away"
        const val EXTRA_HOME = "home"
        const val EXTRA_LEAD_MINUTES = "lead_minutes"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID) ?: return
        val away = intent.getStringExtra(EXTRA_AWAY) ?: return
        val home = intent.getStringExtra(EXTRA_HOME) ?: return
        val leadMinutes = intent.getIntExtra(EXTRA_LEAD_MINUTES, 15)

        // BroadcastReceiver.onReceive can't suspend - goAsync keeps the
        // process alive for the short DataStore read + notify.
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = AlertsRepository(appContext)
                val settings = repo.settings.first()
                // Fired alarms are one-shot - drop the id from the
                // scheduled set so the next refresh doesn't try to cancel
                // an alarm that no longer exists.
                repo.setScheduledAlarmIds(repo.scheduledAlarmIds.first() - eventId)

                if (!settings.startingSoonEnabled) return@launch
                if (settings.delivery == AlertDelivery.IN_APP) return@launch

                ensureChannel(appContext)
                val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Starting soon")
                    .setContentText("$away @ $home starts in about $leadMinutes min")
                    .setAutoCancel(true)
                    .setContentIntent(
                        PendingIntent.getActivity(
                            appContext,
                            0,
                            Intent(appContext, MainActivity::class.java),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                    .build()

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ActivityCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                ) {
                    // Per-game notification id: two games starting in the
                    // same window each get their own notification instead
                    // of the second replacing the first.
                    NotificationManagerCompat.from(appContext).notify(eventId.hashCode(), notification)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Game starting soon", NotificationManager.IMPORTANCE_HIGH)
        )
    }
}
