package com.nbawatchability.app.alerts

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.nbawatchability.app.data.AlertsNetworkRepository
import com.nbawatchability.app.data.AlertsRepository
import com.nbawatchability.app.data.BACKEND_BASE_URL
import com.nbawatchability.app.data.BelledGameSnapshot
import com.nbawatchability.app.data.FavoritesRepository
import com.nbawatchability.app.data.GameStatus
import com.nbawatchability.app.data.LeagueGroup
import com.nbawatchability.app.data.NetworkGameRepository
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.OffsetDateTime

// Alarms are only ever set for games tipping off inside this window - the
// 12-hourly refresh (StartingSoonRefreshWorker) re-runs well before the
// window's far edge, so a game further out just gets its alarm on a later
// pass. Keeps the scheduled-alarm count small (a night or two of games, not
// a whole season) without any user-visible difference.
private const val SCHEDULE_HORIZON_HOURS = 48L

// A belled game whose tipoff is this far past is over by any sport's
// standards - the bell auto-resets (spec: "once the game has ended and its
// notifications have been sent, remove/reset the bell").
private const val BELL_EXPIRY_HOURS = 6L

/**
 * The starting-soon alert pipeline's brain - fully local/client-side (alert
 * type (a) of the spec), no push infra involved: game times are already
 * known once fetched, so this just keeps one exact alarm per upcoming
 * favorites/belled game at (tipoff - lead time).
 *
 * [refresh] is the single idempotent entry point, re-run from every place
 * the correct alarm set could change: the 12-hourly worker, app start, boot
 * (alarms don't survive reboot), bell toggles, favorites changes, and the
 * starting-soon settings themselves. Each run recomputes the full target
 * set from scratch, cancels anything stale, and (re)sets the rest -
 * FLAG_UPDATE_CURRENT + a per-game requestCode makes re-setting an existing
 * alarm a replace, so there's no double-fire path.
 */
object StartingSoonScheduler {

    suspend fun refresh(context: Context) {
        val repo = AlertsRepository(context)
        val settings = repo.settings.first()
        val previouslyScheduled = repo.scheduledAlarmIds.first()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Prune ended belled games first, regardless of enabled state - the
        // bell's auto-reset shouldn't wait for alerts to be switched back on.
        val snapshots = repo.belledSnapshots.first()
        val expiryCutoff = Instant.now().minusSeconds(BELL_EXPIRY_HOURS * 3600)
        val (endedBells, liveBells) = snapshots.partition { tipoffInstant(it.tipoffUtc)?.isBefore(expiryCutoff) ?: true }
        if (endedBells.isNotEmpty()) {
            val endedIds = endedBells.map { it.eventId }.toSet()
            repo.removeBelledEventIds(endedIds)
            // Server-side hygiene, best-effort: the poller ignores ended
            // games anyway, this just keeps alert_game_subs from growing
            // forever. Failure is fine (offline, cold start) - rows for
            // ended games are inert.
            val deviceId = repo.getOrCreateDeviceId()
            for (id in endedIds) {
                runCatching { AlertsNetworkRepository.setGameSub(BACKEND_BASE_URL, deviceId, id, false) }
            }
        }

        if (!settings.startingSoonEnabled) {
            for (id in previouslyScheduled) alarmManager.cancel(alarmIntent(context, id))
            repo.setScheduledAlarmIds(emptySet())
            return
        }

        val now = Instant.now()
        val horizon = now.plusSeconds(SCHEDULE_HORIZON_HOURS * 3600)
        val leadMillis = settings.leadTimeMinutes * 60_000L

        // Favorites' upcoming games, one /team-schedule call per favorited
        // team - the same per-team fetch shape as the Favorites tab's Games
        // page, resilient to any single team's fetch failing (that team's
        // games just wait for the next refresh).
        val targets = mutableMapOf<String, BelledGameSnapshot>()
        val favorites = FavoritesRepository(context).favoriteTeams.first()
        for (team in favorites) {
            if (team.id.isBlank()) continue
            val leagueGroup = LeagueGroup.entries.find { it.apiValue == team.leagueGroup } ?: continue
            val games = runCatching { NetworkGameRepository.teamSchedule(BACKEND_BASE_URL, team.id, leagueGroup) }
                .getOrDefault(emptyList())
            for (game in games) {
                val eventId = game.eventId ?: continue
                if (game.status != GameStatus.UPCOMING) continue
                val tipoff = tipoffInstant(game.tipoffUtc) ?: continue
                if (tipoff.isAfter(now) && tipoff.isBefore(horizon)) {
                    targets[eventId] = BelledGameSnapshot(eventId, game.away, game.home, game.tipoffUtc)
                }
            }
        }
        // Belled games need no network at all - the snapshot (captured at
        // bell time) already has the tipoff and names.
        for (bell in liveBells) {
            val tipoff = tipoffInstant(bell.tipoffUtc) ?: continue
            if (tipoff.isAfter(now) && tipoff.isBefore(horizon)) targets[bell.eventId] = bell
        }

        for (staleId in previouslyScheduled - targets.keys) {
            alarmManager.cancel(alarmIntent(context, staleId))
        }

        val scheduled = mutableSetOf<String>()
        for ((eventId, game) in targets) {
            val triggerAt = tipoffInstant(game.tipoffUtc)!!.toEpochMilli() - leadMillis
            // A game inside the lead window (e.g. lead 60min, tipoff in 20)
            // fires immediately-ish rather than being skipped - the alarm
            // still carries real information ("starting soon" is literally
            // true), and skipping would silently drop alerts for anyone
            // opening the app shortly before tipoff.
            val effectiveTrigger = maxOf(triggerAt, System.currentTimeMillis() + 5_000)
            val pending = alarmIntent(context, eventId, game, settings.leadTimeMinutes)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, effectiveTrigger, pending)
            } else {
                // Exact-alarm special access not granted (Android 12+) -
                // inexact delivery may drift by a few minutes in Doze, still
                // far better than no alert at all.
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, effectiveTrigger, pending)
            }
            scheduled.add(eventId)
        }
        repo.setScheduledAlarmIds(scheduled)
    }

    private fun tipoffInstant(tipoffUtc: String): Instant? =
        runCatching { OffsetDateTime.parse(tipoffUtc).toInstant() }.getOrNull()

    // One PendingIntent identity per game: requestCode from the eventId, so
    // set-with-same-id replaces and cancel-by-id works without holding any
    // alarm handles across process death.
    private fun alarmIntent(
        context: Context,
        eventId: String,
        game: BelledGameSnapshot? = null,
        leadMinutes: Int = 0
    ): PendingIntent {
        val intent = Intent(context, StartingSoonAlarmReceiver::class.java).apply {
            putExtra(StartingSoonAlarmReceiver.EXTRA_EVENT_ID, eventId)
            if (game != null) {
                putExtra(StartingSoonAlarmReceiver.EXTRA_AWAY, game.away)
                putExtra(StartingSoonAlarmReceiver.EXTRA_HOME, game.home)
                putExtra(StartingSoonAlarmReceiver.EXTRA_LEAD_MINUTES, leadMinutes)
            }
        }
        return PendingIntent.getBroadcast(
            context,
            eventId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
