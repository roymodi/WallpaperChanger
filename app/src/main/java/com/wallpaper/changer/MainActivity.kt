package com.wallpaper.changer

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

        dbHelper = DatabaseHelper(this)
        cacheManager = CacheManager(this)
        statusText = findViewById(R.id.statusText)

        // Cancel any periodic work scheduled by older versions
        WallpaperScheduler.cancelPeriodicWork(this)

        // Fill cache on app open
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
            statusText.text = "History: $count | Cache: ${"%.1f".format(cacheSizeMb)} MB | Ready: $ready/5"
        }
    }

    private fun triggerImmediateChange() {
        lifecycleScope.launch {
            WallpaperPrefetcher.topUp(this@MainActivity)
        }
        val workRequest = androidx.work.OneTimeWorkRequestBuilder<WallpaperWorker>()
            .build()
        androidx.work.WorkManager.getInstance(this).enqueue(workRequest)
        Toast.makeText(this, "Changing wallpaper…", Toast.LENGTH_SHORT).show()
    }

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.settings_dialog, null)
        val prefs = getSharedPreferences("wallpaper_prefs", MODE_PRIVATE)

        val targetHome = dialogView.findViewById<android.widget.RadioButton>(R.id.radioHome)
        val targetLock = dialogView.findViewById<android.widget.RadioButton>(R.id.radioLock)
        val targetBoth = dialogView.findViewById<android.widget.RadioButton>(R.id.radioBoth)
        val switchScreenOff = dialogView.findViewById<android.widget.Switch>(R.id.switchScreenOff)
        val switchUnlock = dialogView.findViewById<android.widget.Switch>(R.id.switchUnlock)

        when (prefs.getString("target_screen", "home")) {
            "home" -> targetHome.isChecked = true
            "lock" -> targetLock.isChecked = true
            "both" -> targetBoth.isChecked = true
        }
        switchScreenOff.isChecked = prefs.getBoolean("screen_off_trigger", false)
        switchUnlock.isChecked = prefs.getBoolean("unlock_trigger", false)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                val target = when {
                    targetLock.isChecked -> "lock"
                    targetBoth.isChecked -> "both"
                    else -> "home"
                }
                prefs.edit()
                    .putString("target_screen", target)
                    .putBoolean("screen_off_trigger", switchScreenOff.isChecked)
                    .putBoolean("unlock_trigger", switchUnlock.isChecked)
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
                    val existing = prefs.getStringSet("custom_urls", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                    existing.add(url)
                    prefs.edit().putStringSet("custom_urls", existing).apply()
                    Toast.makeText(this, R.string.url_added, Toast.LENGTH_SHORT).show()
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
        }
    }
}