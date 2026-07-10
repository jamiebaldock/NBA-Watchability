package com.nbawatchability.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.nbawatchability.app.ui.AppRoot
import com.nbawatchability.app.ui.theme.NbaWatchabilityTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NbaWatchabilityTheme {
                AppRoot()
            }
        }
    }
}
