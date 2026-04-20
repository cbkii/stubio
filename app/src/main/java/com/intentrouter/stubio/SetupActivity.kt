package com.intentrouter.stubio

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
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
    private var cachedUserInstalledApps: List<InstalledApp>? = null

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
            val apps = cachedUserInstalledApps ?: withContext(Dispatchers.IO) {
                loadUserInstalledApps()
            }.also { cachedUserInstalledApps = it }

            setPickerButtonsEnabled(true)

            if (isFinishing) return@launch

            if (apps.isEmpty()) {
                Toast.makeText(this@SetupActivity, R.string.no_apps_found, Toast.LENGTH_SHORT).show()
                return@launch
            }

            showAppPickerDialog(targetField, apps)
        }
    }

    private fun showAppPickerDialog(targetField: EditText, apps: List<InstalledApp>) {
        val currentPackageName = targetField.text.toString().trim()
        val initialSelection = apps.indexOfFirst { it.packageName == currentPackageName }

        // Keep each list row in a compact single line so TV focus/selection are unambiguous.
        val labels = apps.map { app ->
            "${app.appName} (${app.packageName})"
        }.toTypedArray()

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.app_picker_title)
            .setSingleChoiceItems(labels, initialSelection, null)
            .setNegativeButton(R.string.app_picker_close, null)
            .create()

        dialog.show()
        dialog.listView?.post {
            dialog.listView?.let { listView ->
                listView.choiceMode = android.widget.ListView.CHOICE_MODE_SINGLE

                val commitSelection: (Int) -> Unit = { selectedIndex ->
                    if (selectedIndex in apps.indices) {
                        selectAppPackage(targetField, apps[selectedIndex].packageName)
                        dialog.dismiss()
                    }
                }

                listView.setOnItemClickListener { _, _, position, _ ->
                    commitSelection(position)
                }

                listView.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?,
                        view: android.view.View?,
                        position: Int,
                        id: Long
                    ) {
                        listView.setItemChecked(position, true)
                    }

                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                })

                // Explicitly handle TV remote OK/Enter keys against currently selected row.
                // Handle on ACTION_UP so the selection state has already settled after DPAD navigation.
                listView.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_UP &&
                        (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                    ) {
                        val selectedIndex = listView.checkedItemPosition.takeIf { it >= 0 }
                            ?: listView.selectedItemPosition
                        if (selectedIndex >= 0) {
                            commitSelection(selectedIndex)
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }

                listView.requestFocus()
                if (initialSelection >= 0) {
                    listView.setItemChecked(initialSelection, true)
                    listView.setSelection(initialSelection)
                } else {
                    listView.setItemChecked(0, true)
                    listView.setSelection(0)
                }
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

    private fun loadUserInstalledApps(): List<InstalledApp> {
        val pm = packageManager

        // Include all user-installed apps, not only those exposing launcher activities.
        // This supports TV/mobile/other app types consistently in setup.
        return queryInstalledApplications(pm)
            .asSequence()
            .filter { shouldIncludeAppInPicker(pm, it) }
            .map {
                InstalledApp(
                    appName = it.loadLabel(pm)?.toString().orEmpty().ifBlank { it.packageName },
                    packageName = it.packageName
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.appName })
            .toList()
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

private fun queryInstalledApplications(pm: PackageManager): List<ApplicationInfo> {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
    } else {
        @Suppress("DEPRECATION")
        pm.getInstalledApplications(0)
    }
}

private fun isUserInstalledApp(appInfo: ApplicationInfo): Boolean {
    val isSystemApp = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
    val isUpdatedSystemApp = appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
    return !isSystemApp || isUpdatedSystemApp
}

private fun shouldIncludeAppInPicker(pm: PackageManager, appInfo: ApplicationInfo): Boolean {
    if (isUserInstalledApp(appInfo)) return true
    val packageName = appInfo.packageName
    return hasLauncherEntryPoint(pm, packageName) ||
        handlesVideoIntent(pm, packageName) ||
        handlesYoutubeIntent(pm, packageName)
}

private fun hasLauncherEntryPoint(pm: PackageManager, packageName: String): Boolean {
    return pm.getLaunchIntentForPackage(packageName) != null ||
        pm.getLeanbackLaunchIntentForPackage(packageName) != null
}

private fun handlesVideoIntent(pm: PackageManager, packageName: String): Boolean {
    val videoIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse("http://127.0.0.1/stubio-test.mp4"), "video/*")
        setPackage(packageName)
    }
    return videoIntent.resolveActivity(pm) != null
}

private fun handlesYoutubeIntent(pm: PackageManager, packageName: String): Boolean {
    val youtubeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ")).apply {
        setPackage(packageName)
    }
    return youtubeIntent.resolveActivity(pm) != null
}

private data class InstalledApp(
    val appName: String,
    val packageName: String
)
