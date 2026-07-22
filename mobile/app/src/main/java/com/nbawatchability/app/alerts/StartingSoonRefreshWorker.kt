package com.nbawatchability.app.alerts

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Runs StartingSoonScheduler.refresh off the UI - both on a 12-hourly cadence
 * (alarms only cover a 48h horizon, so the periodic pass keeps rolling new
 * games into it with margin to spare) and as a one-shot whenever something
 * that changes the correct alarm set happens right now (app start, boot,
 * bell toggle, favorites change, lead-time/toggle change). Network-gated:
 * the favorites half of a refresh needs /team-schedule, and a failed pass
 * retries with backoff rather than silently leaving stale alarms.
 */
class StartingSoonRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        runCatching { StartingSoonScheduler.refresh(applicationContext) }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })

    companion object {
        private val NETWORK = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** KEEP policy - calling this on every app start never resets the 12h cadence, just ensures it exists. */
        fun enqueuePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "starting_soon_refresh",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<StartingSoonRefreshWorker>(12, TimeUnit.HOURS)
                    .setConstraints(NETWORK)
                    .build()
            )
        }

        /** REPLACE policy - rapid-fire triggers (toggling several bells in a row) collapse to one refresh. */
        fun enqueueOneShot(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "starting_soon_refresh_once",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<StartingSoonRefreshWorker>()
                    .setConstraints(NETWORK)
                    .build()
            )
        }
    }
}
