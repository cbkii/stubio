package com.intentrouter.stubio

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Stubio"

        private const val YT_SMARTTUBE = "com.teamsmart.videomanager.tv"
        private const val YT_OFFICIAL = "com.google.android.youtube"
        private const val PLAYER_VLC = "org.videolan.vlc"
        private const val PLAYER_MX = "com.mxtech.videoplayer.ad"

        private const val PREF_LAST_STREAM = "last_playback_stream"
        private const val PREF_LAST_POSITION = "last_playback_position"
        private const val PREF_LAST_DURATION = "last_playback_duration"
        private const val PREF_STREMIO_SERVER_IP = "stremio_server_ip"
        private const val LOOPBACK_HOST = "127.0.0.1"
        private val ALLOWED_HOST_REGEX = Regex(
            "^(?:localhost|192\\.168\\.[0-9]+\\.[0-9]+|10\\.[0-9]+\\.[0-9]+\\.[0-9]+|172\\.(1[6-9]|2[0-9]|3[0-1])\\.[0-9]+\\.[0-9]+|[a-zA-Z0-9.-]+\\.stremio\\.com|[a-zA-Z0-9.-]+\\.strem\\.io)$"
        )
        private val YOUTUBE_PATH_REGEX = Regex("/yt/([A-Za-z0-9_-]{11})")

        internal fun normalizeHost(value: String): String =
            value.trim().removeSurrounding("[", "]").lowercase()

        internal fun parseAdditionalAllowedHosts(rawValue: String?): Set<String> {
            if (rawValue.isNullOrBlank()) return emptySet()
            return rawValue.split(",")
                .asSequence()
                .map { normalizeHost(it) }
                .filter { it.isNotEmpty() }
                .toSet()
        }

        internal fun isAllowedHost(host: String, storedStremioServer: String, additionalAllowedHosts: Set<String>): Boolean {
            val h = normalizeHost(host)
            return h == LOOPBACK_HOST ||
                h == storedStremioServer ||
                h in additionalAllowedHosts ||
                h.matches(ALLOWED_HOST_REGEX)
        }

        fun sendPlaybackPositionToStremio(context: Context, position: Long, duration: Long) {
            if (position < 0 || duration <= 0) return
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("stremio://playback?position=$position&duration=$duration")
            ).apply {
                setPackage("com.stremio.one")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }

            runCatching { context.startActivity(intent) }
                .onFailure { if (BuildConfig.DEBUG) Log.w(TAG, "Unable to report position to Stremio", it) }
        }
    }

    private var selectedTrailerPackage: String = YT_OFFICIAL
    private var selectedStreamPackage: String = PLAYER_VLC
    private var selectedPlayerIsVlc: Boolean = true

    private var lastKnownPosition: Long = 0L
    private var lastKnownDuration: Long = 0L
    private var currentStreamUrl: String? = null
    private var cachedStremioServer: String = LOOPBACK_HOST
    private var cachedAdditionalAllowedHosts: Set<String> = emptySet()

    private var streamReceiver: StreamResultReceiver? = null
    private var streamReceiverRegistered = false

    private val streamResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        handleExternalPlayerResult(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadPlayerPackages()
        loadAllowedHosts()
        restoreCachedPlaybackData()
        registerStreamReceiver()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onDestroy() {
        unregisterStreamReceiver()
        super.onDestroy()
    }

    private fun handleIncomingIntent(incomingIntent: Intent) {
        val uri = incomingIntent.data
        if (uri == null) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        if (!isAllowedUri(uri)) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Blocked unsupported uri: $uri")
            finish()
            return
        }

        val newStreamUrl = uri.toString()
        if (newStreamUrl != currentStreamUrl) {
            resetPlaybackTracking()
            currentStreamUrl = newStreamUrl
            storeCachedPlaybackData()
        }

        routeUri(uri, incomingIntent)
    }

    private fun isAllowedUri(uri: Uri): Boolean {
        if (uri.scheme.equals("stremio", ignoreCase = true)) return true

        val host = uri.host ?: return false
        return isAllowedHost(host, cachedStremioServer, cachedAdditionalAllowedHosts)
    }

    private fun routeUri(uri: Uri, originalIntent: Intent) {
        val match = YOUTUBE_PATH_REGEX.find(uri.toString())
        if (match != null) {
            launchYouTube("https://www.youtube.com/watch?v=${match.groupValues[1]}")
        } else {
            launchStreamPlayer(originalIntent)
        }
    }

    private fun launchYouTube(youtubeUrl: String) {
        val ytIntent = Intent(Intent.ACTION_VIEW, Uri.parse(youtubeUrl)).apply {
            setPackage(selectedTrailerPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        if (ytIntent.resolveActivity(packageManager) != null) {
            runCatching { startActivity(ytIntent) }
        }
        finish()
    }

    private fun launchStreamPlayer(originalIntent: Intent) {
        val streamUri = originalIntent.data ?: run {
            finish()
            return
        }

        val playerIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(streamUri, "video/*")
            setPackage(selectedStreamPackage)
            putExtras(originalIntent.extras ?: Bundle())
            if (selectedPlayerIsVlc) {
                putExtra("extra_position", lastKnownPosition)
                putExtra("extra_duration", lastKnownDuration)
            } else {
                putExtra("position", lastKnownPosition)
                putExtra("duration", lastKnownDuration)
            }
            putExtra("return_result", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        if (playerIntent.resolveActivity(packageManager) != null) {
            runCatching { streamResultLauncher.launch(playerIntent) }
                .onFailure {
                    if (BuildConfig.DEBUG) Log.e(TAG, "Unable to launch stream player", it)
                    finish()
                }
        } else {
            finish()
        }
    }

    private fun handleExternalPlayerResult(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK || resultCode == Activity.RESULT_CANCELED) {
            data?.extras?.let { extras ->
                val position = if (selectedPlayerIsVlc) extras.getLong("extra_position", -1L) else extras.getLong("position", -1L)
                val duration = if (selectedPlayerIsVlc) extras.getLong("extra_duration", -1L) else extras.getLong("duration", -1L)
                if (position >= 0 && duration > 0) {
                    updatePlaybackPosition(position, duration)
                }
            }
            wipePlaybackData()
        }

        lifecycleScope.launch {
            delay(1000)
            finish()
        }
    }

    private fun updatePlaybackPosition(position: Long, duration: Long) {
        lastKnownPosition = position
        lastKnownDuration = duration
        storeCachedPlaybackData()
        sendPlaybackPositionToStremio(this, position, duration)
    }

    class StreamResultReceiver : BroadcastReceiver() {
        companion object {
            var lastKnownPosition: Long = 0L
            var lastKnownDuration: Long = 0L
        }

        override fun onReceive(context: Context, intent: Intent) {
            val isVlcResult = intent.action?.endsWith("player.result") == true
            val isMxResult = intent.action?.endsWith("mxplayer.result") == true
            if (!isVlcResult && !isMxResult) return

            val position = if (isVlcResult) intent.getLongExtra("extra_position", -1L) else intent.getLongExtra("position", -1L)
            val duration = if (isVlcResult) intent.getLongExtra("extra_duration", -1L) else intent.getLongExtra("duration", -1L)

            if (position >= 0 && duration > 0) {
                lastKnownPosition = position
                lastKnownDuration = duration
            }
        }
    }

    private fun registerStreamReceiver() {
        if (streamReceiverRegistered) return
        streamReceiver = StreamResultReceiver()
        val filter = IntentFilter().apply {
            if (selectedPlayerIsVlc) addAction("$selectedStreamPackage.player.result")
            else addAction("$selectedStreamPackage.mxplayer.result")
        }

        // RECEIVER_NOT_EXPORTED is inlined as a compile-time constant (value 0x4);
        // the 3-argument form of registerReceiver requires API 26+, safe with minSdk=28.
        registerReceiver(streamReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        streamReceiverRegistered = true
    }

    private fun unregisterStreamReceiver() {
        if (!streamReceiverRegistered || streamReceiver == null) return
        unregisterReceiver(streamReceiver)
        streamReceiverRegistered = false
    }

    private fun loadPlayerPackages() {
        val sp = getSharedPreferences(SetupActivity.PREFS_NAME, MODE_PRIVATE)

        val streamPrimary = sp.getString(SetupActivity.KEY_STREAM_PRIMARY, "")?.trim().orEmpty()
        val streamFallback = sp.getString(SetupActivity.KEY_STREAM_FALLBACK, "")?.trim().orEmpty()
        val trailerPrimary = sp.getString(SetupActivity.KEY_TRAILER_PRIMARY, "")?.trim().orEmpty()
        val trailerFallback = sp.getString(SetupActivity.KEY_TRAILER_FALLBACK, "")?.trim().orEmpty()

        selectedStreamPackage = listOf(streamPrimary, streamFallback, PLAYER_VLC, PLAYER_MX)
            .filter { it.isNotBlank() }
            .firstOrNull { isPackageInstalled(it) }
            ?: PLAYER_VLC

        selectedTrailerPackage = listOf(trailerPrimary, trailerFallback, YT_SMARTTUBE, YT_OFFICIAL)
            .filter { it.isNotBlank() }
            .firstOrNull { isPackageInstalled(it) }
            ?: YT_OFFICIAL

        selectedPlayerIsVlc = selectedStreamPackage == PLAYER_VLC
    }

    /**
     * Returns true if [packageName] exists on the device.
     *
     * Setup now allows selecting packages beyond launcher-entry apps, so routing should
     * validate against installation state rather than launcher categories.
     */
    private fun isPackageInstalled(packageName: String): Boolean {
        return runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    packageName,
                    android.content.pm.PackageManager.ApplicationInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
        }.isSuccess
    }

    private fun restoreCachedPlaybackData() {
        val sp = getSharedPreferences(SetupActivity.PREFS_NAME, MODE_PRIVATE)
        currentStreamUrl = sp.getString(PREF_LAST_STREAM, null)
        lastKnownPosition = maxOf(sp.getLong(PREF_LAST_POSITION, 0L), StreamResultReceiver.lastKnownPosition)
        lastKnownDuration = maxOf(sp.getLong(PREF_LAST_DURATION, 0L), StreamResultReceiver.lastKnownDuration)
    }

    private fun storeCachedPlaybackData() {
        getSharedPreferences(SetupActivity.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(PREF_LAST_STREAM, currentStreamUrl)
            .putLong(PREF_LAST_POSITION, lastKnownPosition)
            .putLong(PREF_LAST_DURATION, lastKnownDuration)
            .apply()
    }

    private fun resetPlaybackTracking() {
        lastKnownPosition = 0L
        lastKnownDuration = 0L
    }

    private fun wipePlaybackData() {
        lastKnownPosition = 0L
        lastKnownDuration = 0L
        currentStreamUrl = null
        // Also reset the broadcast-receiver statics so a stale value from a previous
        // session is never merged back in on the next restoreCachedPlaybackData() call.
        StreamResultReceiver.lastKnownPosition = 0L
        StreamResultReceiver.lastKnownDuration = 0L

        getSharedPreferences(SetupActivity.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .remove(PREF_LAST_STREAM)
            .remove(PREF_LAST_POSITION)
            .remove(PREF_LAST_DURATION)
            .apply()
    }

    private fun loadAllowedHosts() {
        val sp = getSharedPreferences(SetupActivity.PREFS_NAME, MODE_PRIVATE)
        cachedStremioServer = normalizeHost(
            sp.getString(PREF_STREMIO_SERVER_IP, LOOPBACK_HOST) ?: LOOPBACK_HOST
        )
        cachedAdditionalAllowedHosts = parseAdditionalAllowedHosts(
            sp.getString(SetupActivity.KEY_ADDITIONAL_ALLOWED_HOSTS, null)
        )
    }
}
