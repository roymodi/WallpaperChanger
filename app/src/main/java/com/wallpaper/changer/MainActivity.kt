package com.wallpaper.changer

import android.content.Intent
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

class MainActivity : AppCompatActivity() {

    private val prefsName = "wallpaper_prefs"
    private val intervalLabels = listOf("1 hour", "12 hours", "1 day", "1 week")
    private val intervalHours = listOf(1L, 12L, 24L, 168L)
    private val networkLabels = listOf("Wi-Fi only", "Mobile data only", "Wi-Fi or mobile data")
    private val networkValues = listOf("wifi", "mobile", "any")

    private lateinit var statusText: TextView
    private lateinit var scheduleSwitch: Switch

    private val localImagePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val selected = prefs.getStringSet("local_image_uris", emptySet())?.toMutableSet()
            ?: mutableSetOf()
        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            selected.add(uri.toString())
        }
        prefs.edit().putStringSet("local_image_uris", selected).apply()
        updateStatus()
        Toast.makeText(this, "${selected.size} local lock-screen image(s) saved", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        scheduleSwitch = findViewById(R.id.switchSchedule)
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)

        // Remove legacy event-trigger and prefetch jobs from older app versions.
        WallpaperScheduler.cancelLegacyWork(this)

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
        findViewById<Button>(R.id.btnRefreshNow).setOnClickListener {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<WallpaperWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf("manual_refresh" to true))
                .addTag("manual_wallpaper_refresh")
                .build()
            WorkManager.getInstance(this).enqueueUniqueWork(
                "manual_wallpaper_refresh",
                ExistingWorkPolicy.REPLACE,
                request
            )
            prefs.edit().putString("last_result", "Manual refresh queued").apply()
            updateStatus()
            Toast.makeText(this, "Wallpaper refresh started", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnSelectLocalImages).setOnClickListener {
            localImagePicker.launch(arrayOf("image/*"))
        }
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
        val localCount = prefs.getStringSet("local_image_uris", emptySet())?.size ?: 0
        val lastResult = prefs.getString("last_result", "No wallpaper change yet")
        statusText.text = "Schedule: " + (if (enabled) "ON" else "OFF") +
            "\nInterval: " + intervalLabel +
            "\nTarget: " + target +
            "\nNetwork: " + network +
            "\nLock-screen local images: " + localCount +
            "\nLast result: " + (lastResult ?: "No wallpaper change yet")
        scheduleSwitch.setOnCheckedChangeListener(null)
        scheduleSwitch.isChecked = enabled
        scheduleSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("schedule_enabled", checked).apply()
            if (checked) WallpaperScheduler.scheduleFromPreferences(this)
            else WallpaperScheduler.cancelSchedule(this)
            updateStatus()
        }
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
}
