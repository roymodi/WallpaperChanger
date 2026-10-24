package com.wallpaper.changer

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Registers screen state broadcasts while this app process exists.
 * Android does not guarantee delivery after the process is killed, so boot recovery
 * and the manual button remain available as fallback paths.
 */
class WallpaperChangerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(ScreenStateReceiver(), filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(ScreenStateReceiver(), filter)
            }
        } catch (e: Exception) {
            Log.e("WallpaperChanger", "Could not register screen-state receiver", e)
        }
    }
}

/** Enqueues wallpaper work instead of doing network or bitmap work inside a receiver. */
class ScreenStateReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: Intent) {
        val prefs = context.getSharedPreferences("wallpaper_prefs", android.content.Context.MODE_PRIVATE)
        val enabled = when (intent.action) {
            Intent.ACTION_SCREEN_OFF -> prefs.getBoolean("screen_off_trigger", false)
            Intent.ACTION_USER_PRESENT -> prefs.getBoolean("unlock_trigger", false)
            else -> false
        }
        if (!enabled) {
            if (intent.action == Intent.ACTION_USER_PRESENT) enqueuePrefetch(context)
            return
        }
        val request = OneTimeWorkRequestBuilder<WallpaperWorker>()
            .setInitialDelay(if (intent.action == Intent.ACTION_USER_PRESENT) 700 else 0, TimeUnit.MILLISECONDS)
            .addTag("wallpaper_change")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "wallpaper_change", androidx.work.ExistingWorkPolicy.KEEP, request
        )
        if (intent.action == Intent.ACTION_USER_PRESENT) enqueuePrefetch(context)
    }

    private fun enqueuePrefetch(context: android.content.Context) {
        val request = OneTimeWorkRequestBuilder<PrefetchWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .addTag("wallpaper_prefetch")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "wallpaper_prefetch", androidx.work.ExistingWorkPolicy.KEEP, request
        )
    }
}

/** Boot broadcasts are suitable for manifest registration; do minimal work here. */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: Intent) {
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
            "wallpaper_prefetch_after_boot", androidx.work.ExistingWorkPolicy.KEEP, request
        )
    }
}

/** WorkManager-compatible worker for cache refill. */
class PrefetchWorker(context: android.content.Context, params: androidx.work.WorkerParameters) :
    androidx.work.CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        WallpaperPrefetcher.topUp(applicationContext)
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}