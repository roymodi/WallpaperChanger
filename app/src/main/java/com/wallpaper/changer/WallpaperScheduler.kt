package com.wallpaper.changer

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Periodically refills the cache. WorkManager waits for a network connection
 * and a non-low battery state, and Android may defer execution to save power.
 */
object WallpaperScheduler {
    private const val PREFETCH_WORK_NAME = "wallpaper_cache_refill_periodic"

    fun schedulePeriodicWork(context: Context, intervalHours: Long = 6) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<PrefetchWorker>(
            intervalHours.coerceAtLeast(6), TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PREFETCH_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun schedulePeriodicWork(context: Context) {
        schedulePeriodicWork(context, 6)
    }

    fun cancelPeriodicWork(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork("wallpaper_change_work")
        // Keep an existing periodic refill schedule if already installed.
    }
}