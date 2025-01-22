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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.properties.Delegates

// Lifecycle
// onCreate()	--->> 	Initialize variables, services, UI elements, and receivers.
// onStart()	--->> 	Register broadcast receivers, start intent handling.
// onResume()	--->> 	Start playback monitoring.
// onPause()	--->> 	Pause playback monitoring if needed.
// onStop()	    --->> 	Unregister broadcast receivers, stop services if needed.
// onDestroy()	--->> 	Cleanup resources and release dependencies.

// ---Section #1

class MainActivity : AppCompatActivity() {

    private lateinit var streamResultLauncher: ActivityResultLauncher<Intent>
    private val streamReceiver by lazy { StreamResultReceiver() }
    private var isStreamReceiverRegistered = false
    private var isMonitoringPlayback = false
    companion object {
        // Make references globally accessible
        const val yt1 = "com.teamsmart.videomanager.tv"
        const val yt2 = "com.google.android.youtube"
        const val tor1 = "org.videolan.vlc"
        const val tor2 = "com.mxtech.videoplayer.ad"
        lateinit var pkgYT: String
        lateinit var pkgP2P: String
        var isTor1 by Delegates.notNull<Boolean>()  // No default value, must be initialized
        // var isTor1: Boolean = false  // Provide a safe default value
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

// ---Section #2

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startMonitoringPlayback()
        
        // Initialize the package names when the app starts
        pkgYT = packageManager.getLaunchIntentForPackage(Companion.yt1)?.let { Companion.yt1 } ?: yt2
        pkgP2P = packageManager.getLaunchIntentForPackage( tor1 )?.let { tor1 } ?: tor2
        isTor1 = (pkgP2P == tor1)



        // Handle intent results from VLC or MX media players
        streamResultLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK ||
                    result.resultCode == Activity.RESULT_CANCELED ||
                    result.resultCode == Activity.RESULT_FIRST_USER) {
                    result.data?.let { data ->
                        val playbackIntent = Intent().apply {
                            action = Intent.ACTION_VIEW  // Most likely intent action for returning playback data
                            if (isTor1) {
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
        // --Maybe unnecessary Call to finish(): Ensure that calling finish() immediately after intent processing doesn't prevent playback.
        finish()
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            // Keep the app alive using a foreground service to prevent Android10+ aggressive background app killing
            val serviceIntent = Intent(this, PlaybackForegroundService::class.java)
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

        registerStreamReceiver()
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

    // Runnable to periodically check playback position and send it to Stremio
    private fun monitorPlaybackPosition() = lifecycleScope.launch(Dispatchers.IO) {
        while (isActive) {
            val currentPosition = getCurrentPlaybackPosition()
            withContext(Dispatchers.Main) {getCurrentPlaybackPosition()
                sendPlaybackPositionToStremio(this@MainActivity, currentPosition, 0L)
            }
            delay(10000L) // Poll every 10 seconds
        }
    }

    private fun stopForegroundServiceIfNeeded() {
        if (!isMonitoringPlayback) {
            stopService(Intent(this, PlaybackForegroundService::class.java))
        }
    }

// ---Section #3
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
    // Method to reset the playback position to ensure it starts fresh
    private fun resetPlaybackPosition() {
        StreamResultReceiver.lastKnownPosition = 0L
    }
    // Launch VLC or MX Player with the parsed intent and extras
    private fun launchWithStreamApp(originalIntent: Intent) {
        val playerIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(originalIntent.data, "video/*")
            setPackage(pkgP2P)
            putExtras(originalIntent.extras ?: Bundle())
            // Add specific extras based on player type
            if (isTor1) {
                putExtra("extra_position", getCurrentPlaybackPosition())  // VLC expects 'extra_position'
                putExtra("extra_duration", retrieveCachedPlaybackPosition())  // VLC expects 'extra_duration'
            } else {
                putExtra("position", getCurrentPlaybackPosition())  // MX Player expects 'position'
                putExtra("duration", retrieveCachedPlaybackPosition())  // MX Player expects 'duration'
            }
            putExtra("return_result", true)
            // Add intent flags based on player compatibility
            if (isTor1) {
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

// ---Section #4
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

    private fun getCurrentPlaybackPosition(): Long {
        return StreamResultReceiver.fetchLastKnownPosition().takeIf { it > 0 }
            ?: retrieveCachedPlaybackPosition()
    }
    private fun retrieveCachedPlaybackPosition(): Long {
        val sharedPref = getSharedPreferences("StubioPrefs", Context.MODE_PRIVATE)
        return sharedPref.getLong("last_playback_position", 0L)
    }

// ---Section #5

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
