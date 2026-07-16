package com.nbawatchability.app

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nbawatchability.app.ui.AppRoot
import com.nbawatchability.app.ui.AppSettingsViewModel
import com.nbawatchability.app.ui.theme.NbaWatchabilityTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Debug builds only (never a release/Play Store build) - lets any
        // WebView in the app, including the highlights player's internal
        // one, be inspected via chrome://inspect over USB.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

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
