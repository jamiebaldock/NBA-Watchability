package com.nbawatchability.app

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.nbawatchability.app.ui.AppRoot
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
            NbaWatchabilityTheme {
                AppRoot()
            }
        }
    }
}
