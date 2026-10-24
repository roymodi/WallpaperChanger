package com.wallpaper.changer

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Uses cached images first. In "both" mode, it deliberately applies two different
 * images so Home and Lock never receive the same wallpaper during this change.
 */
class WallpaperWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val db = DatabaseHelper(context)
        val cache = CacheManager(context)

        return try {
            val prefs = context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
            val target = prefs.getString("target_screen", "home") ?: "home"

            var first = WallpaperPrefetcher.getInstantWallpaper(context)
            if (first == null) {
                first = downloadFreshWallpaper(context, db, cache, NetworkHelper())
            }
            if (first == null) {
                return if (runAttemptCount >= MAX_RETRIES) Result.failure() else Result.retry()
            }

            if (target != "both") {
                val bitmap = decodeWallpaper(first.file.absolutePath) ?: run {
                    cache.consumeWallpaper(first)
                    return if (runAttemptCount >= MAX_RETRIES) Result.failure() else Result.retry()
                }
                val flag = if (target == "lock") WallpaperManager.FLAG_LOCK else WallpaperManager.FLAG_SYSTEM
                val applied = try {
                    setWallpaper(context, bitmap, flag)
                } finally {
                    bitmap.recycle()
                }
                if (!applied) return retryOrFail()
                db.logWallpaper(first.imageUrl, first.imageUrl.substringAfterLast("/"), first.sourceUrl)
                cache.consumeWallpaper(first)
                enqueueRefill(context)
                return Result.success()
            }

            // Both screens selected: get a second, different, never-before-used image.
            var second = WallpaperPrefetcher.getInstantWallpaper(
                context,
                excludeIdentifiers = setOf(first.imageUrl)
            )
            if (second == null) {
                WallpaperPrefetcher.topUp(context)
                second = WallpaperPrefetcher.getInstantWallpaper(
                    context,
                    excludeIdentifiers = setOf(first.imageUrl)
                )
            }
            if (second == null) return retryOrFail()

            val homeBitmap = decodeWallpaper(first.file.absolutePath)
            val lockBitmap = decodeWallpaper(second.file.absolutePath)
            if (homeBitmap == null || lockBitmap == null) {
                homeBitmap?.recycle()
                lockBitmap?.recycle()
                if (homeBitmap == null) cache.consumeWallpaper(first)
                if (lockBitmap == null) cache.consumeWallpaper(second)
                return retryOrFail()
            }

            try {
                // Android offers no atomic API for two different images; decode both
                // before changing either screen to minimize the chance of a partial update.
                if (!setWallpaper(context, homeBitmap, WallpaperManager.FLAG_SYSTEM)) {
                    return retryOrFail()
                }
                db.logWallpaper(first.imageUrl, first.imageUrl.substringAfterLast("/"), first.sourceUrl)
                cache.consumeWallpaper(first)

                if (!setWallpaper(context, lockBitmap, WallpaperManager.FLAG_LOCK)) {
                    enqueueRefill(context)
                    return retryOrFail()
                }
                db.logWallpaper(second.imageUrl, second.imageUrl.substringAfterLast("/"), second.sourceUrl)
                cache.consumeWallpaper(second)
            } finally {
                homeBitmap.recycle()
                lockBitmap.recycle()
            }

            enqueueRefill(context)
            Result.success()
        } catch (_: Exception) {
            retryOrFail()
        }
    }

    private fun setWallpaper(context: Context, bitmap: Bitmap, flags: Int): Boolean {
        return try {
            val manager = WallpaperManager.getInstance(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                manager.setBitmap(bitmap, null, true, flags)
            } else {
                if (flags == WallpaperManager.FLAG_LOCK) return false
                manager.setBitmap(bitmap)
            }
            true
        } catch (_: Exception) {
            false
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
        network: NetworkHelper
    ): CacheManager.CachedWallpaper? {
        val prefs = context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
        val sources = (PreloadedUrls.ALL_URLS +
            (prefs.getStringSet("custom_urls", emptySet()) ?: emptySet())).distinct().shuffled()
        for (source in sources.take(MAX_SOURCE_ATTEMPTS)) {
            val imageUrls = network.scrapeImageUrls(source).shuffled().take(MAX_IMAGE_ATTEMPTS_PER_SOURCE)
            for (url in imageUrls) {
                if (db.isWallpaperUsed(url) || cache.getCachedFile(url) != null) continue
                val bytes = network.downloadBytes(url) ?: continue
                val saved = cache.saveToCache(url, source, bytes)
                if (saved != null) return saved
            }
        }
        return null
    }

    private fun enqueueRefill(context: Context) {
        val request = androidx.work.OneTimeWorkRequestBuilder<PrefetchWorker>()
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .addTag("wallpaper_prefetch")
            .build()
        androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
            "wallpaper_prefetch",
            androidx.work.ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun retryOrFail(): Result =
        if (runAttemptCount >= MAX_RETRIES) Result.failure() else Result.retry()

    companion object {
        private const val MAX_DECODE_DIMENSION = 4096
        private const val MAX_SOURCE_ATTEMPTS = 4
        private const val MAX_IMAGE_ATTEMPTS_PER_SOURCE = 5
        private const val MAX_RETRIES = 2
    }
}