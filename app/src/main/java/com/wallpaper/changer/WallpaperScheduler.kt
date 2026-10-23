package com.wallpaper.changer

import android.content.Context

/**
 * Scheduler disabled — wallpaper changes are now triggered only by:
 *  - Unlock event (USER_PRESENT)
 *  - Screen off event (SCREEN_OFF)
 *  - Manual "Change Wallpaper Now" button
 *
 * No hourly/periodic scheduling. This class is kept as a no-op
 * so nothing else in the codebase needs changing.
 */
object WallpaperScheduler {
    fun schedulePeriodicWork(context: Context, intervalHours: Long = 0) {
        // No-op — periodic scheduling removed
    }

    fun schedulePeriodicWork(context: Context) {
        // No-op — periodic scheduling removed
    }

    fun cancelPeriodicWork(context: Context) {
        // Cancel any previously-scheduled work from older versions
        try {
            androidx.work.WorkManager.getInstance(context).cancelUniqueWork("wallpaper_change_work")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}