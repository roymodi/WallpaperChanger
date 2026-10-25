package com.wallpaper.changer

import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import java.io.File

class MainActivity : AppCompatActivity() {

    private val prefsName = "wallpaper_prefs"
    private val intervalLabels = listOf("1 hour", "12 hours", "1 day", "1 week")
    private val intervalHours = listOf(1L, 12L, 24L, 168L)
    private val networkLabels = listOf("Wi-Fi only", "Mobile data only", "Wi-Fi or mobile data")
    private val networkValues = listOf("wifi", "mobile", "any")

    private lateinit var statusText: TextView
    private lateinit var scheduleSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        scheduleSwitch = findViewById(R.id.switchSchedule)
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)

        // Stop old screen-event/cache work left by previous versions.
        WallpaperScheduler.cancelLegacyWork(this)
        File(cacheDir, "wallpaper_cache").deleteRecursively()

        scheduleSwitch.isChecked = prefs.getBoolean("schedule_enabled", false)
        scheduleSwitch.setOnCheckedChangeListener { _, enabled ->
            prefs.edit().putBoolean("schedule_enabled", enabled).apply()
            if (enabled) {
                WallpaperScheduler.scheduleFromPreferences(this)
                Toast.makeText(this, "Wallpaper schedule enabled", Toast.LENGTH_SHORT).show()
            } else {
                WallpaperScheduler.cancelSchedule(this)
                Toast.makeText(this, "Wallpaper schedule paused", Toast.LENGTH_SHORT).show()
            }
            updateStatus()
        }

        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }
        findViewById<Button>(R.id.btnAddUrl).setOnClickListener { showAddUrlDialog() }
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val enabled = prefs.getBoolean("schedule_enabled", false)
        val hours = prefs.getLong("interval_hours", 1L)
        val intervalLabel = intervalLabels[intervalHours.indexOf(hours).coerceAtLeast(0)]
        val target = when (prefs.getString("target_screen", "home")) {
            "lock" -> "Lock screen"
            "both" -> "Home + Lock (different images)"
            else -> "Home screen"
        }
        val network = when (prefs.getString("network_mode", "any")) {
            "wifi" -> "Wi-Fi only"
            "mobile" -> "Mobile data only"
            else -> "Wi-Fi or mobile data"
        }
        val lastResult = prefs.getString("last_result", "No wallpaper change yet")
        statusText.text = "Schedule: " + (if (enabled) "ON" else "OFF") +
            "\nInterval: " + intervalLabel +
            "\nTarget: " + target +
            "\nNetwork: " + network +
            "\nLast result: " + (lastResult ?: "No wallpaper change yet")
        scheduleSwitch.isChecked = enabled
    }

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.settings_dialog, null)
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val targetGroup = view.findViewById<RadioGroup>(R.id.targetGroup)
        val radioHome = view.findViewById<RadioButton>(R.id.radioHome)
        val radioLock = view.findViewById<RadioButton>(R.id.radioLock)
        val radioBoth = view.findViewById<RadioButton>(R.id.radioBoth)
        val intervalSpinner = view.findViewById<Spinner>(R.id.spinnerInterval)
        val networkSpinner = view.findViewById<Spinner>(R.id.spinnerNetwork)

        when (prefs.getString("target_screen", "home")) {
            "lock" -> radioLock.isChecked = true
            "both" -> radioBoth.isChecked = true
            else -> radioHome.isChecked = true
        }

        intervalSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, intervalLabels
        )
        val savedHours = prefs.getLong("interval_hours", 1L)
        intervalSpinner.setSelection(intervalHours.indexOf(savedHours).coerceAtLeast(0))

        networkSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, networkLabels
        )
        val savedNetwork = prefs.getString("network_mode", "any") ?: "any"
        networkSpinner.setSelection(networkValues.indexOf(savedNetwork).coerceAtLeast(0))

        AlertDialog.Builder(this)
            .setTitle("Schedule settings")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val target = when (targetGroup.checkedRadioButtonId) {
                    R.id.radioLock -> "lock"
                    R.id.radioBoth -> "both"
                    else -> "home"
                }
                val hours = intervalHours[intervalSpinner.selectedItemPosition.coerceIn(intervalHours.indices)]
                val network = networkValues[networkSpinner.selectedItemPosition.coerceIn(networkValues.indices)]
                prefs.edit()
                    .putString("target_screen", target)
                    .putLong("interval_hours", hours)
                    .putString("network_mode", network)
                    .apply()

                if (prefs.getBoolean("schedule_enabled", false)) {
                    WallpaperScheduler.scheduleFromPreferences(this)
                }
                updateStatus()
                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddUrlDialog() {
        val input = TextInputEditText(this).apply {
            hint = "https://example.com/wallpaper.jpg or a wallpaper page"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Add wallpaper URL")
            .setMessage("Add a direct image URL or a page containing wallpaper images.")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val url = input.text?.toString()?.trim().orEmpty()
                val parsed = runCatching { Uri.parse(url) }.getOrNull()
                if ((parsed?.scheme == "https" || parsed?.scheme == "http") && !parsed.host.isNullOrBlank()) {
                    val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
                    val urls = prefs.getStringSet("custom_urls", emptySet())?.toMutableSet() ?: mutableSetOf()
                    urls.add(url)
                    prefs.edit().putStringSet("custom_urls", urls).apply()
                    Toast.makeText(this, "URL added", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Enter a valid http or https URL", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
