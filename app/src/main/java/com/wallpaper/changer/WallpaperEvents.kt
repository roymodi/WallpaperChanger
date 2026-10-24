package com.wallpaper.changer

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Screen-off and unlock are treated as one lock/unlock cycle.
 * A change is queued on screen-off; unlock only queues a change if screen-off
 * was missed. This prevents two changes for the same cycle.
 */
class WallpaperChangerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            val receiver = ScreenStateReceiver()
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            Log.e("WallpaperChanger", "Could not register screen-state receiver", e)
        }
    }
}

class ScreenStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(
            "screen_activity_trigger",
            prefs.getBoolean("screen_off_trigger", false) ||
                prefs.getBoolean("unlock_trigger", false)
        )

        when (intent.action) {
            Intent.ACTION_SCREEN_OFF -> {
                if (!enabled) return
                val alreadyHandled = prefs.getBoolean("screen_activity_pending", false)
                if (!alreadyHandled) {
                    // Persist before enqueueing so the following unlock is deduplicated.
                    prefs.edit().putBoolean("screen_activity_pending", true).commit()
                    enqueueWallpaperChange(context, 0)
                }
            }

            Intent.ACTION_USER_PRESENT -> {
                val screenOffAlreadyHandled =
                    prefs.getBoolean("screen_activity_pending", false)
                prefs.edit().putBoolean("screen_activity_pending", false).apply()

                if (enabled && !screenOffAlreadyHandled) {
                    // Fallback if Android did not deliver SCREEN_OFF to the running process.
                    enqueueWallpaperChange(context, 700)
                }

                // Refill in the background after each unlock, even when automatic changes are off.
                enqueuePrefetch(context)
            }
        }
    }

    private fun enqueueWallpaperChange(context: Context, delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<WallpaperWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .addTag("wallpaper_change")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "wallpaper_change",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun enqueuePrefetch(context: Context) {
        val request = OneTimeWorkRequestBuilder<PrefetchWorker>()
            .setConstraints(prefetchConstraints())
            .addTag("wallpaper_prefetch")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "wallpaper_prefetch",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun prefetchConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()
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