package com.nbawatchability.app.ui

import android.content.Context
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
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
import androidx.core.view.doOnNextLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
// read-only, purely to locate the WebView so we can log/query it.
private fun getInternalWebView(youTubePlayerView: YouTubePlayerView): WebView? = try {
    val legacyField = YouTubePlayerView::class.java.getDeclaredField("legacyTubePlayerView")
    legacyField.isAccessible = true
    val legacyView = legacyField.get(youTubePlayerView)
    val webViewField = legacyView.javaClass.getDeclaredField("webViewYouTubePlayer")
    webViewField.isAccessible = true
    webViewField.get(legacyView) as WebView
} catch (e: Exception) {
    HighlightsDebugLog.log("getInternalWebView failed (${e.javaClass.simpleName}: ${e.message})")
    null
}

private fun logWebViewState(youTubePlayerView: YouTubePlayerView, label: String) {
    val webView = getInternalWebView(youTubePlayerView)
    val state = if (webView != null) "progress=${webView.progress} url=${webView.url}" else "WebView unavailable"
    HighlightsDebugLog.log("WebView state [$label]: $state")
}

private const val DIAGNOSTICS_JS_INTERFACE = "AndroidDiagnostics"

// A second, independently-named JS interface (the library's own is
// "YouTubePlayerBridge") - Android allows multiple named interfaces on one
// WebView with no conflict, so this is purely additive.
private class WebViewDiagnosticsBridge(private val context: Context) {
    @JavascriptInterface
    fun log(message: String) {
        HighlightsDebugLog.log("JS: $message")
        HighlightsDebugLog.save(context)
    }
}

/**
 * One-time Android-side setup: registers a second, independently-named JS
 * interface (safe - doesn't touch the library's own WebViewClient/
 * WebChromeClient, which would risk breaking the onReady/onError bridge)
 * and logs WebView settings that could plausibly affect YouTube embed
 * behavior. addJavascriptInterface is a WebView-level binding, not a page
 * script, so unlike evaluateJavascript it's safe to call before any page
 * has loaded.
 */
private fun injectDiagnostics(youTubePlayerView: YouTubePlayerView, context: Context) {
    val webView = getInternalWebView(youTubePlayerView) ?: return
    HighlightsDebugLog.log(
        "WebView settings: UA=${webView.settings.userAgentString} " +
            "thirdPartyCookies=${CookieManager.getInstance().acceptThirdPartyCookies(webView)} " +
            "cookiesForYoutube=${CookieManager.getInstance().getCookie("https://www.youtube.com") != null}"
    )
    HighlightsDebugLog.save(context)
    webView.addJavascriptInterface(WebViewDiagnosticsBridge(context), DIAGNOSTICS_JS_INTERFACE)
}

/**
 * Re-run every poll tick rather than once - evaluateJavascript needs an
 * actual document loaded to do anything, and calling it immediately after
 * initialize() (before the page has loaded) can silently no-op. The
 * console/error-listener setup is guarded by a page-global flag so it only
 * actually attaches once it succeeds, however many times this is called.
 * Beyond typeof YT (confirms the IFrame API script itself loaded/ran),
 * this checks whether the actual video embed iframe - a separate network
 * request YT.Player() makes internally - ever got inserted into the DOM,
 * since that's a more likely failure point once the outer API is confirmed
 * working.
 *
 * [attemptRecovery] tests a specific hypothesis: that YouTube's iframe_api
 * script defines the YT global but, for whatever reason, never actually
 * invokes the pre-existing window.onYouTubeIframeAPIReady callback it's
 * documented to auto-call. If YT is defined, player is still undefined, and
 * the callback function exists, this manually invokes it once. If that
 * makes the iframe appear (and onReady subsequently fires via the library's
 * real bridge), the auto-invocation itself is the confirmed root cause and
 * this recovery step is a genuine fix, not just a diagnostic.
 */
private fun queryPageState(youTubePlayerView: YouTubePlayerView, label: String, attemptRecovery: Boolean) {
    val webView = getInternalWebView(youTubePlayerView) ?: return
    webView.evaluateJavascript(
        """
        (function() {
          if (!window.__nbaDiagnosticsAttached) {
            try {
              function relay(level, args) {
                try {
                  var msg = Array.prototype.slice.call(args).map(function(a) {
                    try { return typeof a === 'object' ? JSON.stringify(a) : String(a); } catch (e) { return String(a); }
                  }).join(' ');
                  $DIAGNOSTICS_JS_INTERFACE.log('console.' + level + ': ' + msg);
                } catch (e) {}
              }
              ['log', 'warn', 'error', 'info'].forEach(function(level) {
                var orig = console[level];
                console[level] = function() {
                  relay(level, arguments);
                  if (orig) orig.apply(console, arguments);
                };
              });
              window.addEventListener('error', function(e) {
                var target = e.target || e.srcElement;
                if (target && target.tagName === 'SCRIPT') {
                  $DIAGNOSTICS_JS_INTERFACE.log('SCRIPT LOAD ERROR: ' + (target.src || 'unknown src'));
                } else {
                  var stack = (e.error && e.error.stack) ? (' stack=' + e.error.stack) : ' (no stack available)';
                  $DIAGNOSTICS_JS_INTERFACE.log('JS ERROR: ' + e.message + ' at ' + e.filename + ':' + e.lineno + stack);
                }
              }, true);
              // Tests whether something is triggering YouTube's own
              // auto-pause-on-hidden logic (which would explain pauseVideo()
              // being called before a player even exists) versus a genuine
              // Android Activity lifecycle event - these fire purely from the
              // WebView/page's own visibility state, independent of whatever
              // the Android-side lifecycle observer sees.
              ['visibilitychange', 'pagehide', 'pageshow', 'blur', 'focus'].forEach(function(evt) {
                var target = (evt === 'blur' || evt === 'focus') ? window : document;
                target.addEventListener(evt, function() {
                  $DIAGNOSTICS_JS_INTERFACE.log('PAGE EVENT: ' + evt + ' (visibilityState=' + document.visibilityState + ' hasFocus=' + document.hasFocus() + ')');
                }, true);
              });
              // Confirmed via decompiling the library that it never calls
              // Android's WebView.onResume()/onPause() (only manages its own
              // internal canPlay flag) - Chromium's Page Visibility API can
              // be left reporting hidden/unfocused even though the Activity
              // is fully resumed and the WebView is correctly laid out,
              // which would make YouTube's own script defer/skip the embed
              // handshake (many sites, including YouTube, intentionally
              // throttle video setup on a page that thinks it's hidden).
              // Overriding the getters (not just dispatching one event)
              // means any later re-check also sees visible/focused, not
              // just the very first one.
              try {
                Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
                document.hasFocus = function() { return true; };
                document.dispatchEvent(new Event('visibilitychange'));
                window.dispatchEvent(new Event('focus'));
                $DIAGNOSTICS_JS_INTERFACE.log(
                  'forced visibility+focus. now visibilityState=' + document.visibilityState +
                  ' hidden=' + document.hidden + ' hasFocus=' + document.hasFocus()
                );
              } catch (e) {
                $DIAGNOSTICS_JS_INTERFACE.log('forcing visibility failed: ' + e);
              }
              // Wraps the page's own "JAVA to WEB" pauseVideo() function (the
              // one Android calls via evaluateJavascript to pause on
              // lifecycle events) so every invocation logs a real JS stack
              // trace - this shows definitively whether it's called fresh
              // (stack bottoms out immediately, meaning an external
              // evaluateJavascript("pauseVideo()") call) or from inside
              // another JS function (e.g. a visibilitychange handler
              // YouTube's own script installed), which the error listener
              // above can't distinguish on its own.
              if (typeof window.pauseVideo === 'function') {
                var origPauseVideo = window.pauseVideo;
                window.pauseVideo = function() {
                  $DIAGNOSTICS_JS_INTERFACE.log('pauseVideo() invoked. typeof player=' + (typeof player) + ' stack=' + (new Error().stack));
                  return origPauseVideo.apply(this, arguments);
                };
              }
              window.__nbaDiagnosticsAttached = true;
              $DIAGNOSTICS_JS_INTERFACE.log('diagnostics attached. location=' + location.href + ' origin=' + location.origin);
            } catch (e) {
              $DIAGNOSTICS_JS_INTERFACE.log('diagnostics attach failed: ' + e);
            }
            // YouTube's IFrame API is known to silently stall if the target
            // element has zero width/height when YT.Player() constructs its
            // embed iframe - the target div here has no CSS sizing at all
            // (height:100% only applies to html/body, it doesn't cascade to
            // nested block elements), so it likely collapses to 0 height.
            // Forcing explicit pixel dimensions before any YT.Player() call
            // (auto-fired or our own manual recovery below) rules this in or
            // out directly, and fixes it if this is in fact the cause.
            try {
              var target = document.getElementById('youTubePlayerDOM');
              if (target && target.tagName !== 'IFRAME') {
                $DIAGNOSTICS_JS_INTERFACE.log(
                  'pre-fix container size: offsetWidth=' + target.offsetWidth + ' offsetHeight=' + target.offsetHeight +
                  ' window=' + window.innerWidth + 'x' + window.innerHeight
                );
                target.style.width = window.innerWidth + 'px';
                target.style.height = window.innerHeight + 'px';
                target.style.position = 'absolute';
                target.style.top = '0';
                target.style.left = '0';
                $DIAGNOSTICS_JS_INTERFACE.log('post-fix container size: ' + target.style.width + ' x ' + target.style.height);
              }
            } catch (e) {
              $DIAGNOSTICS_JS_INTERFACE.log('container sizing fix failed: ' + e);
            }
          }
          // YT.Player() replaces the target element outright rather than
          // inserting a child into it, so check the element's own tag too -
          // if it's already an IFRAME, .children.length alone would miss that.
          var dom = document.getElementById('youTubePlayerDOM');
          var domInfo = 'youTubePlayerDOM missing';
          if (dom) {
            domInfo = 'tag=' + dom.tagName + ' children=' + dom.children.length +
              ' offsetSize=' + dom.offsetWidth + 'x' + dom.offsetHeight;
            if (dom.tagName === 'IFRAME') domInfo += ' src=' + (dom.src || 'none');
            else if (dom.children.length > 0) domInfo += ' firstChildTag=' + dom.children[0].tagName + ' firstChildSrc=' + (dom.children[0].src || 'none');
          }
          $DIAGNOSTICS_JS_INTERFACE.log(
            '[$label] typeof YT=' + (typeof YT) +
            ' typeof player=' + (typeof player) +
            ' onLine=' + navigator.onLine + ' ' + domInfo
          );
          if (
            $attemptRecovery &&
            !window.__nbaManualRetryDone &&
            typeof YT === 'object' &&
            typeof player === 'undefined' &&
            typeof onYouTubeIframeAPIReady === 'function'
          ) {
            window.__nbaManualRetryDone = true;
            $DIAGNOSTICS_JS_INTERFACE.log('[$label] YT loaded but onYouTubeIframeAPIReady never auto-fired - manually invoking as recovery attempt');
            try {
              onYouTubeIframeAPIReady();
              $DIAGNOSTICS_JS_INTERFACE.log('[$label] manual onYouTubeIframeAPIReady() call completed without throwing');
            } catch (e) {
              $DIAGNOSTICS_JS_INTERFACE.log('[$label] manual onYouTubeIframeAPIReady() THREW: ' + e);
            }
          }
        })();
        """.trimIndent(),
        null
    )
}

@Composable
private fun YoutubePlayer(videoId: String) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    var errorMessage by remember(videoId) { mutableStateOf<String?>(null) }
    var playerReady by remember(videoId) { mutableStateOf(false) }
    var playerView by remember(videoId) { mutableStateOf<YouTubePlayerView?>(null) }
    var lifecycleObserver by remember(videoId) { mutableStateOf<LifecycleEventObserver?>(null) }

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
        injectDiagnostics(view, context)
        queryPageState(view, "immediate", attemptRecovery = false)
        // 10 polls (~20s) rather than 7 (~14s) - confirmed via the manual
        // recovery attempt that onYouTubeIframeAPIReady often needs to be
        // triggered by us around poll #2, which only leaves the *embed
        // iframe itself* (a further network load) the remaining window to
        // complete; the old 14s total left too little runway after that.
        repeat(10) { attempt ->
            delay(2000)
            if (playerReady || errorMessage != null) return@LaunchedEffect
            logWebViewState(view, "poll #${attempt + 1}")
            // One grace poll (attempt 0, ~2s) before trying the manual
            // recovery, in case the callback is just slow rather than never
            // firing at all.
            queryPageState(view, "poll #${attempt + 1}", attemptRecovery = attempt >= 1)
            HighlightsDebugLog.save(context)
        }
        if (!playerReady && errorMessage == null) {
            HighlightsDebugLog.log("TIMEOUT: no onReady/onError after 20s")
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
                    HighlightsDebugLog.log("View created, pre-layout size: ${width}x$height")

                    // Must be set before addObserver() below - Lifecycle.addObserver
                    // synchronously replays the current state to a new observer, so
                    // if the screen is already resumed, addObserver would otherwise
                    // immediately trigger automatic initialization (with no listener
                    // attached) before we get a chance to disable it. That silently
                    // steals the init, and our own initialize() call further down
                    // becomes a no-op - explaining "initialize() logged, then nothing"
                    // regardless of WebView version.
                    enableAutomaticInitialization = false

                    // Wrapped rather than registering `this` directly, so every
                    // real Activity lifecycle event gets logged before being
                    // forwarded to the library's own handling (onStateChanged
                    // is public, so this changes nothing about its behavior) -
                    // this is how we tell a genuine Android-level pause (screen
                    // lock, backgrounding) apart from something purely inside
                    // the WebView/JS layer triggering YouTube's own
                    // auto-pause-on-hidden logic. Also explicitly calls the
                    // WebView's own onResume()/onPause() here, since decompiling
                    // the library confirmed it never does - those Android APIs
                    // are what actually tell Chromium's engine the page is
                    // visible/foregrounded, separate from the Activity lifecycle
                    // reaching the View through normal attachment callbacks.
                    val observer = LifecycleEventObserver { source, event ->
                        HighlightsDebugLog.log("Lifecycle event: $event")
                        HighlightsDebugLog.save(factoryContext)
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
                    // measure/layout, so it's 0x0. YouTube's IFrame API is
                    // known to silently stall if the target element is 0x0 when
                    // YT.Player() constructs its embed iframe - loading the page
                    // (which happens inside initialize()) before the WebView has
                    // real dimensions risks baking that 0x0 size into the page's
                    // own initial viewport.
                    doOnNextLayout {
                        HighlightsDebugLog.log("Post-layout size: ${it.width}x${it.height} - calling initialize()")
                        HighlightsDebugLog.save(factoryContext)
                        val options = IFramePlayerOptions.Builder().controls(1).build()
                        // Root cause, confirmed via decompiling the library:
                        // the 3-arg initialize(listener, handleNetworkEvents,
                        // options) overload passes a hardcoded null as the
                        // initial videoId (javap showed an explicit aconst_null
                        // pushed as the 4th arg to the internal call it
                        // delegates to). The HTML template then substitutes the
                        // literal unquoted token `undefined` for a null videoId
                        // (also confirmed via decompile), producing
                        // `new YT.Player('youTubePlayerDOM', { videoId:
                        // undefined, ... })` - which is exactly what a
                        // real-device log's captured stack trace showed
                        // throwing "Invalid video id" synchronously inside
                        // YouTube's own constructor, every single time,
                        // regardless of video/device/WebView version. That
                        // exception fires before `player = new YT.Player(...)`
                        // ever completes, so player stays undefined and
                        // onReady never fires via the real, automatic
                        // invocation - explaining every symptom this
                        // investigation chased. The 4-arg overload passes the
                        // real videoId through to that same template slot, so
                        // the player never throws in the first place - loading
                        // the video separately via loadVideo() in onReady is
                        // no longer necessary once it's already cued here.
                        initialize(
                            object : AbstractYouTubePlayerListener() {
                                override fun onReady(youTubePlayer: YouTubePlayer) {
                                    HighlightsDebugLog.log("onReady called")
                                    HighlightsDebugLog.save(factoryContext)
                                    playerReady = true
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
            // registered on the Activity's lifecycle even after Compose
            // disposes this View (e.g. once the timeout sets errorMessage and
            // this leaves composition) - a real, separate bug found via a
            // stray "player.pauseVideo is not a function" crash several
            // seconds after a timeout, coming from the orphaned instance
            // reacting to a later lifecycle event with a still-broken player.
            onRelease = { view ->
                lifecycleObserver?.let { lifecycleOwner.lifecycle.removeObserver(it) }
                view.release()
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
