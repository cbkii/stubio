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
import android.os.IBinder
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

    private lateinit var streamResultLauncher: ActivityResultLauncher<Intent>
    private var streamReceiver: StreamResultReceiver? = null
    private var isStreamReceiverRegistered = false
    private var isMonitoringPlayback = false


/* ************************************************ /
// Companion Object Globals
/ ************************************************ */

    companion object {
        // Make references globally accessible
        const val YT1 = "com.teamsmart.videomanager.tv"
        const val YT2 = "com.google.android.youtube"
        const val TOR1 = "org.videolan.vlc"
        const val TOR2 = "com.mxtech.videoplayer.ad"
        lateinit var pkgYT: String
        lateinit var pkgP2P: String
        var isTOR1 by Delegates.notNull<Boolean>()  // No default value, must be initialized

        // var isTOR1: Boolean = false  // Provide a safe default value
        var cachedStremioServer: String? = "127.0.0.1"

        // Function to relay the playback position back to Stremio [com.stremio.one]
        fun sendPlaybackPositionToStremio(context: Context, position: Long, duration: Long) {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("stremio://playback?position=$position&duration=$duration")
            ).apply {
                setPackage("com.stremio.one")
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
                // addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
        }

    }


/* ************************************************ /
// Lifecycle Methods
/ ************************************************ */


    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startMonitoringPlayback()

        // Initialize the package names when the app starts
        pkgYT = packageManager.getLaunchIntentForPackage(YT1)?.let { YT1 } ?: YT2
        pkgP2P = packageManager.getLaunchIntentForPackage(TOR1)?.let { TOR1 } ?: TOR2
        isTOR1 = (pkgP2P == TOR1)

        registerStreamReceiver()

        // --Maybe unnecessary Call to finish(): Ensure that calling finish() immediately after intent processing doesn't prevent playback.
        finish()
    }


    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            // Keep the app alive using a foreground service to prevent Android10+ aggressive background app killing
            val serviceIntent = Intent(this@MainActivity, PlaybackForegroundService::class.java)
            // val serviceIntent = Intent(applicationContext, PlaybackForegroundService::class.java)


            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            // Parse incoming intent URI to determine media routing
            val incomingUri: Uri? = intent.data
            incomingUri?.host?.let { host ->
                if (isAllowedHost(host)) {
                    routeUri(incomingUri, intent)
                    delay(333)  // Allow time for processing before launching the player
                } else {
                    delay(222)
                    finish()  // Terminate host not allowed
                }
            }
            delay(555)
            finish()  // Only finish if data was handled
        }

        // Handle intent results from VLC or MX media players
        streamResultLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK ||
                    result.resultCode == Activity.RESULT_CANCELED ||
                    result.resultCode == Activity.RESULT_FIRST_USER
                ) {
                    result.data?.let { data ->
                        val playbackIntent = Intent().apply {
                            action =
                                Intent.ACTION_VIEW  // Most likely intent action for returning playback data
                            if (isTOR1) {
                                // VLC Player expected intent keys
                                val position = data.getLongExtra("extra_position", -1L)
                                val duration = data.getLongExtra("extra_duration", -1L)
                                if (position >= 0 && duration > 0) {
                                    StreamResultReceiver.lastKnownPosition = position
                                    putExtra("extra_position", position)
                                    putExtra("extra_duration", duration)
                                }
                            } else {
                                // MX Player expected intent keys
                                val position = data.getLongExtra("position", -1L)
                                val duration = data.getLongExtra("duration", -1L)
                                if (position >= 0 && duration > 0) {
                                    StreamResultReceiver.lastKnownPosition = position
                                    putExtra("position", position)
                                    putExtra("duration", duration)
                                }
                            }
                        }
                        // Ensure valid intent before sending
                        if (playbackIntent.extras != null && playbackIntent.hasExtra("return_result")) {
                            sendBroadcast(playbackIntent)
                        }
                    }
                } else {
                    // Do nothing if the resultCode does not match expected values
                }
            }
    }

    // Ensure playback tracking starts only when the user is actively interacting with the app.
    // Save resources when the activity goes into the background.
    override fun onResume() {
        super.onResume()
        startMonitoringPlayback()
    }

    override fun onPause() {
        super.onPause()
        stopMonitoringPlayback()
    }

    override fun onStop() {
        super.onStop()
        if (isStreamReceiverRegistered) {
            unregisterReceiver(streamReceiver)
            isStreamReceiverRegistered = false
        }
        stopForegroundServiceIfNeeded()
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.cancel()
        stopMonitoringPlayback()
        lifecycleScope.launch {
            if (!intent.hasExtra("startfrom")) {
                delay(2222)
                resetPlaybackPosition()
            }
        }
    }


/* ************************************************ /
// Playback Monitoring
/ ************************************************ */

    // Runnable to periodically check playback position and send it to Stremio
    private var playbackMonitoringJob: Job? = null
    private fun monitorPlaybackPosition() = lifecycleScope.launch(Dispatchers.IO) {
        while (isActive) {
            val currentPosition = getCurrentPlaybackPosition()
            withContext(Dispatchers.Main) {
                sendPlaybackPositionToStremio(this@MainActivity, currentPosition, 0L)
            }
            delay(10000L) // Poll every 10 seconds
        }
    }
    private fun startMonitoringPlayback() {
        if (playbackMonitoringJob == null || playbackMonitoringJob?.isCancelled == true) {
            playbackMonitoringJob = monitorPlaybackPosition()
        }
    }
    private fun stopMonitoringPlayback() {
        playbackMonitoringJob?.cancel()
        playbackMonitoringJob = null
    }

    private fun getCurrentPlaybackPosition(): Long {
        return StreamResultReceiver.fetchLastKnownPosition().takeIf { it > 0 }
            ?: retrieveCachedPlaybackPosition()
    }
    private fun retrieveCachedPlaybackPosition(): Long {
        val sharedPref = getSharedPreferences("StubioPrefs", Context.MODE_PRIVATE)
        return sharedPref.getLong("last_playback_position", 0L)
    }


/* ************************************************ /
// Intent Handling
/ ************************************************ */


    // Check whether the incoming URI host matches allowed patterns
    private fun isAllowedHost(host: String?): Boolean {
        val allowedHostPatterns = listOf(
            "127\\.0\\.0\\.1",
            "localhost",
            "^(?:localhost|127\\.0\\.0\\.1|192\\.168\\.[0-9]+\\.[0-9]+|10\\.[0-9]+\\.[0-9]+\\.[0-9]+|172\\.(1[6-9]|2[0-9]|3[0-1])\\.[0-9]+\\.[0-9]+|[a-zA-Z0-9.-]+\\.stremio\\.com|[a-zA-Z0-9.-]+\\.strem\\.io)$"
        )
        return allowedHostPatterns.any { pattern -> host?.matches(pattern.toRegex()) == true }
                || host == getStoredStremioServer()
    }

    // Route match to the appropriate media player (P2P or parsed YouTube link)
    private fun routeUri(uri: Uri, intent: Intent) {
        val youtubeRegex = """(?:/yt/|v=|/v/|youtu.be|[/?=&])([a-zA-Z0-9_-]{11})""".toRegex()
        youtubeRegex.find(uri.toString())?.let {
            launchWithYTapp("https://www.youtube.com/watch?v=${it.groupValues[1]}")
        } ?: resetPlaybackPosition()
        launchWithStreamApp(intent)
    }

    // Launch SmartTubeNext or YouTube app
    private fun launchWithYTapp(youtubeUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(youtubeUrl)).apply {
            setPackage(pkgYT)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("return_result", true)
        }
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        }
    }

    // Launch VLC or MX Player with the parsed intent and extras
    private fun launchWithStreamApp(originalIntent: Intent) {
        val playerIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(originalIntent.data, "video/*")
            setPackage(pkgP2P)
            putExtras(originalIntent.extras ?: Bundle())
            // Add specific extras based on player type
            if (isTOR1) {
                putExtra(
                    "extra_position",
                    getCurrentPlaybackPosition()
                )  // VLC expects 'extra_position'
                putExtra(
                    "extra_duration",
                    retrieveCachedPlaybackPosition()
                )  // VLC expects 'extra_duration'
            } else {
                putExtra("position", getCurrentPlaybackPosition())  // MX Player expects 'position'
                putExtra(
                    "duration",
                    retrieveCachedPlaybackPosition()
                )  // MX Player expects 'duration'
            }
            putExtra("return_result", true)
            // Add intent flags based on player compatibility
            if (isTOR1) {
                addFlags(
                    // addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT or Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP)
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            } else {
                addFlags(
                    // addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT or Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP)
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_FORWARD_RESULT or
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        if (playerIntent.resolveActivity(packageManager) != null) {
            streamResultLauncher.launch(playerIntent)
            finish() // Call finish after launching
        } else {
            finish()  // Terminate if no suitable player is found
        }
    }

    // Method to reset the playback position to ensure it starts fresh
    private fun resetPlaybackPosition() {
        StreamResultReceiver.lastKnownPosition = 0L
    }

/* ************************************************ /
// Broadcast Receivers
/ ************************************************ */

    // Stream BroadcastReceiver to capture playback position
    class StreamResultReceiver : BroadcastReceiver() {
        companion object {
            var lastKnownPosition: Long = 0L
            var lastKnownDuration: Long = 0L
            fun fetchLastKnownPosition(): Long = lastKnownPosition
        }

        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "$pkgP2P.player.result" -> {
                    val position = intent.getLongExtra("extra_position", -1L)
                    val duration = intent.getLongExtra("extra_duration", -1L)
                    if (position >= 0 && duration > 0) {
                        lastKnownPosition = position
                        lastKnownDuration = duration
                    }
                }

                "$pkgP2P.mxplayer.result" -> {
                    val position = intent.getLongExtra("position", -1L)
                    val duration = intent.getLongExtra("duration", -1L)
                    if (position >= 0 && duration > 0) {
                        lastKnownPosition = position
                        lastKnownDuration = duration
                    }
                }
            }
            // Send the playback position to Stremio, using last known values if they are valid
            if (lastKnownPosition > 0 && lastKnownDuration > 0) {
                sendPlaybackPositionToStremio(context, lastKnownPosition, lastKnownDuration)
            }
        }

    }

    private fun registerStreamReceiver() {
        if (!isStreamReceiverRegistered) {
            streamReceiver = StreamResultReceiver()
            val filter = IntentFilter().apply {
                addAction("$pkgP2P.player.result")  // VLC compatibility
                addAction("$pkgP2P.mxplayer.result")  // MX Player compatibility
            }
            registerReceiver(StreamResultReceiver(), filter)
            isStreamReceiverRegistered = true
        }
    }

/* ************************************************ /
// Caching
/ ************************************************ */

    // Cache user config addressMinimise repeated I/O operations, ensures only one instance is used throughout
    private fun getStoredStremioServer(): String {
        return cachedStremioServer ?: run {
            val sharedPref = getSharedPreferences("StubioPrefs", Context.MODE_PRIVATE)
            sharedPref.getString("stremio_server_ip", "127.0.0.1").also {
                cachedStremioServer = it
            } ?: "127.0.0.1"
        }
    }

    // Update domain/IP with user-configured Stremio server address
    class ServerConfigReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val serverIp = intent.getStringExtra("server_ip") ?: return
            val sharedPref = context.getSharedPreferences("StubioPrefs", Context.MODE_PRIVATE)
            sharedPref.edit().putString("stremio_server_ip", serverIp).apply()
            // Log.d("Stubio", "Stremio server IP updated to: $serverIp")
        }
    }

/* ************************************************ /
// Foreground Service Management
/ ************************************************ */

    private fun stopForegroundServiceIfNeeded() {
        if (!isMonitoringPlayback) {
            stopService(Intent(this, PlaybackForegroundService::class.java))
        }
    }
    // Keep alive for polling playback time
    class PlaybackForegroundService : Service() {
        override fun onCreate() {
            super.onCreate()
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
                .setContentText("Playback position handler")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }
        override fun onBind(intent: Intent?): IBinder? = null
    }

}