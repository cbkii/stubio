package com.intentrouter.stubio

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SetupActivity : AppCompatActivity() {

    private lateinit var editStreamPrimary: EditText
    private lateinit var editStreamFallback: EditText
    private lateinit var editTrailerPrimary: EditText
    private lateinit var editTrailerFallback: EditText
    private lateinit var editAdditionalAllowedHosts: EditText

    private lateinit var btnPickStreamPrimary: ImageButton
    private lateinit var btnPickStreamFallback: ImageButton
    private lateinit var btnPickTrailerPrimary: ImageButton
    private lateinit var btnPickTrailerFallback: ImageButton

    private lateinit var btnSave: Button
    private var cachedLaunchableApps: List<LaunchableApp>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        title = getString(R.string.setup_title)

        editStreamPrimary = findViewById(R.id.editStreamPrimary)
        editStreamFallback = findViewById(R.id.editStreamFallback)
        editTrailerPrimary = findViewById(R.id.editTrailerPrimary)
        editTrailerFallback = findViewById(R.id.editTrailerFallback)
        editAdditionalAllowedHosts = findViewById(R.id.editAdditionalAllowedHosts)

        btnPickStreamPrimary = findViewById(R.id.btnPickStreamPrimary)
        btnPickStreamFallback = findViewById(R.id.btnPickStreamFallback)
        btnPickTrailerPrimary = findViewById(R.id.btnPickTrailerPrimary)
        btnPickTrailerFallback = findViewById(R.id.btnPickTrailerFallback)

        btnSave = findViewById(R.id.btnSave)

        loadSavedSettings()

        btnPickStreamPrimary.setOnClickListener { showAppPicker(editStreamPrimary) }
        btnPickStreamFallback.setOnClickListener { showAppPicker(editStreamFallback) }
        btnPickTrailerPrimary.setOnClickListener { showAppPicker(editTrailerPrimary) }
        btnPickTrailerFallback.setOnClickListener { showAppPicker(editTrailerFallback) }

        btnSave.setOnClickListener { saveSettings() }
        btnSave.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                saveSettings()
                true
            } else {
                false
            }
        }

        editStreamPrimary.requestFocus()
    }

    private fun showAppPicker(targetField: EditText) {
        // Disable all picker buttons while loading to prevent double-tap on slow hardware.
        setPickerButtonsEnabled(false)

        lifecycleScope.launch {
            val apps = cachedLaunchableApps ?: withContext(Dispatchers.IO) {
                loadLaunchableApps()
            }.also { cachedLaunchableApps = it }

            setPickerButtonsEnabled(true)

            if (isFinishing) return@launch

            if (apps.isEmpty()) {
                Toast.makeText(this@SetupActivity, R.string.no_apps_found, Toast.LENGTH_SHORT).show()
                return@launch
            }

            showAppPickerDialog(targetField, apps)
        }
    }

    private fun showAppPickerDialog(targetField: EditText, apps: List<LaunchableApp>) {
        val currentPackageName = targetField.text.toString().trim()
        val initialSelection = apps.indexOfFirst { it.packageName == currentPackageName }

        // Battle-tested platform pattern: single-choice list dialog that reports selected index
        // through builder callback. This avoids custom ListView click/focus edge-cases.
        val labels = apps.map { app ->
            "${app.appName}\n${app.packageName}"
        }.toTypedArray()

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.app_picker_title)
            .setSingleChoiceItems(labels, initialSelection) { d, which ->
                if (which in apps.indices) {
                    selectAppPackage(targetField, apps[which].packageName)
                    d.dismiss()
                }
            }
            .setNegativeButton(R.string.app_picker_close, null)
            .create()

        dialog.show()
        val listView = dialog.listView
        listView.post {
            listView.requestFocus()
            if (initialSelection >= 0) {
                listView.setSelection(initialSelection)
            } else {
                listView.setSelection(0)
            }
        }
    }

    private fun selectAppPackage(targetField: EditText, packageName: String) {
        targetField.setText(packageName)
        targetField.setSelection(packageName.length)
        targetField.requestFocus()
    }

    private fun setPickerButtonsEnabled(enabled: Boolean) {
        btnPickStreamPrimary.isEnabled = enabled
        btnPickStreamFallback.isEnabled = enabled
        btnPickTrailerPrimary.isEnabled = enabled
        btnPickTrailerFallback.isEnabled = enabled
    }

    private fun loadLaunchableApps(): List<LaunchableApp> {
        val pm = packageManager

        // Query both standard LAUNCHER and TV LEANBACK_LAUNCHER so that all installed
        // apps (phone apps, TV-only apps, and apps supporting both) are included.
        // Use flags=0 (not MATCH_DEFAULT_ONLY) because launcher activities are not
        // required to also declare CATEGORY_DEFAULT.
        val standardIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val leanbackIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)

        // Use a LinkedHashMap keyed by package name to deduplicate.
        val byPackage = LinkedHashMap<String, android.content.pm.ResolveInfo>()
        for (ri in queryLauncherActivities(pm, standardIntent)) byPackage[ri.activityInfo.packageName] = ri
        for (ri in queryLauncherActivities(pm, leanbackIntent)) byPackage.putIfAbsent(ri.activityInfo.packageName, ri)

        return byPackage.values
            .map {
                LaunchableApp(
                    appName = it.loadLabel(pm)?.toString().orEmpty().ifBlank { it.activityInfo.packageName },
                    packageName = it.activityInfo.packageName
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.appName })
    }

    private fun loadSavedSettings() {
        val sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        editStreamPrimary.setText(sp.getString(KEY_STREAM_PRIMARY, ""))
        editStreamFallback.setText(sp.getString(KEY_STREAM_FALLBACK, ""))
        editTrailerPrimary.setText(sp.getString(KEY_TRAILER_PRIMARY, ""))
        editTrailerFallback.setText(sp.getString(KEY_TRAILER_FALLBACK, ""))
        editAdditionalAllowedHosts.setText(sp.getString(KEY_ADDITIONAL_ALLOWED_HOSTS, ""))
    }

    private fun saveSettings() {
        val invalidField = listOf(editStreamPrimary, editStreamFallback, editTrailerPrimary, editTrailerFallback)
            .firstOrNull { field ->
                val value = field.text.toString().trim()
                value.isNotEmpty() && !value.matches(PACKAGE_PATTERN)
            }

        if (invalidField != null) {
            invalidField.requestFocus()
            Toast.makeText(this, R.string.invalid_package_name, Toast.LENGTH_LONG).show()
            return
        }

        val sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit()
            .putString(KEY_STREAM_PRIMARY, editStreamPrimary.text.toString().trim())
            .putString(KEY_STREAM_FALLBACK, editStreamFallback.text.toString().trim())
            .putString(KEY_TRAILER_PRIMARY, editTrailerPrimary.text.toString().trim())
            .putString(KEY_TRAILER_FALLBACK, editTrailerFallback.text.toString().trim())
            .putString(KEY_ADDITIONAL_ALLOWED_HOSTS, editAdditionalAllowedHosts.text.toString().trim())
            .apply()

        Toast.makeText(this, R.string.saved_confirmation, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val PREFS_NAME = "StubioPrefs"
        const val KEY_STREAM_PRIMARY = "stream_player_primary"
        const val KEY_STREAM_FALLBACK = "stream_player_fallback"
        const val KEY_TRAILER_PRIMARY = "trailer_player_primary"
        const val KEY_TRAILER_FALLBACK = "trailer_player_fallback"
        const val KEY_ADDITIONAL_ALLOWED_HOSTS = "additional_allowed_hosts"

        private val PACKAGE_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")
    }
}

// Launcher categories (LAUNCHER / LEANBACK_LAUNCHER) don't require CATEGORY_DEFAULT,
// so use flags=0 to avoid filtering out apps that omit that category.
private fun queryLauncherActivities(pm: PackageManager, intent: Intent): List<android.content.pm.ResolveInfo> {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, 0)
    }
}

private data class LaunchableApp(
    val appName: String,
    val packageName: String
)
