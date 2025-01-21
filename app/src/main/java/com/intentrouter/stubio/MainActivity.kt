package com.intentrouter.stubio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var vlcResultLauncher: ActivityResultLauncher<Intent>
    private val handler = Handler(Looper.getMainLooper())

    // Runnable to periodically check playback position and send it to Stremio
    private val positionRunnable = object : Runnable {
        override fun run() {
            val currentPosition = getCurrentPlaybackPosition()
            sendPlaybackPositionToStremio(this@MainActivity, currentPosition, 0L)
            handler.postDelayed(this, 10000) // 10 seconds loop
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
        handler.post(positionRunnable)

        // Load Stremio server URL from SharedPreferences (Default stored in res/xml/stubio_user_settings.xml)
        val sharedPref = getSharedPreferences("StubioPrefs", Context.MODE_PRIVATE)
        // Apply default values if not already set
        androidx.preference.PreferenceManager.setDefaultValues(this, R.xml.stubio_user_settings, false)

        val stremioServer = sharedPref.getString("stremio_server_ip", "127.0.0.1")
        // Potential future routing usage: val stremioUrl = "http://$stremioServer/stream"

        // Register broadcast receiver for ADB/Tasker updates:
            // $ adb shell am broadcast -a com.intentrouter.stubio.SET_SERVER --es server_ip "192.168.1.100"
            // Tasker>System>SendIntent: Action: com.intentrouter.stubio.SET_SERVER, Extra: server_ip:192.168.1.100
        val filter = IntentFilter("com.intentrouter.stubio.SET_SERVER")
        registerReceiver(ServerConfigReceiver(), filter)

        // Setup playback position parser
        vlcResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.let { data ->
                    val position = data.getLongExtra("extra_position", 0L)
                    val duration = data.getLongExtra("extra_duration", 0L)
                    sendPlaybackPositionToStremio(this, position, duration)
                }
            }
        }

        val incomingIntent = intent
        val incomingUri: Uri? = incomingIntent.data

        if (incomingUri != null) {
            routeUri(incomingUri, incomingIntent)
        }

        val filter = IntentFilter("org.videolan.vlc.player.result")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(VLCResultReceiver(), filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(VLCResultReceiver(), filter)
        }

        finish()
    }
    // Determines which media player to launch based on parsed incoming URI
    private fun routeUri(uri: Uri, intent: Intent) {
        val youtubeRegex = """(?:/yt/|[/?=&])([a-zA-Z0-9_-]{11})(?=[/?&=#]|\$)""".toRegex()
        val matchResult = youtubeRegex.find(uri.toString())

        if (matchResult != null) {
            val youtubeId = matchResult.groupValues[1]
            val youtubeUrl = "https://www.youtube.com/watch?v=\$youtubeId"
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

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(positionRunnable)
        unregisterReceiver(VLCResultReceiver())
    }
    // BroadcastReceiver to receive playback position from VLC
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
                    data = Uri.parse("stremio://playback?position=\$position&duration=\$duration")
                    setPackage("com.stremio.one")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(returnIntent)
            } catch (e: Exception) {
                e.printStackTrace()
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

    private fun getCurrentPlaybackPosition(): Long {
        return 0L
    }
}
