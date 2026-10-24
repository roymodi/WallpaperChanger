package com.wallpaper.changer

import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build

/**
 * Applies an already-cached image. No network access happens in this class.
 * For "both", one WallpaperManager call updates both flags to avoid a two-step
 * home/lock update that can leave the screens inconsistent.
 */
class InstantWallpaperSetter(private val context: Context) {
    suspend fun applyNow(): Boolean {
        val prefs = context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
        val target = prefs.getString("target_screen", "home") ?: "home"
        val cache = CacheManager(context)
        val db = DatabaseHelper(context)
        if (cache.getReadyCount() == 0) return false

        val cached = WallpaperPrefetcher.getInstantWallpaper(context) ?: return false
        val bitmap = BitmapFactory.decodeFile(cached.file.absolutePath) ?: return false
        val flags = when (target) {
            "lock" -> WallpaperManager.FLAG_LOCK
            "both" -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            else -> WallpaperManager.FLAG_SYSTEM
        }
        val success = try {
            val manager = WallpaperManager.getInstance(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                manager.setBitmap(bitmap, null, true, flags)
            } else {
                if (target == "lock") return false
                manager.setBitmap(bitmap)
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            bitmap.recycle()
        }

        if (success) {
            db.logWallpaper(cached.imageUrl, cached.imageUrl.substringAfterLast("/"), cached.sourceUrl)
            cache.consumeWallpaper(cached)
        }
        return success
    }
}