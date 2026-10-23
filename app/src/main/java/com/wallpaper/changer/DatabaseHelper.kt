package com.wallpaper.changer

import android.content.Context
import com.wallpaper.changer.data.AppDatabase
import com.wallpaper.changer.data.WallpaperLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Helper class for anti-repetition tracking.
 * Filters out wallpapers already downloaded and logged.
 */
class DatabaseHelper(context: Context) {

    private val dao = AppDatabase.getInstance(context).wallpaperLogDao()

    /**
     * Check if an image identifier has already been used.
     */
    suspend fun isWallpaperUsed(identifier: String): Boolean {
        return withContext(Dispatchers.IO) {
            dao.exists(identifier)
        }
    }

    /**
     * Log a newly downloaded wallpaper to prevent repetition.
     */
    suspend fun logWallpaper(identifier: String, imageName: String, sourceUrl: String) {
        withContext(Dispatchers.IO) {
            dao.insert(WallpaperLog(
                identifier = identifier,
                imageName = imageName,
                sourceUrl = sourceUrl
            ))
        }
    }

    /**
     * Get the total number of logged wallpapers.
     */
    suspend fun getHistoryCount(): Int {
        return withContext(Dispatchers.IO) { dao.getCount() }
    }

    /**
     * Reset history (allow repeats again).
     */
    suspend fun resetHistory() {
        withContext(Dispatchers.IO) { dao.clearAll() }
    }
}