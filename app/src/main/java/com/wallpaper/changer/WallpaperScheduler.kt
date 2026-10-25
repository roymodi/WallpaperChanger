package com.wallpaper.changer

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * One battery-conscious periodic job. WorkManager can delay execution while Doze
 * or battery restrictions are active; Android does not guarantee exact wall-clock timing.
 */
object WallpaperScheduler {
    private const val WORK_NAME = "scheduled_wallpaper_change"

    fun scheduleFromPreferences(context: Context) {
        val prefs = context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("schedule_enabled", false)) {
            cancelSchedule(context)
            return
        }

        val intervalHours = prefs.getLong("interval_hours", 1L).coerceIn(1L, 168L)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<WallpaperWorker>(
            intervalHours, TimeUnit.HOURS
        )
            .setInitialDelay(intervalHours, TimeUnit.HOURS)
            .setConstraints(constraints)
            .addTag(WORK_NAME)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancelSchedule(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork("wallpaper_change_work")
    }

    fun cancelLegacyWork(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork("wallpaper_change_work")
        workManager.cancelUniqueWork("wallpaper_prefetch")
        workManager.cancelAllWorkByTag("wallpaper_change")
        workManager.cancelAllWorkByTag("wallpaper_prefetch")
    }
}
