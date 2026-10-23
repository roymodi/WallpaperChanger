package com.wallpaper.changer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Listens for screen lock (SCREEN_OFF) and unlock (USER_PRESENT) events.
 *
 * Triggers wallpaper change only on:
 *  - Unlock (if "Change on unlock" is enabled)
 *  - Screen off (if "Change on screen off" is enabled)
 *
 * No periodic/hourly scheduling.
 */
class WallpaperService : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)

        when (intent.action) {
            Intent.ACTION_SCREEN_OFF -> {
                if (prefs.getBoolean("screen_off_trigger", false)) {
                    triggerInstantChange(context)
                }
            }

            Intent.ACTION_USER_PRESENT -> {
                if (prefs.getBoolean("unlock_trigger", false)) {
                    triggerInstantChange(context)
                }
                // Always refill cache on unlock so buffer stays at 5
                refillInBackground(context)
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                // Just refill the cache after reboot. No scheduling.
                refillInBackground(context)
            }
        }
    }

    /**
     * Apply wallpaper from cache IMMEDIATELY (~300ms).
     * Refill starts AFTER applying, in its own coroutine,
     * so the download never blocks the wallpaper change.
     */
    private fun triggerInstantChange(context: Context) {
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Small delay so unlock animation can finish
                delay(500)

                val success = InstantWallpaperSetter(appContext).applyNow()
                if (!success) {
                    // Cache was empty — fall back to WorkManager (slow path)
                    fallbackToWorkManager(appContext)
                }

                // Refill in background (does not block)
                launch {
                    try {
                        WallpaperPrefetcher.topUp(appContext)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                fallbackToWorkManager(appContext)
            }
        }
    }

    /**
     * Refill the cache in a background coroutine.
     * Safe to call on every unlock — topUp() is a no-op if buffer is already full.
     */
    private fun refillInBackground(context: Context) {
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                WallpaperPrefetcher.topUp(appContext)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Last-resort fallback if cache is empty and instant set fails.
     */
    private fun fallbackToWorkManager(context: Context) {
        val workRequest = OneTimeWorkRequestBuilder<WallpaperWorker>()
            .setInitialDelay(2, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueue(workRequest)
    }
}