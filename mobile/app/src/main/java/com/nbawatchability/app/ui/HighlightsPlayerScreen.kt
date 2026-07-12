package com.nbawatchability.app.ui

import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nbawatchability.app.ui.theme.TextSecondary
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextPrimary
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

/**
 * Full-screen player for a game's official full-game-highlights video. A
 * hand-rolled WebView+iframe embed hit YouTube's "error 152" on a real
 * device (not just the emulator's outdated WebView), so this uses
 * android-youtube-player - the standard, actively-maintained library for
 * playing YouTube videos in a WebView on Android, which handles the IFrame
 * Player API's origin/postMessage handshake correctly. A dedicated screen
 * (rather than an inline player on the game card) so only one player is
 * ever alive at a time, mounted while this is open and torn down on back -
 * a scrolling list of finished-game cards could otherwise hold many
 * expensive WebView instances at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightsPlayerScreen(videoId: String, onBack: () -> Unit) {
    Scaffold(
        containerColor = BackgroundBase,
        topBar = {
            TopAppBar(
                title = { Text("Highlights", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            YoutubePlayer(videoId = videoId)
        }
    }
}

@Composable
private fun YoutubePlayer(videoId: String) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    var errorMessage by remember(videoId) { mutableStateOf<String?>(null) }

    if (errorMessage != null) {
        CenteredError(errorMessage!!) { errorMessage = null }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { factoryContext ->
                HighlightsDebugLog.start(factoryContext, videoId)
                YouTubePlayerView(factoryContext).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    lifecycleOwner.lifecycle.addObserver(this)

                    // Automatic initialization silently stopped working after a
                    // YouTube IFrame API change - it never calls onReady and
                    // never errors, just leaves the player black forever.
                    // Explicit initialize() + IFramePlayerOptions is the current
                    // reliable path.
                    enableAutomaticInitialization = false
                    HighlightsDebugLog.log("Calling initialize()")
                    HighlightsDebugLog.save(factoryContext)
                    val options = IFramePlayerOptions.Builder().controls(1).build()
                    initialize(
                        object : AbstractYouTubePlayerListener() {
                            override fun onReady(youTubePlayer: YouTubePlayer) {
                                HighlightsDebugLog.log("onReady called - loading video")
                                HighlightsDebugLog.save(factoryContext)
                                youTubePlayer.loadVideo(videoId, 0f)
                            }

                            override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) {
                                HighlightsDebugLog.log("onError: ${error.name}")
                                HighlightsDebugLog.save(factoryContext)
                                errorMessage = "Couldn't play this video (${error.name.lowercase().replace('_', ' ')})."
                            }

                            override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {
                                HighlightsDebugLog.log("onStateChange: ${state.name}")
                                HighlightsDebugLog.save(factoryContext)
                            }
                        },
                        true,
                        options
                    )
                }
            }
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
        ) {
            Text(
                text = "Diagnostic log: Downloads/nba_watchability_highlights_debug.txt",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
