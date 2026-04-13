package com.intentrouter.stubio

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.properties.Delegates

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Stubio"
        private const val PREFS_NAME = "StubioPrefs"
        private const val KEY_LAST_STREAM = "last_playback_stream"
        private const val KEY_LAST_POSITION = "last_playback_position"
        private const val KEY_LAST_DURATION = "last_playback_duration"
        private const val STREMIO_PACKAGE = "com.stremio.one"

        const val YT1 = "com.teamsmart.videomanager.tv"
        const val YT2 = "com.google.android.youtube"
        const val TOR1 = "org.videolan.vlc"
        const val TOR2 = "com.mxtech.videoplayer.ad"

        lateinit var pkgYT: String
        lateinit var pkgP2P: String
        var isTOR1 by Delegates.notNull<Boolean>()

        fun sendPlaybackPositionToStremio(context: Context, position: Long, duration: Long) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("stremio://playback?position=$position&duration=$duration"))
                .apply {
                    setPackage(STREMIO_PACKAGE)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            runCatching { context.startActivity(intent) }
                .onFailure { logWarn("Unable to return playback position to Stremio", it) }
        }

        private fun logDebug(message: String) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, message)
            }
        }

        private fun logWarn(message: String, error: Throwable? = null) {
            if (error == null) {
                Log.w(TAG, message)
            } else {
                Log.w(TAG, message, error)
            }
        }
    }

    private var lastKnownPosition: Long = 0L
    private var lastKnownDuration: Long = 0L
    private var currentStreamUrl: String? = null

    private lateinit var streamResultLauncher: ActivityResultLauncher<Intent>
    private var streamReceiver: StreamResultReceiver? = null
    private var isStreamReceiverRegistered = false
    private var playbackMonitoringJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadPlayerPackages()
        restoreCachedPlaybackData()

        streamResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleExternalPlayerResult(result.resultCode, result.data)
        }

        registerStreamReceiver()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        startMonitoringPlayback()
    }

    override fun onPause() {
        super.onPause()
        stopMonitoringPlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMonitoringPlayback()
        unregisterStreamReceiver()
    }

    private fun handleIncomingIntent(incomingIntent: Intent) {
        val newUri = incomingIntent.data
        if (newUri == null) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        if (!isSupportedUri(newUri)) {
            logWarn("Unsupported URI: $newUri")
            finish()
            return
        }

        val newStreamUrl = newUri.toString()
        if (newStreamUrl != currentStreamUrl) {
            resetPlaybackTracking()
            currentStreamUrl = newStreamUrl
            storeCachedPlaybackData()
        }

        routeUri(newUri, incomingIntent)
    }

    private fun isSupportedUri(uri: Uri): Boolean {
        val scheme = uri.scheme.orEmpty()
        if (scheme == "stremio") return true

        if (scheme == "content" || scheme == "file") {
            return true
        }

        val host = uri.host ?: return false
        val hostMatches = isAllowedHost(host)
        val schemeAllowed = scheme == "http" || scheme == "https" || scheme == "intent"
        return schemeAllowed && hostMatches
    }

    private fun isAllowedHost(host: String): Boolean {
        val allowedHostPatterns = listOf(
            "127\\.0\\.0\\.1",
            "localhost",
            "192\\.168\\.[0-9]+\\.[0-9]+",
            "10\\.[0-9]+\\.[0-9]+\\.[0-9]+",
            "172\\.(1[6-9]|2[0-9]|3[0-1])\\.[0-9]+\\.[0-9]+",
            "[a-zA-Z0-9.-]+\\.stremio\\.com",
            "[a-zA-Z0-9.-]+\\.strem\\.io"
        )
        return allowedHostPatterns.any { host.matches(it.toRegex()) }
    }

    private fun routeUri(uri: Uri, originalIntent: Intent) {
        val youtubeRegex = """/yt/([A-Za-z0-9_-]{11})""".toRegex()
        val match = youtubeRegex.find(uri.toString())
        if (match != null) {
            val ytId = match.groupValues[1]
            launchYouTube("https://www.youtube.com/watch?v=$ytId")
        } else {
            launchStreamPlayer(originalIntent)
        }
    }

    private fun launchYouTube(youtubeUrl: String) {
        val ytIntent = Intent(Intent.ACTION_VIEW, Uri.parse(youtubeUrl)).apply {
            setPackage(pkgYT)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        if (ytIntent.resolveActivity(packageManager) != null) {
            startActivity(ytIntent)
        } else {
            logWarn("No YouTube handler found for $youtubeUrl")
        }
        finish()
    }

    private fun launchStreamPlayer(originalIntent: Intent) {
        val playerIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(originalIntent.data, "video/*")
            setPackage(pkgP2P)
            putExtras(originalIntent.extras ?: Bundle())
            if (isTOR1) {
                putExtra("extra_position", lastKnownPosition)
                putExtra("extra_duration", lastKnownDuration)
            } else {
                putExtra("position", lastKnownPosition)
                putExtra("duration", lastKnownDuration)
            }
            putExtra("return_result", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        if (playerIntent.resolveActivity(packageManager) != null) {
            streamResultLauncher.launch(playerIntent)
        } else {
            logWarn("No suitable external player found")
            finish()
        }
    }

    private fun handleExternalPlayerResult(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK || resultCode == Activity.RESULT_CANCELED) {
            data?.extras?.let { extras ->
                val position = if (isTOR1) extras.getLong("extra_position", -1L) else extras.getLong("position", -1L)
                val duration = if (isTOR1) extras.getLong("extra_duration", -1L) else extras.getLong("duration", -1L)
                if (position >= 0 && duration > 0) {
                    updatePlaybackPositionFromExternal(position, duration)
                }
            }
            wipePlaybackDataIfDone()
        }

        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 300)
    }

    private fun startMonitoringPlayback() {
        if (playbackMonitoringJob?.isActive == true) return

        playbackMonitoringJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val broadcastPos = StreamResultReceiver.lastKnownPosition
                val broadcastDur = StreamResultReceiver.lastKnownDuration

                if (broadcastPos > lastKnownPosition) lastKnownPosition = broadcastPos
                if (broadcastDur > lastKnownDuration) lastKnownDuration = broadcastDur
                storeCachedPlaybackData()
                delay(10_000L)
            }
        }
    }

    private fun stopMonitoringPlayback() {
        playbackMonitoringJob?.cancel()
        playbackMonitoringJob = null
    }

    private fun updatePlaybackPositionFromExternal(position: Long, duration: Long) {
        if (position >= 0) lastKnownPosition = position
        if (duration >= 0) lastKnownDuration = duration
        storeCachedPlaybackData()
        sendPlaybackPositionToStremio(this, position, duration)
    }

    class StreamResultReceiver : BroadcastReceiver() {
        companion object {
            var lastKnownPosition: Long = 0L
            var lastKnownDuration: Long = 0L
        }

        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action?.endsWith("player.result") == true) {
                val position = intent.getLongExtra("extra_position", -1L)
                val duration = intent.getLongExtra("extra_duration", -1L)
                if (position >= 0 && duration > 0) {
                    lastKnownPosition = position
                    lastKnownDuration = duration
                }
            } else if (intent.action?.endsWith("mxplayer.result") == true) {
                val position = intent.getLongExtra("position", -1L)
                val duration = intent.getLongExtra("duration", -1L)
                if (position >= 0 && duration > 0) {
                    lastKnownPosition = position
                    lastKnownDuration = duration
                }
            }
        }
    }

    private fun registerStreamReceiver() {
        if (isStreamReceiverRegistered) return

        streamReceiver = StreamResultReceiver()
        val filter = IntentFilter().apply {
            if (isTOR1) addAction("$pkgP2P.player.result") else addAction("$pkgP2P.mxplayer.result")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(streamReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(streamReceiver, filter)
        }

        isStreamReceiverRegistered = true
    }

    private fun unregisterStreamReceiver() {
        if (isStreamReceiverRegistered && streamReceiver != null) {
            unregisterReceiver(streamReceiver)
            isStreamReceiverRegistered = false
        }
    }

    private fun loadPlayerPackages() {
        val sp = getSharedPreferences(SetupActivity.PREFS_NAME, MODE_PRIVATE)

        val streamPrimary = sp.getString(SetupActivity.KEY_STREAM_PRIMARY, "")?.trim().orEmpty()
        val streamFallback = sp.getString(SetupActivity.KEY_STREAM_FALLBACK, "")?.trim().orEmpty()
        val trailerPrimary = sp.getString(SetupActivity.KEY_TRAILER_PRIMARY, "")?.trim().orEmpty()
        val trailerFallback = sp.getString(SetupActivity.KEY_TRAILER_FALLBACK, "")?.trim().orEmpty()

        pkgP2P = listOf(streamPrimary, streamFallback, TOR1, TOR2)
            .filter { it.isNotBlank() }
            .firstOrNull { packageManager.getLaunchIntentForPackage(it) != null }
            ?: TOR1

        pkgYT = listOf(trailerPrimary, trailerFallback, YT1, YT2)
            .filter { it.isNotBlank() }
            .firstOrNull { packageManager.getLaunchIntentForPackage(it) != null }
            ?: YT2

        isTOR1 = pkgP2P == TOR1
        logDebug("Selected players stream=$pkgP2P trailer=$pkgYT")
    }

    private fun restoreCachedPlaybackData() {
        val sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        currentStreamUrl = sp.getString(KEY_LAST_STREAM, null)
        lastKnownPosition = sp.getLong(KEY_LAST_POSITION, 0L)
        lastKnownDuration = sp.getLong(KEY_LAST_DURATION, 0L)
    }

    private fun storeCachedPlaybackData() {
        val sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        sp.edit()
            .putString(KEY_LAST_STREAM, currentStreamUrl)
            .putLong(KEY_LAST_POSITION, lastKnownPosition)
            .putLong(KEY_LAST_DURATION, lastKnownDuration)
            .apply()
    }

    private fun resetPlaybackTracking() {
        lastKnownPosition = 0L
        lastKnownDuration = 0L
    }

    private fun wipePlaybackDataIfDone() {
        lastKnownPosition = 0L
        lastKnownDuration = 0L
        currentStreamUrl = null

        val sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        sp.edit()
            .remove(KEY_LAST_STREAM)
            .remove(KEY_LAST_POSITION)
            .remove(KEY_LAST_DURATION)
            .apply()
    }
}
