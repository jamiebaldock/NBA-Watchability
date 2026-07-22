package com.nbawatchability.app

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nbawatchability.app.alerts.StartingSoonRefreshWorker
import com.nbawatchability.app.ui.AppRoot
import com.nbawatchability.app.ui.AppSettingsViewModel
import com.nbawatchability.app.ui.theme.NbaWatchabilityTheme

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way - alerts simply stay silent if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Debug builds only (never a release/Play Store build) - lets any
        // WebView in the app, including the highlights player's internal
        // one, be inspected via chrome://inspect over USB.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // Android 13+ (API 33) requires this at runtime before any
        // notification, including Alerts pushes, can actually show.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Starting-soon alarms: make sure the 12-hourly reschedule pass
        // exists (KEEP - never resets an existing cadence) and run one now,
        // so a fresh install/update has alarms without waiting for the
        // first periodic tick.
        StartingSoonRefreshWorker.enqueuePeriodic(this)
        StartingSoonRefreshWorker.enqueueOneShot(this)

        setContent {
            // Same AppSettingsViewModel instance AppRoot() itself later grabs
            // via viewModel() (both scoped to this same Activity) - read here
            // too, specifically so the light-theme choice is known before
            // MaterialTheme picks a color scheme, rather than flashing dark
            // and then repainting once AppRoot's own settings read resolves.
            val appSettingsViewModel: AppSettingsViewModel = viewModel()
            NbaWatchabilityTheme(isLightTheme = appSettingsViewModel.settings.lightTheme) {
                AppRoot()
            }
        }
    }
}
