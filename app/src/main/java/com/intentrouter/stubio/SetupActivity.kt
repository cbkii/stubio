package com.intentrouter.stubio

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * SetupActivity — launched when the user opens Stubio from the home screen launcher icon.
 *
 * Allows the user to configure preferred package names for:
 *   - Stream player (primary + fallback, default fallback: VLC)
 *   - Trailer player (primary + fallback, default fallback: SmartTube)
 *
 * Settings are persisted in "StubioPrefs" SharedPreferences and are picked up
 * by MainActivity when routing video intents. Blank fields are silently skipped
 * and the next non-blank / default-installed package is used instead.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var editStreamPrimary: EditText
    private lateinit var editStreamFallback: EditText
    private lateinit var editTrailerPrimary: EditText
    private lateinit var editTrailerFallback: EditText
    private lateinit var btnSave: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        editStreamPrimary  = findViewById(R.id.editStreamPrimary)
        editStreamFallback = findViewById(R.id.editStreamFallback)
        editTrailerPrimary  = findViewById(R.id.editTrailerPrimary)
        editTrailerFallback = findViewById(R.id.editTrailerFallback)
        btnSave             = findViewById(R.id.btnSave)

        loadSavedSettings()

        btnSave.setOnClickListener { saveSettings() }
    }

    // region -- Load / Save

    private fun loadSavedSettings() {
        val sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        editStreamPrimary.setText(sp.getString(KEY_STREAM_PRIMARY, ""))
        editStreamFallback.setText(sp.getString(KEY_STREAM_FALLBACK, ""))
        editTrailerPrimary.setText(sp.getString(KEY_TRAILER_PRIMARY, ""))
        editTrailerFallback.setText(sp.getString(KEY_TRAILER_FALLBACK, ""))
    }

    private fun saveSettings() {
        val sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit()
            .putString(KEY_STREAM_PRIMARY,  editStreamPrimary.text.toString().trim())
            .putString(KEY_STREAM_FALLBACK, editStreamFallback.text.toString().trim())
            .putString(KEY_TRAILER_PRIMARY,  editTrailerPrimary.text.toString().trim())
            .putString(KEY_TRAILER_FALLBACK, editTrailerFallback.text.toString().trim())
            .apply()

        Toast.makeText(this, R.string.saved_confirmation, Toast.LENGTH_SHORT).show()
    }

    // endregion

    companion object {
        const val PREFS_NAME = "StubioPrefs"

        const val KEY_STREAM_PRIMARY  = "stream_player_primary"
        const val KEY_STREAM_FALLBACK = "stream_player_fallback"
        const val KEY_TRAILER_PRIMARY  = "trailer_player_primary"
        const val KEY_TRAILER_FALLBACK = "trailer_player_fallback"
    }
}
