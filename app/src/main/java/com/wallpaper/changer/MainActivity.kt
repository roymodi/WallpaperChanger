package com.wallpaper.changer

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var dbHelper: DatabaseHelper
    private lateinit var cacheManager: CacheManager
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Start screen monitoring from a visible activity. Android requires a foreground
        // notification for a service that must remain active in the background.
        try {
            androidx.core.content.ContextCompat.startForegroundService(
                this,
                Intent(this, WallpaperMonitorService::class.java)
            )
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Could not start automatic monitoring. Open the app again and check battery settings.",
                Toast.LENGTH_LONG
            ).show()
        }

        dbHelper = DatabaseHelper(this)
        cacheManager = CacheManager(this)
        statusText = findViewById(R.id.statusText)

        WallpaperScheduler.cancelPeriodicWork(this)
        WallpaperScheduler.schedulePeriodicWork(this)

        lifecycleScope.launch {
            WallpaperPrefetcher.topUp(this@MainActivity)
            updateStatus()
        }

        findViewById<Button>(R.id.btnChangeNow).setOnClickListener {
            triggerImmediateChange()
        }

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            showSettingsDialog()
        }

        findViewById<Button>(R.id.btnAddUrl).setOnClickListener {
            showAddUrlDialog()
        }

        findViewById<Button>(R.id.btnResetHistory).setOnClickListener {
            resetHistory()
        }

        findViewById<Button>(R.id.btnClearCache).setOnClickListener {
            cacheManager.clearCache()
            updateStatus()
            Toast.makeText(this, "Cache cleared", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                WallpaperPrefetcher.topUp(this@MainActivity)
                updateStatus()
            }
        }

        updateStatus()
    }

    private fun updateStatus() {
        lifecycleScope.launch {
            val count = dbHelper.getHistoryCount()
            val cacheSizeMb = cacheManager.getCurrentCacheSize() / (1024.0 * 1024.0)
            val ready = cacheManager.getReadyCount()
            statusText.text = "History: $count | Cache: ${"%.1f".format(cacheSizeMb)} MB | Ready: $ready/10"
        }
    }

    private fun triggerImmediateChange() {
        val workRequest = androidx.work.OneTimeWorkRequestBuilder<WallpaperWorker>()
            .addTag("wallpaper_change")
            .build()
        androidx.work.WorkManager.getInstance(this).enqueueUniqueWork(
            "wallpaper_change",
            androidx.work.ExistingWorkPolicy.REPLACE,
            workRequest
        )
        Toast.makeText(this, "Changing wallpaper…", Toast.LENGTH_SHORT).show()
    }

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.settings_dialog, null)
        val prefs = getSharedPreferences("wallpaper_prefs", MODE_PRIVATE)

        val targetHome = dialogView.findViewById<android.widget.RadioButton>(R.id.radioHome)
        val targetLock = dialogView.findViewById<android.widget.RadioButton>(R.id.radioLock)
        val targetBoth = dialogView.findViewById<android.widget.RadioButton>(R.id.radioBoth)
        val switchScreenActivity =
            dialogView.findViewById<android.widget.Switch>(R.id.switchScreenActivity)

        when (prefs.getString("target_screen", "home")) {
            "home" -> targetHome.isChecked = true
            "lock" -> targetLock.isChecked = true
            "both" -> targetBoth.isChecked = true
        }

        val legacyEnabled = prefs.getBoolean("screen_off_trigger", false) ||
            prefs.getBoolean("unlock_trigger", false)
        switchScreenActivity.isChecked =
            prefs.getBoolean("screen_activity_trigger", legacyEnabled)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                val target = when {
                    targetLock.isChecked -> "lock"
                    targetBoth.isChecked -> "both"
                    else -> "home"
                }
                val enabled = switchScreenActivity.isChecked
                prefs.edit()
                    .putString("target_screen", target)
                    .putBoolean("screen_activity_trigger", enabled)
                    .putBoolean("screen_off_trigger", false)
                    .putBoolean("unlock_trigger", false)
                    .apply()

                updateStatus()
                Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAddUrlDialog() {
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = "https://example.com/wallpapers"
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.add_custom_url)
            .setView(input)
            .setPositiveButton(R.string.add) { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    val prefs = getSharedPreferences("wallpaper_prefs", MODE_PRIVATE)
                    val existing = prefs.getStringSet("custom_urls", mutableSetOf())
                        ?.toMutableSet() ?: mutableSetOf()
                    existing.add(url)
                    prefs.edit().putStringSet("custom_urls", existing).apply()
                    Toast.makeText(this, R.string.url_added, Toast.LENGTH_SHORT).show()
                    lifecycleScope.launch {
                        WallpaperPrefetcher.topUp(this@MainActivity)
                        updateStatus()
                    }
                } else {
                    Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun resetHistory() {
        lifecycleScope.launch {
            dbHelper.resetHistory()
            updateStatus()
            Toast.makeText(this@MainActivity, R.string.history_reset, Toast.LENGTH_SHORT).show()
            WallpaperPrefetcher.topUp(this@MainActivity)
        }
    }
}