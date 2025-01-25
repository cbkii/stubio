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

    // region -- Constants & Companion
    companion object {
        private const val TAG = "Stubio"

        const val YT1 = "com.teamsmart.videomanager.tv"
        const val YT2 = "com.google.android.youtube"
        const val TOR1 = "org.videolan.vlc"
        const val TOR2 = "com.mxtech.videoplayer.ad"

        // We'll fill these at runtime
        lateinit var pkgYT: String
        lateinit var pkgP2P: String
        var isTOR1 by Delegates.notNull<Boolean>()

        // Cache stremio server
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
            Log.d(TAG, "sendPlaybackPositionToStremio() - pos:$position dur:$duration")
            context.startActivity(intent)
        }
    }
    // endregion

    // region -- Properties & Receivers
    private lateinit var streamResultLauncher: ActivityResultLauncher<Intent>
    private var streamReceiver: StreamResultReceiver? = null
    private var isStreamReceiverRegistered = false

    private var playbackMonitoringJob: Job? = null
    private var lastKnownPosition: Long = 0L
    // endregion

    // region -- Lifecycle Overrides

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate() intent: $intent")

        // Prepare references to external player packages (VLC, YouTube)
        pkgYT = packageManager.getLaunchIntentForPackage(YT1)?.let { YT1 } ?: YT2
        pkgP2P = packageManager.getLaunchIntentForPackage(TOR1)?.let { TOR1 } ?: TOR2
        isTOR1 = (pkgP2P == TOR1)

        // Set up the activity result launcher for launching external players
        streamResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleExternalPlayerResult(result.resultCode, result.data)
        }

        // Register broadcast receiver once
        registerStreamReceiver()

        // Handle the incoming intent if we are freshly launched
        handleIncomingIntent(intent)
    }

    /**
     * Called if using `android:launchMode="singleTop"` or `singleTask` in manifest
     * and new Intents come into an already running Activity. Use this to avoid multiple
     * onCreate calls for each new video.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        Log.d(TAG, "onNewIntent() intent: $intent")
        setIntent(intent) // So getIntent() is updated
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume() called")

        // Start monitoring playback if required
        startMonitoringPlayback()

        // Optionally start foreground service if you truly need it
        // startPlaybackForegroundService()
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause() called")
        stopMonitoringPlayback()
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop() called")
        // Optionally stop the foreground service if playback is not ongoing
        // stopForegroundServiceIfNeeded()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy() called")

        // Clean up
        lifecycleScope.cancel()
        stopMonitoringPlayback()
        unregisterStreamReceiver()
    }
    // endregion

    // region -- Intent Handling

    private fun handleIncomingIntent(incomingIntent: Intent) {
        val uri = incomingIntent.data
        if (uri == null) {
            Log.w(TAG, "No URI found in intent. Finishing.")
            finish()
            return
        }

        Log.d(TAG, "handleIncomingIntent() -> URI: $uri")

        // Check if the host is allowed
        val host = uri.host
        if (host.isNullOrBlank() || !isAllowedHost(host)) {
            Log.w(TAG, "Host not allowed: $host. Finishing.")
            finish()
            return
        }

        // Route to either YouTube or P2P (VLC/MX)
        routeUri(uri, incomingIntent)
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
        Log.d(TAG, "isAllowedHost -> matched:$matched stored:$stored for host:$host")
        return matched || stored
    }

    private fun routeUri(uri: Uri, originalIntent: Intent) {
        // Check if it’s a YouTube ID
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
                Log.d(TAG, "launchYouTube() -> Starting $pkgYT with url: $youtubeUrl")
                startActivity(ytIntent)
            } else {
                Log.w(TAG, "No activity found to handle YouTube link!")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch YouTube: ${e.message}")
        }

        // We’re done with Stubio at this point
        finish()
    }

    /**
     * Launch VLC or MX Player. We'll request an ActivityResult so we can pass the final position back to Stremio.
     */
    private fun launchStreamPlayer(originalIntent: Intent) {
        val playerIntent = Intent(Intent.ACTION_VIEW).apply {
            data = originalIntent.data
            type = "video/*"
            setPackage(pkgP2P)
            putExtras(originalIntent.extras ?: Bundle())

            // Insert known position/duration
            val pos = getCurrentPlaybackPosition()
            val dur = retrieveCachedPlaybackPosition()
            if (isTOR1) {
                putExtra("extra_position", pos)
                putExtra("extra_duration", dur)
                Log.d(TAG, "launchStreamPlayer -> For VLC: pos:$pos dur:$dur")
            } else {
                putExtra("position", pos)
                putExtra("duration", dur)
                Log.d(TAG, "launchStreamPlayer -> For MX: pos:$pos dur:$dur")
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
                Log.w(TAG, "No suitable external player found.")
                finish()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch external player: ${e.message}")
            finish()
        }
    }
    // endregion

    // region -- External Player Result Handling
    private fun handleExternalPlayerResult(resultCode: Int, data: Intent?) {
        Log.d(TAG, "handleExternalPlayerResult -> resultCode:$resultCode data:$data")

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
                    // Relay back to Stremio
                    Log.d(TAG, "Relaying position:$position duration:$duration to Stremio")
                    sendPlaybackPositionToStremio(this, position, duration)
                }
            }
        } else {
            Log.w(TAG, "Unhandled result code from external player: $resultCode")
        }

        // Close Stubio after a short delay
        Handler(mainLooper).postDelayed({
            finish()
        }, 1000)
    }
    // endregion

    // region -- Playback Monitoring
    private fun startMonitoringPlayback() {
        if (playbackMonitoringJob?.isActive == true) return

        Log.d(TAG, "startMonitoringPlayback()")
        playbackMonitoringJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val currentPos = getCurrentPlaybackPosition()
                Log.d(TAG, "Polling playback position: $currentPos")

                withContext(Dispatchers.Main) {
                    // If you genuinely want to push position to Stremio every 10s:
                    sendPlaybackPositionToStremio(this@MainActivity, currentPos, 0L)
                }
                delay(10_000L)
            }
        }
    }

    private fun stopMonitoringPlayback() {
        playbackMonitoringJob?.cancel()
        playbackMonitoringJob = null
        Log.d(TAG, "stopMonitoringPlayback()")
    }

    private fun getCurrentPlaybackPosition(): Long {
        val lastKnown = StreamResultReceiver.fetchLastKnownPosition()
        return if (lastKnown > 0) {
            lastKnown
        } else {
            retrieveCachedPlaybackPosition()
        }
    }

    private fun retrieveCachedPlaybackPosition(): Long {
        val sp = getSharedPreferences("StubioPrefs", Context.MODE_PRIVATE)
        return sp.getLong("last_playback_position", 0L)
    }
    // endregion

    // region -- Broadcast Receiver
    class StreamResultReceiver : BroadcastReceiver() {
        companion object {
            var lastKnownPosition: Long = 0L
            var lastKnownDuration: Long = 0L
            fun fetchLastKnownPosition() = lastKnownPosition
        }

        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "StreamResultReceiver onReceive action:${intent.action} extras:${intent.extras}")
            val position: Long
            val duration: Long

            // For VLC or MX:
            if (intent.action?.endsWith("player.result") == true) {
                position = intent.getLongExtra("extra_position", -1L)
                duration = intent.getLongExtra("extra_duration", -1L)
            } else if (intent.action?.endsWith("mxplayer.result") == true) {
                position = intent.getLongExtra("position", -1L)
                duration = intent.getLongExtra("duration", -1L)
            } else {
                Log.w(TAG, "Unknown broadcast action: ${intent.action}")
                return
            }

            if (position >= 0 && duration > 0) {
                lastKnownPosition = position
                lastKnownDuration = duration
                sendPlaybackPositionToStremio(context, position, duration)
            }
        }
    }

    private fun registerStreamReceiver() {
        if (!isStreamReceiverRegistered) {
            streamReceiver = StreamResultReceiver()
            val filter = IntentFilter().apply {
                addAction("$pkgP2P.player.result")   // e.g. VLC
                addAction("$pkgP2P.mxplayer.result") // e.g. MX
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

    // region -- Stremio Server Caching
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
        // Only stop if you’re sure no playback is needed
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
                .setContentText("Managing playback handoff")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }

        override fun onBind(intent: Intent?): IBinder? = null
    }
    // endregion
}
