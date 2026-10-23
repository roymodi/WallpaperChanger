package com.wallpaper.changer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Random

/**
 * Manages a rotating buffer of TARGET_BUFFER_SIZE wallpapers.
 *
 * Behavior:
 *  - topUp() fills the buffer to 5 wallpapers (safe to call anytime)
 *  - Uses a Mutex so only one topUp() runs at a time (prevents duplicate downloads)
 *  - Skips already-used and already-cached images
 */
object WallpaperPrefetcher {

    const val TARGET_BUFFER_SIZE = 5
    private const val MAX_SCRAPE_ATTEMPTS_PER_SLOT = 3

    // Prevents concurrent topUp() calls from downloading the same image twice
    private val mutex = Mutex()

    suspend fun topUp(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val cacheManager = CacheManager(context)
            val dbHelper = DatabaseHelper(context)
            val networkHelper = NetworkHelper()
            val random = Random()

            val current = cacheManager.getReadyCount()
            val needed = TARGET_BUFFER_SIZE - current
            if (needed <= 0) return@withLock

            val prefs = context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
            val urlPool = PreloadedUrls.ALL_URLS.toMutableList()
            urlPool.addAll(prefs.getStringSet("custom_urls", emptySet()) ?: emptySet())
            if (urlPool.isEmpty()) return@withLock

            repeat(needed) {
                var success = false
                var attempt = 0

                while (!success && attempt < MAX_SCRAPE_ATTEMPTS_PER_SLOT) {
                    attempt++
                    try {
                        val sourceUrl = urlPool[random.nextInt(urlPool.size)]
                        val imageUrls = networkHelper.scrapeImageUrls(sourceUrl)
                        if (imageUrls.isEmpty()) continue

                        val available = imageUrls.filter { url ->
                            !dbHelper.isWallpaperUsed(url) &&
                            cacheManager.getCachedFile(url) == null
                        }
                        if (available.isEmpty()) continue

                        val chosenUrl = available[random.nextInt(available.size)]
                        val bytes = networkHelper.downloadBytes(chosenUrl) ?: continue

                        val saved = cacheManager.saveToCache(chosenUrl, sourceUrl, bytes)
                        if (saved != null) success = true
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    suspend fun getInstantWallpaper(
        context: Context,
        excludeIdentifiers: Set<String> = emptySet()
    ): CacheManager.CachedWallpaper? = withContext(Dispatchers.IO) {
        val cacheManager = CacheManager(context)
        val dbHelper = DatabaseHelper(context)
        cacheManager.pickUnusedWallpaper(excludeIdentifiers, dbHelper)
    }
}