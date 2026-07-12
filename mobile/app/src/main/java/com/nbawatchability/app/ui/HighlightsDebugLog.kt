package com.nbawatchability.app.ui

import android.content.ContentValues
import android.content.Context
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

    fun start(context: Context, videoId: String) {
        lines.clear()
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
                resolver.delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                    arrayOf(FILE_NAME)
                )
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let { resolver.openOutputStream(it)?.use { out -> out.write(content.toByteArray()) } }
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
