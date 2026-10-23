package com.wallpaper.changer

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WallpaperWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext

        val prefs = context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
        val targetScreen = prefs.getString("target_screen", "home") ?: "home"

        val dbHelper = DatabaseHelper(context)
        val cacheManager = CacheManager(context)
        val networkHelper = NetworkHelper()

        if (cacheManager.getReadyCount() == 0) {
            WallpaperPrefetcher.topUp(context)
        }

        return when (targetScreen) {
            "both" -> applyBothScreens(context, dbHelper, cacheManager, networkHelper)
            "lock" -> applySingleScreen(context, dbHelper, cacheManager, networkHelper, "lock")
            else -> applySingleScreen(context, dbHelper, cacheManager, networkHelper, "home")
        }
    }

    private suspend fun applySingleScreen(
        context: Context,
        dbHelper: DatabaseHelper,
        cacheManager: CacheManager,
        networkHelper: NetworkHelper,
        target: String
    ): Result {
        val cached = WallpaperPrefetcher.getInstantWallpaper(context)
        if (cached != null) {
            val bitmap = BitmapFactory.decodeFile(cached.file.absolutePath)
            if (bitmap != null && setWallpaper(context, bitmap, target)) {
                dbHelper.logWallpaper(cached.imageUrl, cached.imageUrl.substringAfterLast("/"), cached.sourceUrl)
                cacheManager.consumeWallpaper(cached)
                WallpaperPrefetcher.topUp(context)
                return Result.success()
            }
        }

        val downloaded = downloadFreshWallpaper(context, dbHelper, cacheManager, networkHelper)
        if (downloaded != null) {
            val bitmap = BitmapFactory.decodeFile(downloaded.file.absolutePath)
            if (bitmap != null && setWallpaper(context, bitmap, target)) {
                dbHelper.logWallpaper(downloaded.imageUrl, downloaded.imageUrl.substringAfterLast("/"), downloaded.sourceUrl)
                cacheManager.consumeWallpaper(downloaded)
                WallpaperPrefetcher.topUp(context)
                return Result.success()
            }
        }

        return Result.retry()
    }

    private suspend fun applyBothScreens(
        context: Context,
        dbHelper: DatabaseHelper,
        cacheManager: CacheManager,
        networkHelper: NetworkHelper
    ): Result {
        var homeCached = WallpaperPrefetcher.getInstantWallpaper(context)
        if (homeCached == null) {
            homeCached = downloadFreshWallpaper(context, dbHelper, cacheManager, networkHelper)
        }
        if (homeCached == null) return Result.retry()

        val homeBitmap = BitmapFactory.decodeFile(homeCached.file.absolutePath)
        if (homeBitmap == null) return Result.retry()

        var lockCached = WallpaperPrefetcher.getInstantWallpaper(
            context, excludeIdentifiers = setOf(homeCached.imageUrl)
        )
        if (lockCached == null) {
            lockCached = downloadFreshWallpaper(
                context, dbHelper, cacheManager, networkHelper,
                excludeIdentifiers = setOf(homeCached.imageUrl)
            )
        }
        if (lockCached == null) return Result.retry()

        val lockBitmap = BitmapFactory.decodeFile(lockCached.file.absolutePath)
        if (lockBitmap == null) return Result.retry()

        val ok = withContext(Dispatchers.Main) {
            setWallpaper(context, homeBitmap, "home") &&
            setWallpaper(context, lockBitmap, "lock")
        }

        if (!ok) return Result.retry()

        dbHelper.logWallpaper(homeCached.imageUrl, homeCached.imageUrl.substringAfterLast("/"), homeCached.sourceUrl)
        dbHelper.logWallpaper(lockCached.imageUrl, lockCached.imageUrl.substringAfterLast("/"), lockCached.sourceUrl)

        cacheManager.consumeWallpaper(homeCached)
        cacheManager.consumeWallpaper(lockCached)

        WallpaperPrefetcher.topUp(context)

        return Result.success()
    }

    private suspend fun downloadFreshWallpaper(
        context: Context,
        dbHelper: DatabaseHelper,
        cacheManager: CacheManager,
        networkHelper: NetworkHelper,
        excludeIdentifiers: Set<String> = emptySet()
    ): CacheManager.CachedWallpaper? {
        val prefs = context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
        val urlPool = PreloadedUrls.ALL_URLS.toMutableList()
        urlPool.addAll(prefs.getStringSet("custom_urls", emptySet()) ?: emptySet())
        if (urlPool.isEmpty()) return null

        val random = java.util.Random()
        val maxAttempts = 5

        repeat(maxAttempts) {
            val sourceUrl = urlPool[random.nextInt(urlPool.size)]
            val imageUrls = networkHelper.scrapeImageUrls(sourceUrl)
            if (imageUrls.isEmpty()) return@repeat

            val available = imageUrls.filter { url ->
                !excludeIdentifiers.contains(url) && !dbHelper.isWallpaperUsed(url)
            }
            if (available.isEmpty()) return@repeat

            val chosenUrl = available[random.nextInt(available.size)]
            val bytes = networkHelper.downloadBytes(chosenUrl) ?: return@repeat
            return cacheManager.saveToCache(chosenUrl, sourceUrl, bytes)
        }
        return null
    }

    private fun setWallpaper(context: Context, bitmap: Bitmap, target: String): Boolean {
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