package com.nbawatchability.app.ui

import android.view.ViewGroup
import android.webkit.WebView
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
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay

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

// Every callback (onReady/onError/onStateChange) is delivered by the
// library's own internal WebViewClient/WebChromeClient bridge - reading the
// WebView's load state must never replace that client, or the bridge (and
// therefore all playback) breaks. This reflects into private fields
// read-only, purely to log what the WebView is actually doing.
private fun logWebViewState(youTubePlayerView: YouTubePlayerView, label: String) {
    val state = try {
        val legacyField = YouTubePlayerView::class.java.getDeclaredField("legacyTubePlayerView")
        legacyField.isAccessible = true
        val legacyView = legacyField.get(youTubePlayerView)
        val webViewField = legacyView.javaClass.getDeclaredField("webViewYouTubePlayer")
        webViewField.isAccessible = true
        val webView = webViewField.get(legacyView) as WebView
        "progress=${webView.progress} url=${webView.url}"
    } catch (e: Exception) {
        "reflection failed (${e.javaClass.simpleName}: ${e.message})"
    }
    HighlightsDebugLog.log("WebView state [$label]: $state")
}

@Composable
private fun YoutubePlayer(videoId: String) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    var errorMessage by remember(videoId) { mutableStateOf<String?>(null) }
    var playerReady by remember(videoId) { mutableStateOf(false) }
    var playerView by remember(videoId) { mutableStateOf<YouTubePlayerView?>(null) }

    // Playback depends entirely on the WebView loading
    // https://www.youtube.com/iframe_api over the network (see the library's
    // bundled ayp_youtube_player.html asset) - if that request is silently
    // dropped (DNS filtering, an ad-blocker/firewall app) rather than
    // hard-failing, neither onReady nor onError ever fires and the screen
    // stays black forever with zero signal. This polls the WebView's real
    // load state so the debug log shows definitively whether the page ever
    // loaded, and surfaces an actual error to the user instead of an
    // infinite blank screen after a generous timeout.
    LaunchedEffect(videoId, playerView) {
        val view = playerView ?: return@LaunchedEffect
        repeat(7) { attempt ->
            delay(2000)
            if (playerReady || errorMessage != null) return@LaunchedEffect
            logWebViewState(view, "poll #${attempt + 1}")
            HighlightsDebugLog.save(context)
        }
        if (!playerReady && errorMessage == null) {
            HighlightsDebugLog.log("TIMEOUT: no onReady/onError after 14s")
            logWebViewState(view, "timeout")
            HighlightsDebugLog.save(context)
            errorMessage = "Couldn't load the video player after waiting. This usually means the device's network blocked YouTube's player page — check for a DNS filter, ad-blocker, or firewall app, then try again."
        }
    }

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

                    // Must be set before addObserver() below - Lifecycle.addObserver
                    // synchronously replays the current state to a new observer, so
                    // if the screen is already resumed, addObserver would otherwise
                    // immediately trigger automatic initialization (with no listener
                    // attached) before we get a chance to disable it. That silently
                    // steals the init, and our own initialize() call further down
                    // becomes a no-op - explaining "initialize() logged, then nothing"
                    // regardless of WebView version.
                    enableAutomaticInitialization = false
                    lifecycleOwner.lifecycle.addObserver(this)

                    HighlightsDebugLog.log("Calling initialize()")
                    HighlightsDebugLog.save(factoryContext)
                    val options = IFramePlayerOptions.Builder().controls(1).build()
                    initialize(
                        object : AbstractYouTubePlayerListener() {
                            override fun onReady(youTubePlayer: YouTubePlayer) {
                                HighlightsDebugLog.log("onReady called - loading video")
                                HighlightsDebugLog.save(factoryContext)
                                playerReady = true
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
                    playerView = this
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
