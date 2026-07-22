package com.nbawatchability.app.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * AlarmManager alarms don't survive a reboot - this reschedules the
 * starting-soon set as soon as the device is back up. The work request
 * (not a direct refresh call) keeps the boot path fast and lets the
 * network-constrained/retrying worker handle the actual fetch whenever
 * connectivity returns.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        StartingSoonRefreshWorker.enqueueOneShot(context.applicationContext)
        StartingSoonRefreshWorker.enqueuePeriodic(context.applicationContext)
    }
}
