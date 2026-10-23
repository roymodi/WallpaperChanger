package com.wallpaper.changer

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build

/**
 * Sets wallpaper instantly from cache (~300ms).
 * Does NOT download — if cache is empty, returns false.
 * Refill is handled by WallpaperService, not this class.
 */
class InstantWallpaperSetter(private val context: Context) {

    suspend fun applyNow(): Boolean {
        val prefs = context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
        val targetScreen = prefs.getString("target_screen", "home") ?: "home"

        val cacheManager = CacheManager(context)
        val dbHelper = DatabaseHelper(context)

        if (cacheManager.getReadyCount() == 0) return false

        return when (targetScreen) {
            "both" -> applyBothScreens(cacheManager, dbHelper)
            "lock" -> applySingle(cacheManager, dbHelper, "lock")
            else -> applySingle(cacheManager, dbHelper, "home")
        }
    }

    private suspend fun applySingle(
        cacheManager: CacheManager,
        dbHelper: DatabaseHelper,
        target: String
    ): Boolean {
        val cached = WallpaperPrefetcher.getInstantWallpaper(context) ?: return false
        val bitmap = BitmapFactory.decodeFile(cached.file.absolutePath) ?: return false

        val ok = setWallpaper(bitmap, target)
        if (ok) {
            dbHelper.logWallpaper(cached.imageUrl, cached.imageUrl.substringAfterLast("/"), cached.sourceUrl)
            cacheManager.consumeWallpaper(cached)
            // NOTE: Do NOT call topUp here. WallpaperService handles refill separately.
        }
        return ok
    }

    private suspend fun applyBothScreens(
        cacheManager: CacheManager,
        dbHelper: DatabaseHelper
    ): Boolean {
        val homeCached = WallpaperPrefetcher.getInstantWallpaper(context) ?: return false
        val lockCached = WallpaperPrefetcher.getInstantWallpaper(
            context, excludeIdentifiers = setOf(homeCached.imageUrl)
        ) ?: return false

        val homeBitmap = BitmapFactory.decodeFile(homeCached.file.absolutePath) ?: return false
        val lockBitmap = BitmapFactory.decodeFile(lockCached.file.absolutePath) ?: return false

        val okHome = setWallpaper(homeBitmap, "home")
        val okLock = setWallpaper(lockBitmap, "lock")

        if (okHome && okLock) {
            dbHelper.logWallpaper(homeCached.imageUrl, homeCached.imageUrl.substringAfterLast("/"), homeCached.sourceUrl)
            dbHelper.logWallpaper(lockCached.imageUrl, lockCached.imageUrl.substringAfterLast("/"), lockCached.sourceUrl)
            cacheManager.consumeWallpaper(homeCached)
            cacheManager.consumeWallpaper(lockCached)
        }
        return okHome && okLock
    }

    private fun setWallpaper(bitmap: Bitmap, target: String): Boolean {
        return try {
            val wm = WallpaperManager.getInstance(context)
            when (target) {
                "home" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM)
                    } else {
                        wm.setBitmap(bitmap)
                    }
                }
                "lock" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
                    } else {
                        return false
                    }
                }
                "both" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        wm.setBitmap(bitmap, null, true,
                            WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                    } else {
                        wm.setBitmap(bitmap)
                    }
                }
                else -> wm.setBitmap(bitmap)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}