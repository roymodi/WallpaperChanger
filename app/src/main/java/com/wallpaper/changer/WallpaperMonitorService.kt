package com.wallpaper.changer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Keeps screen-state broadcasts registered while Android keeps this foreground service alive.
 * The persistent notification is required by Android for this service type.
 */
class WallpaperMonitorService : Service() {

    private var screenReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        startAsForegroundService()
        registerScreenReceiver()
    }

    private fun startAsForegroundService() {
        val channelId = "wallpaper_automation"
        val manager = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "Wallpaper automation",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps automatic wallpaper changing enabled"
                }
            )
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("WallpaperChanger is active")
            .setContentText("Automatic wallpaper changing is enabled")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun registerScreenReceiver() {
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val enabled = prefs.getBoolean(
                    "screen_activity_trigger",
                    prefs.getBoolean("screen_off_trigger", false) ||
                        prefs.getBoolean("unlock_trigger", false)
                )

                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        if (!enabled) return

                        val alreadyHandled =
                            prefs.getBoolean("screen_activity_pending", false)
                        if (!alreadyHandled) {
                            prefs.edit()
                                .putBoolean("screen_activity_pending", true)
                                .commit()
                            enqueueWallpaperChange(0)
                        }
                    }

                    Intent.ACTION_USER_PRESENT -> {
                        val screenOffHandled =
                            prefs.getBoolean("screen_activity_pending", false)
                        prefs.edit()
                            .putBoolean("screen_activity_pending", false)
                            .apply()

                        if (enabled && !screenOffHandled) {
                            // Fallback if SCREEN_OFF was missed while the service restarted.
                            enqueueWallpaperChange(700)
                        }

                        enqueuePrefetch()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenReceiver, filter)
        }
    }

    private fun enqueueWallpaperChange(delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<WallpaperWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .addTag("wallpaper_change")
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "wallpaper_change",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun enqueuePrefetch() {
        val request = OneTimeWorkRequestBuilder<PrefetchWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .addTag("wallpaper_prefetch")
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "wallpaper_prefetch",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onDestroy() {
        screenReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
                // Already unregistered.
            }
        }
        screenReceiver = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 4102
        private const val PREFS_NAME = "wallpaper_prefs"
    }
}
