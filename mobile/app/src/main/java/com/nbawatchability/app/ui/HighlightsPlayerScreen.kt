package com.nbawatchability.app.ui

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnNextLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextPrimary
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import kotlinx.coroutines.delay

/**
 * Full-screen player for a game's official full-game-highlights video. Uses
 * android-youtube-player rather than a hand-rolled WebView+iframe embed,
 * which handles the IFrame Player API's postMessage handshake correctly.
 * Pinned to 13.0.0+: earlier versions hardcode the embed's origin to
 * https://www.youtube.com, which YouTube's embedder-identity check now
 * rejects as self-spoofed (error 152) - 13.x derives the origin from the
 * app's own package name instead. A dedicated screen (rather than an inline
 * player on the game card) so only one player is ever alive at a time,
 * mounted while this is open and torn down on back - a scrolling list of
 * finished-game cards could otherwise hold many expensive WebView instances
 * at once.
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
// therefore all playback) breaks. This reflects into a private field
// read-only, purely so the Activity lifecycle can forward onResume()/
// onPause()/resumeTimers() to it (the library never calls those itself).
private fun getInternalWebView(youTubePlayerView: YouTubePlayerView): WebView? = try {
    val legacyField = YouTubePlayerView::class.java.getDeclaredField("legacyTubePlayerView")
    legacyField.isAccessible = true
    val legacyView = legacyField.get(youTubePlayerView)
    val webViewField = legacyView.javaClass.getDeclaredField("webViewYouTubePlayer")
    webViewField.isAccessible = true
    webViewField.get(legacyView) as WebView
} catch (e: Exception) {
    null
}

// YouTube's IFrame API silently stalls if the target element has zero
// width/height when YT.Player() constructs its embed iframe - the bundled
// page's CSS sets height:100% on the container, which only cascades from an
// ancestor with an explicit (not auto) height, so it can collapse to 0
// despite the WebView itself being correctly sized. Forcing explicit pixel
// dimensions before the iframe is constructed avoids that. Idempotent and
// safe to call on every poll tick until it applies once.
private const val CONTAINER_SIZE_FIX_JS = """
(function() {
  if (window.__nbaContainerSized) return;
  var target = document.getElementById('youTubePlayerDOM');
  if (target && target.tagName !== 'IFRAME') {
    target.style.width = window.innerWidth + 'px';
    target.style.height = window.innerHeight + 'px';
    target.style.position = 'absolute';
    target.style.top = '0';
    target.style.left = '0';
    window.__nbaContainerSized = true;
  }
})();
"""

@Composable
private fun YoutubePlayer(videoId: String) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var errorMessage by remember(videoId) { mutableStateOf<String?>(null) }
    var playerReady by remember(videoId) { mutableStateOf(false) }
    var playerView by remember(videoId) { mutableStateOf<YouTubePlayerView?>(null) }
    var lifecycleObserver by remember(videoId) { mutableStateOf<LifecycleEventObserver?>(null) }

    // Playback depends on the WebView loading https://www.youtube.com/iframe_api
    // over the network - if that request is silently dropped (DNS filtering,
    // an ad-blocker/firewall app) rather than hard-failing, neither onReady
    // nor onError ever fires and the screen would stay black forever with no
    // feedback. This applies the container-sizing fix on a short poll and
    // surfaces an actual error to the user after a generous timeout instead.
    LaunchedEffect(videoId, playerView) {
        val view = playerView ?: return@LaunchedEffect
        repeat(10) {
            delay(2000)
            if (playerReady || errorMessage != null) return@LaunchedEffect
            getInternalWebView(view)?.evaluateJavascript(CONTAINER_SIZE_FIX_JS, null)
        }
        if (!playerReady && errorMessage == null) {
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
                YouTubePlayerView(factoryContext).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

                    // Must be set before addObserver() below - Lifecycle.addObserver
                    // synchronously replays the current state to a new observer, so
                    // if the screen is already resumed, addObserver would otherwise
                    // immediately trigger automatic initialization (with no listener
                    // attached) before we get a chance to disable it, silently
                    // stealing the init and turning our own initialize() call below
                    // into a no-op.
                    enableAutomaticInitialization = false

                    // Forwards real Activity lifecycle events to the library's own
                    // handling (onStateChanged is public, so this changes nothing
                    // about its behavior), and also explicitly calls the WebView's
                    // own onResume()/onPause()/resumeTimers(), since the library
                    // never does - those Android APIs are what actually tell
                    // Chromium's engine the page is visible/foregrounded, separate
                    // from the Activity lifecycle reaching the View through normal
                    // attachment callbacks.
                    val observer = LifecycleEventObserver { source, event ->
                        onStateChanged(source, event)
                        getInternalWebView(this)?.let { wv ->
                            when (event) {
                                Lifecycle.Event.ON_RESUME -> {
                                    wv.onResume()
                                    wv.resumeTimers()
                                }
                                Lifecycle.Event.ON_PAUSE -> wv.onPause()
                                else -> {}
                            }
                        }
                    }
                    lifecycleObserver = observer
                    lifecycleOwner.lifecycle.addObserver(observer)

                    // Deferred to the next layout pass rather than called
                    // synchronously here: at this point the View has just been
                    // constructed and has never been through Android's
                    // measure/layout, so it's 0x0, and YouTube's IFrame API stalls
                    // if the target element is 0x0 when YT.Player() constructs its
                    // embed iframe.
                    doOnNextLayout {
                        val options = IFramePlayerOptions.Builder(factoryContext).controls(1).build()
                        // videoId is passed directly into initialize() (the 4-arg
                        // overload) rather than via a separate loadVideo() call in
                        // onReady - the 3-arg overload hardcodes a null videoId,
                        // which the library's HTML template then substitutes as the
                        // unquoted literal `undefined`, making
                        // `new YT.Player('youTubePlayerDOM', { videoId: undefined })`
                        // throw "Invalid video id" synchronously and silently break
                        // every playback attempt.
                        initialize(
                            object : AbstractYouTubePlayerListener() {
                                override fun onReady(youTubePlayer: YouTubePlayer) {
                                    playerReady = true
                                }

                                override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) {
                                    errorMessage = "Couldn't play this video (${error.name.lowercase().replace('_', ' ')})."
                                }

                                override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {}
                            },
                            true,
                            options,
                            videoId
                        )
                        // The WebView is only created inside initialize() (via
                        // initWebView), so if ON_RESUME already fired via
                        // addObserver()'s synchronous replay above, the observer
                        // had no WebView yet to call onResume() on - covers that
                        // case explicitly, right as soon as one exists.
                        getInternalWebView(this)?.let { wv ->
                            wv.onResume()
                            wv.resumeTimers()
                        }
                        playerView = this
                    }
                }
            },
            // Without this, the wrapped lifecycle observer (added above) stays
            // registered on the Activity's lifecycle even after Compose disposes
            // this View (e.g. once the timeout sets errorMessage and this leaves
            // composition), leaving an orphaned instance reacting to later
            // lifecycle events with an already-torn-down player.
            onRelease = { view ->
                lifecycleObserver?.let { lifecycleOwner.lifecycle.removeObserver(it) }
                view.release()
            }
        )
    }
}
