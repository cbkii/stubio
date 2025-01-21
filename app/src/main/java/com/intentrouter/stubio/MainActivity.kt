package com.intentrouter.stubio

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var vlcResultLauncher: ActivityResultLauncher<Intent>
    private val coroutineScope = CoroutineScope(Dispatchers.Main.immediate)

    // Runnable to periodically check playback position and send it to Stremio
    private fun monitorPlaybackPosition() = lifecycleScope.launch(Dispatchers.IO) {
        while (isActive) {
            val currentPosition = getCurrentPlaybackPosition()
            withContext(Dispatchers.Main) {
                sendPlaybackPositionToStremio(this@MainActivity, currentPosition, 0L)
            }
            delay(10000L) // Poll every 10 seconds
        }
    }

    private val pkgYT: String by lazy {
        if (packageManager.getLaunchIntentForPackage("com.teamsmart.videomanager.tv") != null) {
            "com.teamsmart.videomanager.tv"
        } else {
            "com.google.android.youtube"
        }
    }

    private val pkgP2P: String by lazy {
        if (packageManager.getLaunchIntentForPackage("org.videolan.vlc") != null) {
            "org.videolan.vlc"
        } else {
            "com.mxtech.videoplayer.ad"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            monitorPlaybackPosition()
        }

        // Keep alive as foreground service, prevent aggressive Android10+ app kill
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, PlaybackForegroundService::class.java))
        } else {
            startService(Intent(this, PlaybackForegroundService::class.java))
        }
        // Stream playback position return handler
        monitorPlaybackPosition()

        // Setup playback position parser
        vlcResultLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    result.data?.let { data ->
                        val position = data.getLongExtra("extra_position", 0L)
                        val duration = data.getLongExtra("extra_duration", 0L)
                        sendPlaybackPositionToStremio(this, position, duration)
                    }
                }
            }

        // Set Incoming Intent, else end
        val incomingIntent = intent
        val incomingUri: Uri? = incomingIntent.data
        incomingUri?.host?.let { host ->
            if (isAllowedHost(host)) {
                routeUri(incomingUri, incomingIntent)
            } else {
                finish()
            }
        }

        val filter = IntentFilter("org.videolan.vlc.player.result")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(VLCResultReceiver(), filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(VLCResultReceiver(), filter)
        }

        finish()
    }
    // Parse and filter hosts dynamically to ensure they match valid hosts
    private fun isAllowedHost(host: String?): Boolean {
        val allowedHostPatterns = listOf(
            "127\\.0\\.0\\.1",
            "localhost",
            "192\\.168\\.\\d{1,3}\\.\\d{1,3}",
            "10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}",
            "172\\.16\\.\\d{1,3}\\.\\d{1,3}",
            "172\\.31\\.\\d{1,3}\\.\\d{1,3}",
            ".*\\.stremio\\.com",
            ".*\\.strem\\.io"
        )
        return allowedHostPatterns.any { pattern -> host?.matches(pattern.toRegex()) == true } || host == getStoredStremioServer()
    }
    private fun getStoredStremioServer(): String? {
        val sharedPref = getSharedPreferences("StubioPrefs", Context.MODE_PRIVATE)
        return sharedPref.getString("stremio_server_ip", "127.0.0.1")
    }

    // Determines which media player to launch based on parsed incoming URI
    private fun routeUri(uri: Uri, intent: Intent) {
        val youtubeRegex = """(?:/yt/|[/?=&])([a-zA-Z0-9_-]{11})(?=[/?&=#]|$)""".toRegex()
        val matchResult = youtubeRegex.find(uri.toString())

        if (matchResult != null) {
            val youtubeId = matchResult.groupValues[1]
            val youtubeUrl = "https://www.youtube.com/watch?v=$youtubeId"
            launchWithYTapp(youtubeUrl)
        } else {
            launchWithStreamApp(intent)
        }
    }

    // Prepares and launches SmartTubeNext with a given YouTube URL
    private fun launchWithYTapp(youtubeUrl: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(youtubeUrl)
                setPackage(pkgYT)
                // Check Stubio started with FORWARD_RESULT, else launch independent
                if (intent.flags and Intent.FLAG_ACTIVITY_FORWARD_RESULT != 0) {
                    addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT or Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP)
                } else {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                putExtra("return_result", true)
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Prepares and launches VLC or MX Player with the full original intent and extras
    private fun launchWithStreamApp(originalIntent: Intent) {
        try {
            val playerIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(originalIntent.data, "video/*")
                setPackage(pkgP2P)
                putExtras(originalIntent.extras ?: Bundle())
                putExtra("startfrom", originalIntent.getIntExtra("startfrom", 0))
                putExtra("position", originalIntent.getIntExtra("position", 0))
                putExtra("return_result", true)
                // Note // Test these and other flags until behaviour is consistent with SoR
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NO_HISTORY)
                // FLAG_GRANT_READ_URI_PERMISSION ensures Stubio can access content URIs shared by Stremio
                // FLAG_ACTIVITY_NO_HISTORY prevents the activity from being stored in the recent tasks list
            }
            if (playerIntent.resolveActivity(packageManager) != null) {
                vlcResultLauncher.launch(playerIntent)
            } else {
                playerIntent.setPackage("com.mxtech.videoplayer.ad")
                if (playerIntent.resolveActivity(packageManager) != null) {
                    vlcResultLauncher.launch(playerIntent)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // BroadcastReceiver to receive playback position from VLC
    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        unregisterReceiver(VLCResultReceiver())
    }

    class VLCResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "org.videolan.vlc.player.result") {
                val position = intent.getLongExtra("extra_position", 0L)
                val duration = intent.getLongExtra("extra_duration", 0L)
                sendPlaybackPositionToStremio(context, position, duration)
            }
        }
    }

    // Function to relay the playback position back to Stremio [com.stremio.one]
    companion object {
        fun sendPlaybackPositionToStremio(context: Context, position: Long, duration: Long) {
            try {
                val returnIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("stremio://playback?position=$position&duration=$duration")
                    setPackage("com.stremio.one")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(returnIntent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getCurrentPlaybackPosition(): Long {
        return 0L
    }

    class PlaybackForegroundService : Service() {
        override fun onCreate() {
            super.onCreate()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                createNotificationChannel()
            }
            val notification =
                NotificationCompat.Builder(this@PlaybackForegroundService, "STUBIO_CHANNEL")
                    .setContentTitle("Stubio Service")
                    .setContentText("Playback position handler")
                    .setSmallIcon(R.drawable.ic_notification)
                    .build()
            startForeground(1, notification)
        }

        override fun onBind(intent: Intent?): IBinder? = null

        override fun onDestroy() {
            stopForeground(true)
            super.onDestroy()
        }

        // Function to create notification channel with fallback for API 23+
        @SuppressLint("WrongConstant")
        private fun createNotificationChannel() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    "STUBIO_CHANNEL",
                    "Playback Position Service for Stremio",
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) NotificationManager.IMPORTANCE_LOW else Notification.PRIORITY_LOW
                )
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(channel)
            } else {
                // Fallback for Android 6 (API 23)
                val notification = NotificationCompat.Builder(this, "STUBIO_CHANNEL")
                    .setContentTitle("Stubio Service")
                    .setContentText("Playback position handler")
                    .setSmallIcon(R.drawable.ic_notification)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(1, notification)
            }
        }

    }

    // Update domain/IP with user configured Stremio server address
    class ServerConfigReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val serverIp = intent.getStringExtra("server_ip") ?: return
            val sharedPref = context.getSharedPreferences("StubioPrefs", Context.MODE_PRIVATE)
            sharedPref.edit().putString("stremio_server_ip", serverIp).apply()
            // Log.d("Stubio", "Stremio server IP updated to: $serverIp")
        }
    }
}
