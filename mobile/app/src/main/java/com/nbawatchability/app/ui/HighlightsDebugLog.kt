package com.nbawatchability.app.ui

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.WebView
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TEMPORARY diagnostic tool for tracking down why highlights playback stays
 * black on a specific real device with no error - the user can't connect via
 * USB for live adb logcat access, so this writes plain-text events straight
 * to Downloads instead, where any file manager can open it and the user can
 * paste the contents back. Remove once the root cause is found.
 */
object HighlightsDebugLog {
    private const val FILE_NAME = "nba_watchability_highlights_debug.txt"
    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lines = mutableListOf<String>()

    // Created once per session and reused for every save() in it - the
    // original code ran a fresh delete-by-name + insert on every single
    // save() call (dozens per session), and MediaStore's delete not always
    // being visible to the very next insert (a real race under how often
    // this logs) meant most saves silently landed in an auto-numbered
    // duplicate ("nba_watchability_highlights_debug (12).txt") instead of
    // overwriting the original - so both we and the user kept reading a
    // stale file without any indication it was stale. Caching the URI and
    // doing a truncating overwrite avoids the repeated delete/insert races
    // entirely.
    private var sessionUri: Uri? = null

    fun start(context: Context, videoId: String) {
        lines.clear()
        sessionUri = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Clears out every leftover duplicate from prior sessions (not
            // just an exact-name match) so there's only ever one debug file
            // present - otherwise a stale duplicate from a previous run can
            // sit there indefinitely looking exactly as plausible as the
            // current one.
            try {
                context.contentResolver.delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                    arrayOf("nba_watchability_highlights_debug%")
                )
            } catch (e: Exception) {
                // Best-effort cleanup only.
            }
        }
        log("=== Highlights debug log started ===")
        log("videoId=$videoId")
        log(deviceInfo())
        save(context)
    }

    fun log(message: String) {
        lines.add("${timestampFormat.format(Date())}  $message")
    }

    /** Call after any log() the user might need to see even if the screen never reaches onReady/onError. */
    fun save(context: Context) {
        val content = lines.joinToString("\n")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val uri = sessionUri ?: run {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.also { sessionUri = it }
                }
                // "wt" truncates before writing, so every call fully replaces
                // the previous content in the same row rather than appending
                // or needing a delete+insert cycle.
                uri?.let { resolver.openOutputStream(it, "wt")?.use { out -> out.write(content.toByteArray()) } }
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                FileOutputStream(File(downloadsDir, FILE_NAME)).use { it.write(content.toByteArray()) }
            }
        } catch (e: Exception) {
            // Nothing more we can do if even the diagnostic log can't be written -
            // this is best-effort and must never crash the actual player screen.
        }
    }

    private fun deviceInfo(): String {
        val webViewPackage = try {
            WebView.getCurrentWebViewPackage()
        } catch (e: Exception) {
            null
        }
        return "Device: ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) | " +
            "WebView: ${webViewPackage?.packageName ?: "unknown"} v${webViewPackage?.versionName ?: "unknown"}"
    }
}
