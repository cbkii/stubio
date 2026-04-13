package com.intentrouter.stubio

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SetupActivity : AppCompatActivity() {

    private lateinit var editStreamPrimary: EditText
    private lateinit var editStreamFallback: EditText
    private lateinit var editTrailerPrimary: EditText
    private lateinit var editTrailerFallback: EditText

    private lateinit var btnPickStreamPrimary: ImageButton
    private lateinit var btnPickStreamFallback: ImageButton
    private lateinit var btnPickTrailerPrimary: ImageButton
    private lateinit var btnPickTrailerFallback: ImageButton

    private lateinit var btnSave: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        title = getString(R.string.setup_title)

        editStreamPrimary = findViewById(R.id.editStreamPrimary)
        editStreamFallback = findViewById(R.id.editStreamFallback)
        editTrailerPrimary = findViewById(R.id.editTrailerPrimary)
        editTrailerFallback = findViewById(R.id.editTrailerFallback)

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
        val launchableApps = loadLaunchableApps()
        if (launchableApps.isEmpty()) {
            Toast.makeText(this, R.string.no_apps_found, Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_app_picker, null)
        val appGrid = dialogView.findViewById<GridView>(R.id.gridApps)
        val emptyText = dialogView.findViewById<TextView>(R.id.textNoApps)

        appGrid.adapter = LaunchableAppsAdapter(layoutInflater, launchableApps)
        appGrid.numColumns = GridView.AUTO_FIT
        appGrid.columnWidth = resources.getDimensionPixelSize(R.dimen.app_picker_tile_width)

        emptyText.visibility = View.GONE
        appGrid.visibility = View.VISIBLE

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.app_picker_title)
            .setView(dialogView)
            .setNegativeButton(R.string.app_picker_close, null)
            .create()

        appGrid.setOnItemClickListener { _, _, position, _ ->
            targetField.setText(launchableApps[position].packageName)
            dialog.dismiss()
        }

        dialog.show()
        appGrid.post { appGrid.requestFocus() }
    }

    private fun loadLaunchableApps(): List<LaunchableApp> {
        val pm = packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val launcherApps = queryActivities(pm, launcherIntent)

        return launcherApps
            .distinctBy { it.activityInfo.packageName }
            .map {
                LaunchableApp(
                    appName = it.loadLabel(pm)?.toString().orEmpty().ifBlank { it.activityInfo.packageName },
                    packageName = it.activityInfo.packageName,
                    icon = it.loadIcon(pm)
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
            .apply()

        Toast.makeText(this, R.string.saved_confirmation, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val PREFS_NAME = "StubioPrefs"
        const val KEY_STREAM_PRIMARY = "stream_player_primary"
        const val KEY_STREAM_FALLBACK = "stream_player_fallback"
        const val KEY_TRAILER_PRIMARY = "trailer_player_primary"
        const val KEY_TRAILER_FALLBACK = "trailer_player_fallback"

        private val PACKAGE_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")
    }
}

private fun queryActivities(pm: PackageManager, intent: Intent): List<android.content.pm.ResolveInfo> {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
    }
}

private data class LaunchableApp(
    val appName: String,
    val packageName: String,
    val icon: Drawable
)

private class LaunchableAppsAdapter(
    private val inflater: LayoutInflater,
    private val apps: List<LaunchableApp>
) : BaseAdapter() {

    override fun getCount(): Int = apps.size
    override fun getItem(position: Int): LaunchableApp = apps[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.item_app_tile, parent, false)
        val app = getItem(position)

        view.findViewById<ImageView>(R.id.imageAppIcon).setImageDrawable(app.icon)
        view.findViewById<TextView>(R.id.textAppName).text = app.appName
        view.findViewById<TextView>(R.id.textPackageName).text = app.packageName
        view.contentDescription = "${app.appName}, ${app.packageName}"

        return view
    }
}
