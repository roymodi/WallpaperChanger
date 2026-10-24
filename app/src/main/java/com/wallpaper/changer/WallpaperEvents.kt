package com.wallpaper.changer

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Screen events are handled by WallpaperMonitorService, not by an application-scoped
 * dynamic receiver. Application-scoped receivers disappear when Android kills the process.
 */
class WallpaperChangerApp : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val request = OneTimeWorkRequestBuilder<PrefetchWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "wallpaper_prefetch_after_boot",
            ExistingWorkPolicy.KEEP,
            request
        )
        WallpaperScheduler.schedulePeriodicWork(context)

        // Try to restore monitoring after reboot. Some Android builds may restrict this;
        // opening the app starts it again from a visible activity.
        try {
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, WallpaperMonitorService::class.java)
            )
        } catch (_: Exception) {
            // User can restart monitoring by opening WallpaperChanger.
        }
    }
}

class PrefetchWorker(context: Context, params: androidx.work.WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        WallpaperPrefetcher.topUp(applicationContext)
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount >= 3) Result.failure() else Result.retry()
    }
}