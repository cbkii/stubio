package com.intentrouter.stubio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

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

        val incomingIntent = intent
        val incomingUri: Uri? = incomingIntent.data

        // Process the incoming URI if it's not null
        if (incomingUri != null) {
            routeUri(incomingUri, incomingIntent)
        }

        // Register BroadcastReceiver to receive playback position from VLC
        val filter = IntentFilter("org.videolan.vlc.player.result")
        registerReceiver(VLCResultReceiver(), filter)

        // Close activity after processing the intent
        finish()
    }

    // Determines which media player to launch based on the incoming URI
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

                // Check if the stub app was started with FLAG_ACTIVITY_FORWARD_RESULT
                if (intent.flags and Intent.FLAG_ACTIVITY_FORWARD_RESULT != 0) {
                    // If Stremio expects a result, forward it back and maintain stack order
                    addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT or Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP)
                } else {
                    // Otherwise, launch SmartTubeNext as an independent task
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                // Ensure return result extra is set correctly
                putExtra("return_result", true)
            }

            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            }

        } catch (e: Exception) {
            // Handle exceptions related to launching SmartTubeNext
        }
    }

    // Prepares and launches VLC or MX Player with the full original intent and extras
    private fun launchWithStreamApp(originalIntent: Intent) {
        try {
            val playerIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(originalIntent.data, "video/*")
                setPackage(pkgP2P)
                putExtras(originalIntent.extras ?: Bundle())
                // Transfer essential extras, setting default values if they don't exist
                val extras = originalIntent.extras ?: Bundle()

                // Ensure "startfrom" is set, default to 0 if missing
                putExtra("startfrom", extras.getInt("startfrom", 0))
                // Ensure "position" is set, default to 0 if missing
                putExtra("position", extras.getInt("position", 0))
                // Ensure "return_result" is set, default to true if missing
                putExtra("return_result", extras.getBoolean("return_result", true))

                // Apply flags to maintain behavior given stub app intermediary
                if (originalIntent.flags and Intent.FLAG_ACTIVITY_FORWARD_RESULT != 0) {
                    addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT or Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP)
                } else {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            if (playerIntent.resolveActivity(packageManager) != null) {
                startActivity(playerIntent)
            } else {
                playerIntent.setPackage("com.mxtech.videoplayer.ad")
                if (playerIntent.resolveActivity(packageManager) != null) {
                    startActivity(playerIntent)
                }
            }
        } catch (e: Exception) {
            // Handle exceptions gracefully if the player fails to launch
        }
    }

    // VLC to return playback time position to Stremio
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.data?.let { incomingUri ->
            // Handle the new intent data as needed
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(VLCResultReceiver())
    }
    // BroadcastReceiver to receive playback position from VLC
    class VLCResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "org.videolan.vlc.player.result") {
                val position = intent.getLongExtra("extra_position", 0L)
                val duration = intent.getLongExtra("extra_duration", 0L)
                // Send the playback position back to Stremio via companion object
                MainActivity.sendPlaybackPositionToStremio(context, position, duration)
            }
        }
    }
    // Function to relay the playback position back to Stremio
    companion object {
        fun sendPlaybackPositionToStremio(context: Context, position: Long, duration: Long) {
            try {
                val returnIntent = Intent().apply {
                    action = Intent.ACTION_VIEW
                    data = Uri.parse("stremio://playback?position=$position&duration=$duration")
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                context.startActivity(returnIntent)
            } catch (e: Exception) {
                // Handle any issues in sending position back to Stremio
            }
        }
    }

}
