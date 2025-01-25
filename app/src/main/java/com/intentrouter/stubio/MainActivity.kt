package com.intentrouter.stubio

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.properties.Delegates

class MainActivity : AppCompatActivity() {

    // region -- Companion / Constants
    companion object {
        private const val TAG = "Stubio"

        // Potential YouTube app packages
        const val YT1 = "com.teamsmart.videomanager.tv"
        const val YT2 = "com.google.android.youtube"

        // Potential Torrent/Local stream player packages
        const val TOR1 = "org.videolan.vlc"
        const val TOR2 = "com.mxtech.videoplayer.ad"

        // To fill at runtime
        lateinit var pkgYT: String
        lateinit var pkgP2P: String
        var isTOR1 by Delegates.notNull<Boolean>()
        var cachedStremioServer: String? = "127.0.0.1"

        /**
         * Notify Stremio about updated playback position.
         */
        fun sendPlaybackPositionToStremio(context: Context, position: Long, duration: Long) {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("stremio://playback?position=$position&duration=$duration")
            ).apply {
                setPackage("com.stremio.one")
                addFlags(
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }
            Log.d(TAG, "sendPlaybackPositionToStremio() -> pos:$position dur:$duration")
            context.startActivity(intent)
        }
    }
    // endregion

    // region -- Properties & Receivers
    private var lastKnownPosition: Long = 0L
    private var lastKnownDuration: Long = 0L
    private var currentStreamUrl: String? = null

    private lateinit var streamResultLauncher: ActivityResultLauncher<Intent>
    private var streamReceiver: StreamResultReceiver? = null
    private var isStreamReceiverRegistered = false

    private var playbackMonitoringJob: Job? = null
    // endregion

    // region -- Lifecycle

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate() -> intent: $intent")

        // Choose external player packages from the device installs
        pkgYT = packageManager.getLaunchIntentForPackage(YT1)?.let { YT1 } ?: YT2
        pkgP2P = packageManager.getLaunchIntentForPackage(TOR1)?.let { TOR1 } ?: TOR2
        isTOR1 = (pkgP2P == TOR1)
        Log.d(TAG, "Set Players: $pkgYT  &  $pkgP2P")

        // Restore any previously cached position/duration & stream
        restoreCachedPlaybackData()

        // Prepare external player activity result
        streamResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleExternalPlayerResult(result.resultCode, result.data)
        }

        // Register broadcast receiver once
        registerStreamReceiver()

        // Handle the incoming intent if we are freshly launched
        handleIncomingIntent(intent)
    }

    /**
     * onNewIntent called if using `launchMode="singleTop"` or `singleTask`
     * used to avoid multiple onCreate calls for each new video.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        Log.d(TAG, "onNewIntent() called -> new intent: $intent")
        setIntent(intent) // So getIntent() is updated
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume() called")
        startMonitoringPlayback()
        startPlaybackForegroundService()
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause() called")
        stopMonitoringPlayback()
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop() called")
        stopForegroundServiceIfNeeded()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy() called")
        lifecycleScope.cancel()
        stopMonitoringPlayback()
        unregisterStreamReceiver()
        wipePlaybackDataIfDone()
    }
    // endregion

    // region -- Intent Handling

    private fun handleIncomingIntent(incomingIntent: Intent) {
        val newUri = incomingIntent.data
        if (newUri == null) {
            Log.w(TAG, "handleIncomingIntent -> No URI found, finishing.")
            finish()
            return
        }

        // Check if host is valid and allowed
        val host = newUri.host
        if (host.isNullOrBlank() || !isAllowedHost(host)) {
            Log.w(TAG, "Host not allowed or is invalid: $host")
            finish()
            return
        }

        // If new incoming stream is different from currently saved stream
        // reset position/duration to 0 to avoid "resuming" from a prior video.
        val newStreamUrl = newUri.toString()
        if (newStreamUrl != currentStreamUrl) {
            Log.d(TAG, "New stream detected, resetting lastKnownPosition/duration.")
            resetPlaybackTracking()
            // Update current stream reference
            currentStreamUrl = newStreamUrl
            // Save that new URL to prefs (so we remember across process kill)
            storeCachedPlaybackData()
        }

        // Distinguish YouTube vs normal stream
        routeUri(newUri, incomingIntent)
    }

    private fun isAllowedHost(host: String): Boolean {
        val allowedHostPatterns = listOf(
            "127\\.0\\.0\\.1",
            "^(?:localhost|" +
                    "192\\.168\\.[0-9]+\\.[0-9]+|" +
                    "10\\.[0-9]+\\.[0-9]+\\.[0-9]+|" +
                    "172\\.(1[6-9]|2[0-9]|3[0-1])\\.[0-9]+\\.[0-9]+|" +
                    "[a-zA-Z0-9.-]+\\.stremio\\.com|" +
                    "[a-zA-Z0-9.-]+\\.strem\\.io)$"
        )
        val matched = allowedHostPatterns.any { pattern -> host.matches(pattern.toRegex()) }
        val stored = (host == getStoredStremioServer())
        Log.d(TAG, "isAllowedHost -> matched:$matched or stored:$stored, host:$host")
        return matched || stored
    }

    private fun routeUri(uri: Uri, originalIntent: Intent) {
        val youtubeRegex = """/yt/([A-Za-z0-9_-]{11})""".toRegex()
        val match = youtubeRegex.find(uri.toString())
        if (match != null) {
            val ytId = match.groupValues[1]
            val youtubeUrl = "https://www.youtube.com/watch?v=$ytId"
            launchYouTube(youtubeUrl)
        } else {
            launchStreamPlayer(originalIntent)
        }
    }

    private fun launchYouTube(youtubeUrl: String) {
        val ytIntent = Intent(Intent.ACTION_VIEW, Uri.parse(youtubeUrl)).apply {
            setPackage(pkgYT)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            if (ytIntent.resolveActivity(packageManager) != null) {
                Log.d(TAG, "launchYouTube -> Starting $pkgYT with url: $youtubeUrl")
                startActivity(ytIntent)
            } else {
                Log.w(TAG, "No handler found for YouTube link! ($youtubeUrl)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch YouTube: ${e.message}")
        }

        finish()
    }

    private fun launchStreamPlayer(originalIntent: Intent) {
        val playerIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(originalIntent.data, "video/*")
            setPackage(pkgP2P)
            putExtras(originalIntent.extras ?: Bundle())

            // Insert known position/duration
            if (isTOR1) {
                putExtra("extra_position", lastKnownPosition)
                putExtra("extra_duration", lastKnownDuration)
                Log.d(TAG, "launchStreamPlayer -> For VLC pos:$lastKnownPosition dur:$lastKnownDuration")
            } else {
                putExtra("position", lastKnownPosition)
                putExtra("duration", lastKnownDuration)
                Log.d(TAG, "launchStreamPlayer -> For MX pos:$lastKnownPosition dur:$lastKnownDuration")
            }
            putExtra("return_result", true)

            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            if (playerIntent.resolveActivity(packageManager) != null) {
                Log.d(TAG, "launchStreamPlayer -> Launching $pkgP2P")
                streamResultLauncher.launch(playerIntent)
            } else {
                Log.w(TAG, "Finishing, no suitable external player found.")
                finish()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Finishing, failed to launch external player: ${e.message}")
            finish()
        }
    }
    // endregion

    // region -- External Player Result

    /**
     * Called upon exit of external player if it returns a result.
     */
    private fun handleExternalPlayerResult(resultCode: Int, data: Intent?) {
        Log.d(TAG, "handleExternalPlayerResult -> code:$resultCode data:$data")

        if (resultCode == Activity.RESULT_OK || resultCode == Activity.RESULT_CANCELED) {
            data?.extras?.let { extras ->
                Log.d(TAG, "External player returned extras: $extras")
                // Attempt to read position/duration from whichever fields are relevant
                val position = if (isTOR1) {
                    extras.getLong("extra_position", -1L)
                } else {
                    extras.getLong("position", -1L)
                }
                val duration = if (isTOR1) {
                    extras.getLong("extra_duration", -1L)
                } else {
                    extras.getLong("duration", -1L)
                }
                if (position >= 0 && duration > 0) {
                    updatePlaybackPositionFromExternal(position, duration)
                }
            }

            // If we reached here, it’s presumably a clean exit from external player,
            // so let's also “wipe” to reset after successful return to Stremio.
            wipePlaybackDataIfDone()
        } else {
            Log.w(TAG, "Unhandled result code:$resultCode. No final position retrieved.")
        }

        // Delay for ~1s so Stremio has time to handle the callback
        Handler(mainLooper).postDelayed({
            finish()
        }, 1234)
    }
    // endregion

    // region -- Playback Monitoring

    private fun startMonitoringPlayback() {
        if (playbackMonitoringJob?.isActive == true) return

        Log.d(TAG, "startMonitoringPlayback()")
        playbackMonitoringJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                // Check if broadcast updated a “global” position
                val broadcastPos = StreamResultReceiver.lastKnownPosition
                val broadcastDur = StreamResultReceiver.lastKnownDuration

                // If broadcast gave us something more recent, use it
                if (broadcastPos > lastKnownPosition) {
                    lastKnownPosition = broadcastPos
                }
                if (broadcastDur > lastKnownDuration) {
                    lastKnownDuration = broadcastDur
                }

                // Persist to SharedPreferences so if the app is killed, we don’t lose it
                storeCachedPlaybackData()

                // (Optional) send to Stremio if you want near real-time scrobbling
                withContext(Dispatchers.Main) {
                    sendPlaybackPositionToStremio(this@MainActivity, lastKnownPosition, lastKnownDuration)
                }

                Log.d(TAG, "Polling playback -> pos:$lastKnownPosition dur:$lastKnownDuration")
                delay(10_000L)
            }
        }
    }

    private fun stopMonitoringPlayback() {
        playbackMonitoringJob?.cancel()
        playbackMonitoringJob = null
        Log.d(TAG, "stopMonitoringPlayback()")
    }

    /**
     * External player or broadcast gave us a new position/duration - let's store it.
     */
    private fun updatePlaybackPositionFromExternal(position: Long, duration: Long) {
        if (position >= 0) {
            lastKnownPosition = position
        }
        if (duration >= 0) {
            lastKnownDuration = duration
        }
        storeCachedPlaybackData()

        // Also send an immediate update to Stremio
        sendPlaybackPositionToStremio(this, position, duration)
    }
    // endregion

    // region -- Broadcast Receiver
    class StreamResultReceiver : BroadcastReceiver() {
        companion object {
            var lastKnownPosition: Long = 0L
            var lastKnownDuration: Long = 0L
        }

        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "StreamResultReceiver -> action:${intent.action} extras:${intent.extras}")

            if (intent.action?.endsWith("player.result") == true) {
                // Typically VLC
                val position = intent.getLongExtra("extra_position", -1L)
                val duration = intent.getLongExtra("extra_duration", -1L)
                if (position >= 0 && duration > 0) {
                    lastKnownPosition = position
                    lastKnownDuration = duration
                    Log.d(TAG, "VLC broadcast -> pos:$position dur:$duration")
                }
            } else if (intent.action?.endsWith("mxplayer.result") == true) {
                // Typically MX
                val position = intent.getLongExtra("position", -1L)
                val duration = intent.getLongExtra("duration", -1L)
                if (position >= 0 && duration > 0) {
                    lastKnownPosition = position
                    lastKnownDuration = duration
                    Log.d(TAG, "MX broadcast -> pos:$position dur:$duration")
                }
            } else {
                Log.w(TAG, "Unknown broadcast action: ${intent.action}")
            }
        }
    }

    private fun registerStreamReceiver() {
        if (!isStreamReceiverRegistered) {
            streamReceiver = StreamResultReceiver()
            val filter = IntentFilter().apply {
                if (isTOR1) {
                    addAction("$pkgP2P.player.result")   // e.g., VLC broadcast
                } else {
                    addAction("$pkgP2P.mxplayer.result") // e.g., MX broadcast
                }
            }
            registerReceiver(streamReceiver, filter)
            isStreamReceiverRegistered = true
            Log.d(TAG, "StreamResultReceiver registered")
        }
    }

    private fun unregisterStreamReceiver() {
        if (isStreamReceiverRegistered && streamReceiver != null) {
            unregisterReceiver(streamReceiver)
            isStreamReceiverRegistered = false
            Log.d(TAG, "StreamResultReceiver unregistered")
        }
    }
    // endregion

    // region -- Caching & Wiping

    /**
     * Load last known stream URL/position/duration from SharedPreferences if they exist.
     */
    private fun restoreCachedPlaybackData() {
        val sp = getSharedPreferences("StubioPrefs", MODE_PRIVATE)
        currentStreamUrl = sp.getString("last_playback_stream", null)
        lastKnownPosition = sp.getLong("last_playback_position", 0L)
        lastKnownDuration = sp.getLong("last_playback_duration", 0L)
        Log.d(TAG, "restoreCachedPlaybackData -> url:$currentStreamUrl pos:$lastKnownPosition dur:$lastKnownDuration")
    }

    /**
     * Save the current stream URL/position/duration to SharedPreferences.
     */
    private fun storeCachedPlaybackData() {
        val sp = getSharedPreferences("StubioPrefs", MODE_PRIVATE)
        sp.edit()
            .putString("last_playback_stream", currentStreamUrl)
            .putLong("last_playback_position", lastKnownPosition)
            .putLong("last_playback_duration", lastKnownDuration)
            .apply()
    }

    /**
     * Reset local state for a new stream or forced manual reset.
     */
    private fun resetPlaybackTracking() {
        lastKnownPosition = 0L
        lastKnownDuration = 0L
        // do NOT reset currentStreamUrl here if about to set it to the new one
    }

    /**
     * Wipe the stored data after we are done returning position to Stremio,
     * so the next video starts from scratch (unless we store again).
     */
    private fun wipePlaybackDataIfDone() {
        // Only wipe if we know the user is "done" with the video
        Log.d(TAG, "wipePlaybackDataIfDone -> clearing stored playback data.")
        lastKnownPosition = 0L
        lastKnownDuration = 0L
        currentStreamUrl = null

        val sp = getSharedPreferences("StubioPrefs", MODE_PRIVATE)
        sp.edit()
            .remove("last_playback_stream")
            .remove("last_playback_position")
            .remove("last_playback_duration")
            .apply()
    }

    private fun getStoredStremioServer(): String {
        return cachedStremioServer ?: run {
            val sp = getSharedPreferences("StubioPrefs", Context.MODE_PRIVATE)
            val ip = sp.getString("stremio_server_ip", "127.0.0.1").also {
                cachedStremioServer = it
            } ?: "127.0.0.1"
            Log.d(TAG, "getStoredStremioServer() -> $ip")
            ip
        }
    }
    // endregion

    // region -- Foreground Service
    private fun startPlaybackForegroundService() {
        val serviceIntent = Intent(this, PlaybackForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopForegroundServiceIfNeeded() {
        // Only stop if you’re sure no playback or polling is needed
        stopService(Intent(this, PlaybackForegroundService::class.java))
    }

    class PlaybackForegroundService : Service() {
        override fun onCreate() {
            super.onCreate()
            Log.d(TAG, "PlaybackForegroundService -> onCreate")
            ensureNotification()
        }

        private fun ensureNotification() {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                startForeground(1, notification)
            } else {
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(1, notification)
            }
        }

        private fun buildNotification(): Notification {
            return NotificationCompat.Builder(this, "STUBIO_CHANNEL")
                .setContentTitle("Stubio Service")
                .setContentText("Handling playback position and redirection")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }

        override fun onBind(intent: Intent?): IBinder? = null
    }
    // endregion
}
