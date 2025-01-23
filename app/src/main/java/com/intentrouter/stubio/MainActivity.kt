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
import android.util.Log      // DEBUG: For logging
import android.widget.Toast // DEBUG: For toast debugging
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
    private val REQUEST_CODE_PLAYBACK = 1001

    /* ************************************************ /
       Companion Object Globals
     / ************************************************ */
    companion object {
        const val YT1 = "com.teamsmart.videomanager.tv"
        const val YT2 = "com.google.android.youtube"
        const val TOR1 = "org.videolan.vlc"
        const val TOR2 = "com.mxtech.videoplayer.ad"
        lateinit var pkgYT: String
        lateinit var pkgP2P: String
        var isTOR1 by Delegates.notNull<Boolean>()  // No default value, must be initialized

        var cachedStremioServer: String? = "127.0.0.1"

        // Relay the playback position back to Stremio
        fun sendPlaybackPositionToStremio(context: Context, position: Long, duration: Long) {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("stremio://playback?position=$position&duration=$duration")
            ).apply {
                setPackage("com.stremio.one")
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            Log.d("Stubio", "sendPlaybackPositionToStremio() - Sending position:$position duration:$duration")
            Toast.makeText(context, "DEBUG: Sending position:$position to Stremio", Toast.LENGTH_SHORT).show()
            context.startActivity(intent)
        }
    }

    /* ************************************************ /
       Lifecycle Methods
     / ************************************************ */

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("Stubio", "onCreate() called with intent: $intent")
        Toast.makeText(this, "DEBUG: onCreate", Toast.LENGTH_SHORT).show()

        startMonitoringPlayback()

        pkgYT = packageManager.getLaunchIntentForPackage(YT1)?.let { YT1 } ?: YT2
        pkgP2P = packageManager.getLaunchIntentForPackage(TOR1)?.let { TOR1 } ?: TOR2
        isTOR1 = (pkgP2P == TOR1)

        registerStreamReceiver()

        // DEBUG: Potential problem: finishing the activity in onCreate can cause everything to end prematurely
        // finish()
        // Avoid finishing until the external player is launched (or returned from).
    }

    override fun onStart() {
        super.onStart()
        Log.d("Stubio", "onStart() called")
        Toast.makeText(this, "DEBUG: onStart", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            // Keep the app alive using a foreground service to prevent Android10+ aggressive background app killing
            val serviceIntent = Intent(this@MainActivity, PlaybackForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Log.d("Stubio", "onStart() -> startForegroundService()")
                startForegroundService(serviceIntent)
            } else {
                Log.d("Stubio", "onStart() -> startService()")
                startService(serviceIntent)
            }

            val incomingUri: Uri? = intent.data
            Log.d("Stubio", "Incoming URI: $incomingUri")
            Toast.makeText(this@MainActivity, "DEBUG: Incoming URI: $incomingUri", Toast.LENGTH_SHORT).show()

            incomingUri?.host?.let { host ->
                Log.d("Stubio", "Host found: $host")
                if (isAllowedHost(host)) {
                    Log.d("Stubio", "Host is allowed. Routing URI...")
                    routeUri(incomingUri, intent)
                    delay(456)  // Allow time for processing before launching the player
                } else {
                    Log.w("Stubio", "Host NOT allowed: $host. Finishing.")
                    Toast.makeText(this@MainActivity, "DEBUG: Host not allowed, finishing.", Toast.LENGTH_SHORT).show()
                    delay(123)
                    finish()
                }
            } ?: run {
                Log.w("Stubio", "No URI host. Finishing.")
                Toast.makeText(this@MainActivity, "DEBUG: No URI host, finishing.", Toast.LENGTH_SHORT).show()
                delay(123)
                finish()
            }
       }

        // Var to handle intent results from VLC or MX media players
        streamResultLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                Log.d("Stubio", "External Player Activity Result received. resultCode = ${result.resultCode}")
                Toast.makeText(this, "DEBUG: Received ActivityResult code:${result.resultCode}", Toast.LENGTH_SHORT).show()

                if (result.resultCode == RESULT_OK ||
                    result.resultCode == Activity.RESULT_CANCELED ||
                    result.resultCode == Activity.RESULT_FIRST_USER
                ) {
                    result.data?.let { data ->
                        Log.d("Stubio", "Result data from external player: $data.extras")
                        val playbackIntent = Intent().apply {
                            action = Intent.ACTION_VIEW
                            if (isTOR1) {
                                val position = data.getLongExtra("extra_position", -1L)
                                val duration = data.getLongExtra("extra_duration", -1L)
                                Log.d("Stubio", "VLC result -> position:$position duration:$duration")
                                if (position >= 0 && duration > 0) {
                                    StreamResultReceiver.lastKnownPosition = position
                                    putExtra("extra_position", position)
                                    putExtra("extra_duration", duration)
                                }
                            } else {
                                val position = data.getLongExtra("position", -1L)
                                val duration = data.getLongExtra("duration", -1L)
                                Log.d("Stubio", "MX result -> position:$position duration:$duration")
                                if (position >= 0 && duration > 0) {
                                    StreamResultReceiver.lastKnownPosition = position
                                    putExtra("position", position)
                                    putExtra("duration", duration)
                                }
                            }
                        }
                        // Only broadcast if there's something to send
                        if (playbackIntent.extras != null && playbackIntent.hasExtra("return_result")) {
                            Log.d("Stubio", "Broadcasting playbackIntent with extras.")
                            sendBroadcast(playbackIntent)
                        }
                    }
                } else {
                    Log.w("Stubio", "Result code from external player not handled: ${result.resultCode}")
                }
            }
    }

    // Ensure playback tracking starts only when the user is actively interacting with the app.
    // Save resources when the activity goes into the background.
    override fun onResume() {
        super.onResume()
        Log.d("Stubio", "onResume() called")
        Toast.makeText(this, "DEBUG: onResume", Toast.LENGTH_SHORT).show()

        startMonitoringPlayback()
    }

    override fun onPause() {
        super.onPause()
        Log.d("Stubio", "onPause() called")
        Toast.makeText(this, "DEBUG: onPause", Toast.LENGTH_SHORT).show()

        stopMonitoringPlayback()
    }

    override fun onStop() {
        super.onStop()
        Log.d("Stubio", "onStop() called")
        Toast.makeText(this, "DEBUG: onStop", Toast.LENGTH_SHORT).show()

        if (isStreamReceiverRegistered) {
            Log.d("Stubio", "Unregistering StreamResultReceiver")
            unregisterReceiver(streamReceiver)
            isStreamReceiverRegistered = false
        }
        stopForegroundServiceIfNeeded()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("Stubio", "onDestroy() called")
        Toast.makeText(this, "DEBUG: onDestroy", Toast.LENGTH_SHORT).show()

        lifecycleScope.cancel()
        stopMonitoringPlayback()
        lifecycleScope.launch {
            if (!intent.hasExtra("startfrom")) {
                delay(2222)
                resetPlaybackPosition()
                Log.d("Stubio", "Playback position reset after ~2s post onDestroy")
            }
        }
    }

    /* ************************************************ /
       Playback Monitoring
     / ************************************************ */

    // Runnable to periodically check playback position and send it to Stremio
    private var playbackMonitoringJob: Job? = null
    private fun monitorPlaybackPosition() = lifecycleScope.launch(Dispatchers.IO) {
        while (isActive) {
            val currentPosition = getCurrentPlaybackPosition()
            Log.d("Stubio", "Polling playback position: $currentPosition")
            withContext(Dispatchers.Main) {
                sendPlaybackPositionToStremio(this@MainActivity, currentPosition, 0L)
            }
            delay(10000L) // 10s
        }
    }

    private fun startMonitoringPlayback() {
        if (playbackMonitoringJob == null || playbackMonitoringJob?.isCancelled == true) {
            isMonitoringPlayback = true
            playbackMonitoringJob = monitorPlaybackPosition()
            Log.d("Stubio", "Started monitoring playback position")
        }
    }

    private fun stopMonitoringPlayback() {
        isMonitoringPlayback = false
        playbackMonitoringJob?.cancel()
        playbackMonitoringJob = null
        Log.d("Stubio", "Stopped monitoring playback position")
    }

    private fun getCurrentPlaybackPosition(): Long {
        val lastKnown = StreamResultReceiver.fetchLastKnownPosition()
        return if (lastKnown > 0) {
            lastKnown
        } else {
            val cached = retrieveCachedPlaybackPosition()
            Log.d("Stubio", "Using cached playback position: $cached")
            cached
        }
    }

    private fun retrieveCachedPlaybackPosition(): Long {
        val sharedPref = getSharedPreferences("StubioPrefs", Context.MODE_PRIVATE)
        return sharedPref.getLong("last_playback_position", 0L)
    }

    /* ************************************************ /
       Intent Handling
     / ************************************************ */

    // Check whether the incoming URI host matches allowed patterns
    private fun isAllowedHost(host: String?): Boolean {
        // If the host is null, it's obviously not allowed
        if (host == null) {
            Log.w("Stubio", "isAllowedHost() -> Host is null")
            return false
        }
        val allowedHostPatterns = listOf(
            "127\\.0\\.0\\.1",
            "^(?:localhost|192\\.168\\.[0-9]+\\.[0-9]+|10\\.[0-9]+\\.[0-9]+\\.[0-9]+|172\\.(1[6-9]|2[0-9]|3[0-1])\\.[0-9]+\\.[0-9]+|[a-zA-Z0-9.-]+\\.stremio\\.com|[a-zA-Z0-9.-]+\\.strem\\.io)$"
        )
        val matched = allowedHostPatterns.any { pattern -> host.matches(pattern.toRegex()) }
        val stored = (host == getStoredStremioServer())
        Log.d("Stubio", "Checking isAllowedHost -> matched:$matched storedMatch:$stored for host:$host")
        return matched || stored
    }

    // Route match to the appropriate media player (P2P or parsed YouTube link)
    private fun routeUri(uri: Uri, intent: Intent) {
        Log.d("Stubio", "routeUri() -> Checking if it's a YouTubeID or TorStream: $uri")

        // Make the YouTube regex less aggressive so it only matches actual /yt/ paths:
        // e.g. "/yt/VIDEO_ID" with exactly 11 valid YT chars
        val youtubeRegex = """/yt/([A-Za-z0-9_-]{11})""".toRegex()
        val match = youtubeRegex.find(uri.toString())
        if (match != null) {
            val youtubeId = match.groupValues[1]
            Log.d("Stubio", "YouTube ID found: $youtubeId, launching YouTube")
            launchWithYTapp("https://www.youtube.com/watch?v=$youtubeId")
        } else {
            Log.d("Stubio", "Not a recognized YouTube link -> launching P2P player")
            launchWithStreamApp(intent)
        }
    }
    // Launch SmartTubeNext or YouTube app, no need for return result
    private fun launchWithYTapp(youtubeUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(youtubeUrl)).apply {
            setPackage(pkgYT)
            // Launch YT Player as an independent task, no need to return position
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            // putExtra("return_result", true)
        }
        if (intent.resolveActivity(packageManager) != null) {
            Log.d("Stubio", "launchWithYTapp() -> Starting YT app with url:$youtubeUrl pkg:$pkgYT")
            Toast.makeText(this, "DEBUG: Launching $pkgYT for YouTube link", Toast.LENGTH_SHORT).show()
            startActivity(intent)
        } else {
            Log.w("Stubio", "No activity found to handle YouTube link!")
            Toast.makeText(this, "DEBUG: No YT app found, finishing", Toast.LENGTH_SHORT).show()
        }
        // As we don’t need result from external player, safely finish soon after its launched
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            finish()
        }, 1234)
    }

    // Launch VLC/MX Player with parsed intent and extras, for return result
    private fun launchWithStreamApp(originalIntent: Intent) {
        val playerIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(originalIntent.data, "video/*")
            setPackage(pkgP2P)
            putExtras(originalIntent.extras ?: Bundle())
            if (isTOR1) {
                val pos = getCurrentPlaybackPosition()
                putExtra("extra_position", pos)
                putExtra("extra_duration", retrieveCachedPlaybackPosition())
                Log.d("Stubio", "launchWithStreamApp() -> For VLC: extra_position:$pos")
            } else {
                val pos = getCurrentPlaybackPosition()
                putExtra("position", pos)
                putExtra("duration", retrieveCachedPlaybackPosition())
                Log.d("Stubio", "launchWithStreamApp() -> For MX: position:$pos")
            }
            putExtra("return_result", true)

            // Add intent flags based on player compatibility
            val flags = if (intent.flags and Intent.FLAG_ACTIVITY_FORWARD_RESULT != 0) {
                Intent.FLAG_ACTIVITY_FORWARD_RESULT or Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP
            } else {
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            addFlags(flags)
        }
        // Check if a compatible media player is available
        if (playerIntent.resolveActivity(packageManager) != null) {
            Log.d("Stubio", "launchWithStreamApp() -> Found valid external player. Launching $pkgP2P")
            Toast.makeText(this, "DEBUG: Launching $pkgP2P for stream", Toast.LENGTH_SHORT).show()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                streamResultLauncher.launch(playerIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityForResult(playerIntent, REQUEST_CODE_PLAYBACK)
            }
        } else {
            Log.w("Stubio", "No suitable P2P player found.")
            Toast.makeText(this, "DEBUG: No P2P player found, finishing.", Toast.LENGTH_SHORT).show()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                finish()
            }, 123)
        }
    }
    // Method to reset the playback position to ensure it starts fresh
    private fun resetPlaybackPosition() {
        StreamResultReceiver.lastKnownPosition = 0L
        Log.d("Stubio", "resetPlaybackPosition() -> lastKnownPosition reset to 0")
    }

    /* ************************************************ /
       Broadcast Receivers
     / ************************************************ */

    // Stream BroadcastReceiver to capture playback position
    class StreamResultReceiver : BroadcastReceiver() {
        companion object {
            var lastKnownPosition: Long = 0L
            var lastKnownDuration: Long = 0L
            fun fetchLastKnownPosition(): Long = lastKnownPosition
        }

        override fun onReceive(context: Context, intent: Intent) {
            Log.d("Stubio", "StreamResultReceiver onReceive -> action:${intent.action} extras:${intent.extras}")
            when (intent.action) {
                "$pkgP2P.player.result" -> {
                    val position = intent.getLongExtra("extra_position", -1L)
                    val duration = intent.getLongExtra("extra_duration", -1L)
                    Log.d("Stubio", "VLC broadcast -> position:$position duration:$duration")
                    if (position >= 0 && duration > 0) {
                        lastKnownPosition = position
                        lastKnownDuration = duration
                    }
                }
                "$pkgP2P.mxplayer.result" -> {
                    val position = intent.getLongExtra("position", -1L)
                    val duration = intent.getLongExtra("duration", -1L)
                    Log.d("Stubio", "MX broadcast -> position:$position duration:$duration")
                    if (position >= 0 && duration > 0) {
                        lastKnownPosition = position
                        lastKnownDuration = duration
                    }
                }
                else -> {
                    Log.w("Stubio", "Unknown broadcast action: ${intent.action}")
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
                addAction("$pkgP2P.player.result")   // VLC
                addAction("$pkgP2P.mxplayer.result") // MX
            }
            registerReceiver(streamReceiver, filter)
            isStreamReceiverRegistered = true
            Log.d("Stubio", "StreamResultReceiver registered")
        }
    }
    // Handle Activity Result on Older Devices
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d("Stubio", "onActivityResult -> requestCode:$requestCode resultCode:$resultCode data:$data")
        if (requestCode == REQUEST_CODE_PLAYBACK && resultCode == RESULT_OK) {
            data?.let { resultData ->
                val position = resultData.getLongExtra("extra_position", 0L)
                Log.d("Stubio", "onActivityResult -> Got position:$position from external player")
                sendPlaybackPositionToStremio(this, position, 0L)
            }
        }
        Log.d("Stubio", "onActivityResult() -> finishing, sent sendPlaybackPositionToStremio()")
        // Now that external player is done or user is back, close stub
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            finish()
        }, 2222)  // Give Stremio ~2s to process, it can be slow to reload
    }

    /* ************************************************ /
       Caching - Minimise repeated I/O operations
     / ************************************************ */
    // Cache user config address, ensure only one instance is used throughout
    private fun getStoredStremioServer(): String {
        return cachedStremioServer ?: run {
            val sharedPref = getSharedPreferences("StubioPrefs", Context.MODE_PRIVATE)
            val ip = sharedPref.getString("stremio_server_ip", "127.0.0.1").also {
                cachedStremioServer = it
            } ?: "127.0.0.1"
            Log.d("Stubio", "getStoredStremioServer() -> $ip")
            ip
        }
    }
    // Update domain/IP with user-configured Stremio server address
    class ServerConfigReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val serverIp = intent.getStringExtra("server_ip") ?: return
            val sharedPref = context.getSharedPreferences("StubioPrefs", Context.MODE_PRIVATE)
            sharedPref.edit().putString("stremio_server_ip", serverIp).apply()
            Log.d("Stubio", "ServerConfigReceiver -> Stremio server IP updated to: $serverIp")
            Toast.makeText(context, "DEBUG: Updated Stremio server IP to: $serverIp", Toast.LENGTH_SHORT).show()
        }
    }

    /* ************************************************ /
       Foreground Service Management
     / ************************************************ */
    private fun stopForegroundServiceIfNeeded() {
        if (!isMonitoringPlayback) {
            Log.d("Stubio", "stopForegroundServiceIfNeeded() -> Stopping PlaybackForegroundService")
            stopService(Intent(this, PlaybackForegroundService::class.java))
        } else {
            Log.d("Stubio", "stopForegroundServiceIfNeeded() -> Still monitoring, won't stop service.")
        }
    }
    // Keep alive for polling playback time
    class PlaybackForegroundService : Service() {
        override fun onCreate() {
            super.onCreate()
            Log.d("Stubio", "PlaybackForegroundService -> onCreate")
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
            // NOTE: Make sure you have an ic_notification.png in drawable, or change icon
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
