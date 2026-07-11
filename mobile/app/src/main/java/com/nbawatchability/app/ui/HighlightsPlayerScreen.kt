package com.nbawatchability.app.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextPrimary

/**
 * Full-screen player for a game's official NBA full-game-highlights video.
 * A WebView loading YouTube's iframe embed needs no API key - only the
 * server-side search that matched the video ID (youtubeClient.ts) does. A
 * dedicated screen (rather than an inline WebView on the game card) so only
 * one WebView is ever alive at a time, mounted while this is open and torn
 * down on back - a scrolling list of finished-game cards could otherwise
 * hold many expensive WebView instances at once.
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
            YoutubeEmbedWebView(videoId = videoId)
        }
    }
}

// Loading the embed URL directly as the WebView's top-level page trips
// YouTube's "Error 153: Video player configuration error" - the embed
// player expects to run inside an iframe with a real origin, not as a
// top-level navigation. Wrapping it in a minimal HTML page (loaded via
// loadDataWithBaseURL so the iframe's origin resolves to youtube.com) is
// the standard fix.
private fun embedHtml(videoId: String): String = """
    <html><body style="margin:0;padding:0;background:#000;">
    <iframe width="100%" height="100%" style="position:fixed;top:0;left:0;"
        src="https://www.youtube.com/embed/$videoId?autoplay=1&playsinline=1"
        frameborder="0" allow="autoplay; encrypted-media" allowfullscreen></iframe>
    </body></html>
""".trimIndent()

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YoutubeEmbedWebView(videoId: String) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                // YouTube's embed player often refuses to play (error 152) for
                // requests whose user agent identifies as an Android WebView
                // (the "; wv)" marker Android appends automatically) - swapping
                // in a plain Chrome mobile user agent avoids that block.
                settings.userAgentString = settings.userAgentString.replace("; wv", "")
                // YouTube's embed player needs its own cookies to validate
                // playback; WebView blocks third-party cookies by default.
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = WebViewClient()
                loadDataWithBaseURL("https://www.youtube.com", embedHtml(videoId), "text/html", "utf-8", null)
            }
        }
    )
}
