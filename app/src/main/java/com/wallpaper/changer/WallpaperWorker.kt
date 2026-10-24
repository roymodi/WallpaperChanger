package com.wallpaper.changer

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Changes wallpaper from cache first, falling back to a bounded network download.
 * A single cached image is applied to both screens in one WallpaperManager call.
 */
class WallpaperWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        return try {
            val prefs = context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
            val target = prefs.getString("target_screen", "home") ?: "home"
            val db = DatabaseHelper(context)
            val cache = CacheManager(context)
            val network = NetworkHelper()

            var cached = WallpaperPrefetcher.getInstantWallpaper(context)
            if (cached == null) {
                cached = downloadFreshWallpaper(context, db, cache, network)
            }
            if (cached == null) return if (runAttemptCount >= MAX_RETRIES) Result.failure() else Result.retry()

            val bitmap = decodeWallpaper(cached.file.absolutePath) ?: run {
                cache.consumeWallpaper(cached)
                return Result.retry()
            }
            val flags = when (target) {
                "lock" -> WallpaperManager.FLAG_LOCK
                "both" -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                else -> WallpaperManager.FLAG_SYSTEM
            }

            val applied = try {
                val manager = WallpaperManager.getInstance(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    manager.setBitmap(bitmap, null, true, flags)
                } else {
                    if (target == "lock") return Result.failure()
                    manager.setBitmap(bitmap)
                }
                true
            } catch (_: Exception) {
                false
            } finally {
                bitmap.recycle()
            }

            if (!applied) return Result.retry()
            db.logWallpaper(cached.imageUrl, cached.imageUrl.substringAfterLast("/"), cached.sourceUrl)
            cache.consumeWallpaper(cached)
            // Cache refill is handled by a separate network- and battery-constrained worker.
            Result.success()
        } catch (_: Exception) {
            if (runAttemptCount >= MAX_RETRIES) Result.failure() else Result.retry()
        }
    }

    private fun decodeWallpaper(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_DECODE_DIMENSION ||
            bounds.outHeight / sample > MAX_DECODE_DIMENSION) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(path, options)
    }

    private suspend fun downloadFreshWallpaper(
        context: Context,
        db: DatabaseHelper,
        cache: CacheManager,
        network: NetworkHelper,
        excludeIdentifiers: Set<String> = emptySet()
    ): CacheManager.CachedWallpaper? {
        val prefs = context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
        val sources = (PreloadedUrls.ALL_URLS +
            (prefs.getStringSet("custom_urls", emptySet()) ?: emptySet())).distinct().shuffled()
        for (source in sources.take(MAX_SOURCE_ATTEMPTS)) {
            val imageUrls = network.scrapeImageUrls(source).shuffled().take(MAX_IMAGE_ATTEMPTS_PER_SOURCE)
            for (url in imageUrls) {
                if (excludeIdentifiers.contains(url) || db.isWallpaperUsed(url) || cache.getCachedFile(url) != null) continue
                val bytes = network.downloadBytes(url) ?: continue
                val saved = cache.saveToCache(url, source, bytes)
                if (saved != null) return saved
            }
        }
        return null
    }

    companion object {
        private const val MAX_DECODE_DIMENSION = 4096
        private const val MAX_SOURCE_ATTEMPTS = 4
        private const val MAX_IMAGE_ATTEMPTS_PER_SOURCE = 5
        private const val MAX_RETRIES = 2
    }
}