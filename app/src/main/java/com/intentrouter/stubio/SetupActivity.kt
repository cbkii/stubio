package com.intentrouter.stubio

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.ArrayAdapter
import android.view.ViewGroup
import android.view.LayoutInflater
import java.util.UUID
import android.widget.Toast
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
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

    private lateinit var btnAdvancedRoutingToggle: Button
    private lateinit var advancedRoutingContainer: LinearLayout
    private lateinit var checkAdvancedRoutingEnabled: CheckBox
    private lateinit var layoutAdvancedRulesList: LinearLayout
    private lateinit var btnAddAdvancedRule: Button

    private var advancedRulesConfigs = mutableListOf<AdvancedRoutingRuleConfig>()
    private lateinit var btnValidateAdvancedRouting: Button
    private lateinit var btnAdvancedRoutingTemplates: Button
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

        btnAdvancedRoutingToggle = findViewById(R.id.btnAdvancedRoutingToggle)
        advancedRoutingContainer = findViewById(R.id.advancedRoutingContainer)
        checkAdvancedRoutingEnabled = findViewById(R.id.checkAdvancedRoutingEnabled)
        layoutAdvancedRulesList = findViewById(R.id.layoutAdvancedRulesList)
        btnAddAdvancedRule = findViewById(R.id.btnAddAdvancedRule)

        btnValidateAdvancedRouting = findViewById(R.id.btnValidateAdvancedRouting)
        btnAdvancedRoutingTemplates = findViewById(R.id.btnAdvancedRoutingTemplates)

        loadSavedSettings()

        btnAdvancedRoutingToggle.setOnClickListener {
            val isExpanded = advancedRoutingContainer.visibility == View.VISIBLE
            setAdvancedRoutingExpanded(!isExpanded)
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ADVANCED_ROUTING_EXPANDED, !isExpanded)
                .apply()
        }


        btnAddAdvancedRule.setOnClickListener {
            val newRule = AdvancedRoutingRuleConfig(
                id = java.util.UUID.randomUUID().toString(),
                order = (advancedRulesConfigs.maxOfOrNull { it.order } ?: 0) + 10
            )
            advancedRulesConfigs.add(newRule)
            renderAdvancedRules()
            showEditRuleDialog(newRule)
        }
        btnAdvancedRoutingTemplates.setOnClickListener { showTemplatesDialog() }

        btnValidateAdvancedRouting.setOnClickListener {
            val rulesText = btnAddAdvancedRule.text.toString()
            val parsed = parseAdvancedRules(rulesText)

            // Reconstruct rulesText to see if there were invalid lines
            val rawLines = rulesText.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }
            if (rawLines.size != parsed.size) {
                Toast.makeText(this, getString(R.string.advanced_routing_invalid, "Some rules could not be parsed"), Toast.LENGTH_LONG).show()
                btnAddAdvancedRule.requestFocus()
            } else {
                val invalidPackage = parsed.find { !it.packageName.matches(PACKAGE_PATTERN) }
                if (invalidPackage != null) {
                    Toast.makeText(this, getString(R.string.advanced_routing_invalid, "Invalid package name: ${invalidPackage.packageName}"), Toast.LENGTH_LONG).show()
                    btnAddAdvancedRule.requestFocus()
                } else {
                    Toast.makeText(this, R.string.advanced_routing_valid, Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnPickStreamPrimary.setOnClickListener { showAppPicker(editStreamPrimary, false) }
        btnPickStreamFallback.setOnClickListener { showAppPicker(editStreamFallback, false) }
        btnPickTrailerPrimary.setOnClickListener { showAppPicker(editTrailerPrimary, false) }
        btnPickTrailerFallback.setOnClickListener { showAppPicker(editTrailerFallback, false) }

        btnSave.setOnClickListener { saveSettings() }

        editStreamPrimary.requestFocus()
    }


    private fun showTemplatesDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.advanced_routing_templates_title)
            .setNegativeButton(R.string.app_picker_close, null)
            .setNeutralButton(R.string.advanced_routing_regex_docs) { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.advanced_routing_regex_docs_url)))
                runCatching { startActivity(intent) }
            }
            .create()

        val adapter = object : ArrayAdapter<AdvancedRoutingTemplate>(this, 0, ADVANCED_ROUTING_TEMPLATES) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.view_advanced_template_row, parent, false)
                val template = getItem(position)!!
                view.findViewById<TextView>(R.id.textTemplateTitle).text = template.title
                view.findViewById<TextView>(R.id.textTemplatePattern).text = template.patternText
                view.findViewById<TextView>(R.id.textTemplateDescription).text = template.description
                return view
            }
        }

        dialog.setOnShowListener {
            val listView = ListView(this).apply {
                this.adapter = adapter
                this.setOnItemClickListener { _, _, position, _ ->
                    val template = adapter.getItem(position)!!

                    // Provide template into a new rule row
                    val newRule = AdvancedRoutingRuleConfig(
                        id = UUID.randomUUID().toString(),
                        patternRaw = template.patternText,
                        order = template.defaultOrder,
                        packageName = "app.package.name" // placeholder or could use selected app
                    )
                    advancedRulesConfigs.add(newRule)
                    renderAdvancedRules()
                    showEditRuleDialog(newRule)

                    Toast.makeText(this@SetupActivity, R.string.advanced_routing_template_copied, Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
            // Add listview dynamically to alert dialog
            dialog.setView(listView)
            dialog.setContentView(listView) // Replaces default content view
            listView.requestFocus()
        }
        dialog.show()
    }

    private fun setAdvancedRoutingExpanded(expanded: Boolean) {
        advancedRoutingContainer.visibility = if (expanded) View.VISIBLE else View.GONE
        btnAdvancedRoutingToggle.text = getString(
            if (expanded) R.string.advanced_routing_expanded
            else R.string.advanced_routing_collapsed
        )

        if (expanded) {
            btnAdvancedRoutingToggle.nextFocusDownId = R.id.checkAdvancedRoutingEnabled
            btnSave.nextFocusUpId = R.id.btnValidateAdvancedRouting
        } else {
            btnAdvancedRoutingToggle.nextFocusDownId = R.id.btnSave
            btnSave.nextFocusUpId = R.id.btnAdvancedRoutingToggle
        }
    }

    private fun showAppPicker(targetField: EditText, isAdvancedRouting: Boolean = false) {
        setPickerButtonsEnabled(false)

        lifecycleScope.launch {
            val apps = cachedUserInstalledApps ?: withContext(Dispatchers.IO) {
                loadUserInstalledApps()
            }.also { cachedUserInstalledApps = it }

            setPickerButtonsEnabled(true)
            if (isFinishing || isDestroyed) return@launch

            if (apps.isEmpty()) {
                Toast.makeText(this@SetupActivity, R.string.no_apps_found, Toast.LENGTH_SHORT).show()
                return@launch
            }

            showAppPickerDialog(targetField, apps, isAdvancedRouting)
        }
    }

    private fun validateAdvancedRoutingRules(requireRules: Boolean): String? {
        if (advancedRulesConfigs.isEmpty()) {
            return if (requireRules) getString(R.string.error_no_rules_provided) else null
        }

        val invalidPackage = advancedRulesConfigs.find { it.packageName.isNotBlank() && !it.packageName.matches(PACKAGE_PATTERN) }
        if (invalidPackage != null) {
            return getString(R.string.error_invalid_package_name, invalidPackage.packageName)
        }

        val missingPattern = advancedRulesConfigs.find { it.patternRaw.isBlank() }
        if (missingPattern != null) {
            return "Rule missing pattern"
        }

        return null
    }


    private fun renderAdvancedRules() {
        layoutAdvancedRulesList.removeAllViews()
        for (config in advancedRulesConfigs) {
            val view = layoutInflater.inflate(R.layout.view_advanced_rule_row, layoutAdvancedRulesList, false)

            val checkEnabled = view.findViewById<CheckBox>(R.id.checkRuleEnabled)
            val textSummary = view.findViewById<TextView>(R.id.textRuleSummary)
            val textPattern = view.findViewById<TextView>(R.id.textRulePattern)
            val btnEdit = view.findViewById<Button>(R.id.btnRuleEdit)
            val btnDelete = view.findViewById<Button>(R.id.btnRuleDelete)

            checkEnabled.isChecked = config.enabled
            checkEnabled.setOnCheckedChangeListener { _, isChecked ->
                config.enabled = isChecked
            }

            textSummary.text = "${config.order} | ${config.packageName.ifBlank { "No app selected" }}"
            textPattern.text = config.patternRaw.ifBlank { "No pattern" }

            btnEdit.setOnClickListener { showEditRuleDialog(config) }
            btnDelete.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle(R.string.delete_rule_title)
                    .setMessage(R.string.delete_rule_message)
                    .setPositiveButton(R.string.delete_rule_confirm) { _, _ ->
                        advancedRulesConfigs.remove(config)
                        renderAdvancedRules()
                    }
                    .setNegativeButton(R.string.delete_rule_cancel, null)
                    .show()
            }

            layoutAdvancedRulesList.addView(view)
        }
    }

    private fun showEditRuleDialog(config: AdvancedRoutingRuleConfig) {
        // For simplicity, we just use a small dialog with package and pattern inputs
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_rule, null)
        val editApp = dialogView.findViewById<EditText>(R.id.editRuleApp)
        val editPattern = dialogView.findViewById<EditText>(R.id.editRulePattern)
        val editOrder = dialogView.findViewById<EditText>(R.id.editRuleOrder)
        val btnPickApp = dialogView.findViewById<Button>(R.id.btnPickRuleApp)

        editApp.setText(config.packageName)
        editPattern.setText(config.patternRaw)
        editOrder.setText(config.order.toString())

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.btn_edit_rule)
            .setView(dialogView)
            .setPositiveButton(R.string.app_picker_ok) { _, _ ->
                config.packageName = editApp.text.toString().trim()
                config.patternRaw = editPattern.text.toString().trim()
                config.order = editOrder.text.toString().toIntOrNull() ?: config.order
                renderAdvancedRules()
            }
            .setNegativeButton(R.string.delete_rule_cancel, null)
            .create()

        btnPickApp.setOnClickListener {
            // Reusing app picker slightly modified
            val apps = cachedUserInstalledApps ?: emptyList()
            if (apps.isNotEmpty()) {
                val labels = apps.map { "${it.appName} (${it.packageName})" }.toTypedArray()
                AlertDialog.Builder(this)
                    .setItems(labels) { _, which ->
                        editApp.setText(apps[which].packageName)
                    }.show()
            }
        }

        dialog.show()
    }

    private fun showAppPickerDialog(targetField: EditText, apps: List<InstalledApp>, isAdvancedRouting: Boolean) {
        val currentPackageName = targetField.text.toString().trim()
        val initialSelection = apps.indexOfFirst { it.packageName == currentPackageName }

        val labels = apps.map { app ->
            "${app.appName} (${app.packageName})"
        }.toTypedArray()

        var selectedIndex = if (initialSelection >= 0) initialSelection else 0
        var committed = false

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.app_picker_title)
            .setSingleChoiceItems(labels, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton(R.string.app_picker_ok, null)
            .setNegativeButton(R.string.app_picker_close, null)
            .create()

        dialog.setOnShowListener {
            val listView = dialog.listView ?: return@setOnShowListener
            val okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            okButton.setOnClickListener {
                if (committed) return@setOnClickListener
                committed = true
                if (selectedIndex in apps.indices) {
                    selectAppPackage(targetField, apps[selectedIndex].packageName, isAdvancedRouting)
                }
                dialog.dismiss()
            }

            listView.choiceMode = ListView.CHOICE_MODE_SINGLE
            listView.setItemChecked(selectedIndex, true)
            listView.setSelection(selectedIndex)

            listView.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    selectedIndex = position
                    listView.setItemChecked(position, true)
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            })

            listView.setOnItemClickListener { _, _, position, _ ->
                selectedIndex = position
                listView.setItemChecked(position, true)
            }

            listView.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_UP || !isConfirmKey(keyCode)) {
                    false
                } else if (committed) {
                    true
                } else {
                    val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    positiveButton?.performClick() ?: false
                }
            }

            listView.requestFocus()
        }

        dialog.setOnDismissListener {
            targetField.requestFocus()
        }

        dialog.show()
    }

    private fun selectAppPackage(targetField: EditText, packageName: String, isAdvancedRouting: Boolean) {
        if (isAdvancedRouting) {
            val currentText = targetField.text.toString()
            val newText = if (currentText.isEmpty() || currentText.endsWith("\n")) {
                currentText + packageName
            } else {
                currentText + "\n" + packageName
            }
            targetField.setText(newText)
            targetField.setSelection(newText.length)
            Toast.makeText(this, R.string.advanced_routing_app_inserted, Toast.LENGTH_LONG).show()
        } else {
            targetField.setText(packageName)
            targetField.setSelection(packageName.length)
        }
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

        // Pre-query components to avoid resolveActivity per package
        val videoApps = getPackagesHandlingVideo(pm)
        val youtubeApps = getPackagesHandlingYoutube(pm)

        return queryInstalledApplications(pm)
            .asSequence()
            .filter { shouldIncludeAppInPicker(pm, it, videoApps, youtubeApps) }
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

        checkAdvancedRoutingEnabled.isChecked = sp.getBoolean(KEY_ADVANCED_ROUTING_ENABLED, false)

        val jsonString = sp.getString(KEY_ADVANCED_ROUTING_RULES_JSON, null)
        if (jsonString != null) {
            advancedRulesConfigs = deserializeAdvancedRules(jsonString).toMutableList()
        } else {
            val rulesText = sp.getString(KEY_ADVANCED_ROUTING_RULES_TEXT, "") ?: ""
            if (rulesText.isNotBlank()) {
                val parsed = parseAdvancedRules(rulesText)
                advancedRulesConfigs = parsed.map {
                    AdvancedRoutingRuleConfig(
                        id = java.util.UUID.randomUUID().toString(),
                        packageName = it.packageName,
                        patternRaw = it.pattern, // Might lose some precision during migration, but functional
                        order = it.order
                    )
                }.toMutableList()
            }
        }
        renderAdvancedRules()

        val expanded = sp.getBoolean(KEY_ADVANCED_ROUTING_EXPANDED, false)
        setAdvancedRoutingExpanded(expanded)
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

        if (checkAdvancedRoutingEnabled.isChecked) {
            val rulesText = btnAddAdvancedRule.text.toString()
            if (rulesText.isNotBlank()) {
                val parsed = parseAdvancedRules(rulesText)
                val rawLines = rulesText.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }
                if (rawLines.size != parsed.size) {
                    Toast.makeText(this, getString(R.string.advanced_routing_invalid, "Some rules could not be parsed"), Toast.LENGTH_LONG).show()
                    setAdvancedRoutingExpanded(true)
                    btnAddAdvancedRule.requestFocus()
                    return
                }

                val invalidPackage = parsed.find { !it.packageName.matches(PACKAGE_PATTERN) }
                if (invalidPackage != null) {
                    Toast.makeText(this, getString(R.string.advanced_routing_invalid, "Invalid package name: ${invalidPackage.packageName}"), Toast.LENGTH_LONG).show()
                    setAdvancedRoutingExpanded(true)
                    btnAddAdvancedRule.requestFocus()
                    return
                }
            } else {
                Toast.makeText(this, getString(R.string.advanced_routing_invalid, "Advanced routing enabled but no rules provided"), Toast.LENGTH_LONG).show()
                checkAdvancedRoutingEnabled.isChecked = false
            }
        }

        val sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit()
            .putString(KEY_STREAM_PRIMARY, editStreamPrimary.text.toString().trim())
            .putString(KEY_STREAM_FALLBACK, editStreamFallback.text.toString().trim())
            .putString(KEY_TRAILER_PRIMARY, editTrailerPrimary.text.toString().trim())
            .putString(KEY_TRAILER_FALLBACK, editTrailerFallback.text.toString().trim())
            .putString(KEY_ADDITIONAL_ALLOWED_HOSTS, editAdditionalAllowedHosts.text.toString().trim())
            .putBoolean(KEY_ADVANCED_ROUTING_ENABLED, checkAdvancedRoutingEnabled.isChecked)
            .putString(KEY_ADVANCED_ROUTING_RULES_JSON, serializeAdvancedRules(advancedRulesConfigs))
            .apply()

        Toast.makeText(this, R.string.saved_confirmation, Toast.LENGTH_SHORT).show()
    }

    private fun isConfirmKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_BUTTON_A
    }

    companion object {
        private const val TAG = "StubioSetup"
        const val PREFS_NAME = "StubioPrefs"
        const val KEY_STREAM_PRIMARY = "stream_player_primary"
        const val KEY_STREAM_FALLBACK = "stream_player_fallback"
        const val KEY_TRAILER_PRIMARY = "trailer_player_primary"
        const val KEY_TRAILER_FALLBACK = "trailer_player_fallback"
        const val KEY_ADDITIONAL_ALLOWED_HOSTS = "additional_allowed_hosts"
        const val KEY_ADVANCED_ROUTING_ENABLED = "advanced_routing_enabled"
        const val KEY_ADVANCED_ROUTING_EXPANDED = "advanced_routing_expanded"
        const val KEY_ADVANCED_ROUTING_RULES_TEXT = "advanced_routing_rules_text"
        const val KEY_ADVANCED_ROUTING_RULES_JSON = "advanced_routing_rules_json"

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

private fun shouldIncludeAppInPicker(
    pm: PackageManager,
    appInfo: ApplicationInfo,
    videoApps: Set<String>,
    youtubeApps: Set<String>
): Boolean {
    if (isUserInstalledApp(appInfo)) return true
    val packageName = appInfo.packageName
    return hasLauncherEntryPoint(pm, packageName) ||
        packageName in videoApps ||
        packageName in youtubeApps
}

private fun hasLauncherEntryPoint(pm: PackageManager, packageName: String): Boolean {
    return pm.getLaunchIntentForPackage(packageName) != null ||
        pm.getLeanbackLaunchIntentForPackage(packageName) != null
}

private fun getPackagesHandlingVideo(pm: PackageManager): Set<String> {
    val videoIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse("http://127.0.0.1/stubio-test.mp4"), "video/*")
    }
    return try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            pm.queryIntentActivities(videoIntent, flags).map { it.activityInfo.packageName }.toSet()
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(videoIntent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }.toSet()
        }
    } catch (e: RuntimeException) {
        Log.w("StubioSetup", "Failed to query apps handling video intents", e)
        emptySet()
    }
}

private fun getPackagesHandlingYoutube(pm: PackageManager): Set<String> {
    val youtubeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    return try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            pm.queryIntentActivities(youtubeIntent, flags).map { it.activityInfo.packageName }.toSet()
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(youtubeIntent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }.toSet()
        }
    } catch (e: RuntimeException) {
        Log.w("StubioSetup", "Failed to query apps handling YouTube intents", e)
        emptySet()
    }
}

private data class InstalledApp(
    val appName: String,
    val packageName: String
)
