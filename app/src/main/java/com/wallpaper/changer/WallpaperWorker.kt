package com.wallpaper.changer

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.security.MessageDigest

class WallpaperWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val prefs = context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)

        if (!prefs.getBoolean("schedule_enabled", false)) return@withContext Result.success()

        val networkMode = prefs.getString("network_mode", "any") ?: "any"
        if (!isAllowedNetwork(context, networkMode)) {
            saveStatus(context, "Selected network is not active; waiting for next schedule")
            return@withContext Result.success()
        }

        val target = prefs.getString("target_screen", "home") ?: "home"
        val history = DatabaseHelper(context)
        val urlPool = (PreloadedUrls.ALL_URLS +
            (prefs.getStringSet("custom_urls", emptySet()) ?: emptySet())).distinct().shuffled()

        if (urlPool.isEmpty()) {
            saveStatus(context, "No wallpaper URLs configured")
            return@withContext Result.success()
        }

        val usedThisRun = mutableSetOf<String>()
        val first = findUnusedWallpaper(history, urlPool, usedThisRun)
        if (first == null) {
            saveStatus(context, "No unused wallpaper found; repeats are disabled")
            return@withContext Result.success()
        }

        if (target == "both") {
            val second = findUnusedWallpaper(history, urlPool, usedThisRun)
            if (second == null) {
                first.bitmap.recycle()
                saveStatus(context, "Could not find two different unused images")
                return@withContext Result.success()
            }

            val homeApplied = applyBitmap(context, first.bitmap, "home")
            if (homeApplied) history.logWallpaper(first.identifier, first.imageName, first.sourceUrl, first.contentHash)
            val lockApplied = applyBitmap(context, second.bitmap, "lock")
            if (lockApplied) history.logWallpaper(second.identifier, second.imageName, second.sourceUrl, second.contentHash)

            first.bitmap.recycle()
            second.bitmap.recycle()
            val success = homeApplied && lockApplied
            saveStatus(context, if (success) "Both screens updated with different images" else "Wallpaper apply failed")
            return@withContext if (success) Result.success() else Result.retry()
        }

        val applied = applyBitmap(context, first.bitmap, target)
        if (applied) {
            history.logWallpaper(first.identifier, first.imageName, first.sourceUrl, first.contentHash)
        }
        first.bitmap.recycle()
        saveStatus(context, if (applied) "Wallpaper changed" else "Wallpaper apply failed")
        if (applied) Result.success() else Result.retry()
    }

    private data class DownloadedWallpaper(
        val identifier: String,
        val imageName: String,
        val sourceUrl: String,
        val contentHash: String,
        val bitmap: Bitmap
    )

    private suspend fun findUnusedWallpaper(
        history: DatabaseHelper,
        sourceUrls: List<String>,
        usedThisRun: MutableSet<String>
    ): DownloadedWallpaper? {
        val network = NetworkHelper()
        // Scrape at most ten pages per run to limit network, CPU and battery use.
        for (sourceUrl in sourceUrls.shuffled().take(minOf(10, sourceUrls.size))) {
            for (imageUrl in network.scrapeImageUrls(sourceUrl).shuffled()) {
                val identifier = canonicalIdentifier(imageUrl)
                if (identifier in usedThisRun) continue
                if (history.isWallpaperUsed(identifier) || history.isWallpaperUsed(imageUrl)) continue

                val bytes = network.downloadBytes(imageUrl) ?: continue
                val contentHash = sha256(bytes)
                if (contentHash in usedThisRun || history.isContentHashUsed(contentHash)) continue

                val bitmap = decodeSampledBitmap(bytes) ?: continue
                usedThisRun.add(identifier)
                usedThisRun.add(contentHash)
                val name = runCatching {
                    URL(imageUrl).path.substringAfterLast('/').ifBlank { URL(imageUrl).host }
                }.getOrDefault("wallpaper")
                return DownloadedWallpaper(identifier, name, sourceUrl, contentHash, bitmap)
            }
        }
        return null
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun canonicalIdentifier(url: String): String {
        return runCatching {
            val parsed = URL(url)
            parsed.protocol.lowercase() + "://" + parsed.host.lowercase() +
                (if (parsed.port == -1) "" else ":" + parsed.port) + parsed.path
        }.getOrDefault(url.substringBefore('#').substringBefore('?'))
    }

    private fun decodeSampledBitmap(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val maxDimension = 2048
        var sample = 1
        while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun applyBitmap(context: Context, bitmap: Bitmap, target: String): Boolean {
        return try {
            val manager = WallpaperManager.getInstance(context)
            val flags = if (target == "lock") WallpaperManager.FLAG_LOCK else WallpaperManager.FLAG_SYSTEM
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                manager.setBitmap(bitmap, null, true, flags)
            } else {
                manager.setBitmap(bitmap)
            }
            true
        } catch (e: Exception) {
            Log.e("WallpaperWorker", "Failed to apply $target wallpaper", e)
            false
        }
    }

    private fun isAllowedNetwork(context: Context, mode: String): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return when (mode) {
            "wifi" -> caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            "mobile" -> caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            else -> caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        }
    }

    private fun saveStatus(context: Context, status: String) {
        context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
            .edit().putString("last_result", status).apply()
        Log.i("WallpaperWorker", status)
    }
}
