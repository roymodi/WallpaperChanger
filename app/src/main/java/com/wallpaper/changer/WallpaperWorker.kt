package com.wallpaper.changer

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
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
            saveStatus(context, "Waiting for the selected network")
            return@withContext Result.success()
        }

        val target = prefs.getString("target_screen", "home") ?: "home"
        val localUris = prefs.getStringSet("local_image_uris", emptySet())?.toList().orEmpty()
        val history = DatabaseHelper(context)
        val sourceUrls = (PreloadedUrls.ALL_URLS +
            (prefs.getStringSet("custom_urls", emptySet()) ?: emptySet())).distinct()

        if (sourceUrls.isEmpty()) {
            saveStatus(context, "No online wallpaper sources are configured")
            return@withContext Result.success()
        }

        // Home-only always uses an online wallpaper.
        if (target == "home") {
            val online = findUnusedWallpaper(history, sourceUrls, mutableSetOf())
                ?: return@withContext noWallpaper(context)
            val applied = applyBitmap(context, online.bitmap, "home")
            if (applied) history.logWallpaper(
                online.identifier, online.imageName, online.sourceUrl, online.contentHash
            )
            online.bitmap.recycle()
            saveStatus(context, if (applied) "Home-screen wallpaper changed online" else "Home wallpaper apply failed")
            return@withContext if (applied) Result.success() else Result.retry()
        }

        val nextLocal = prefs.getBoolean("next_lock_source_local", true) && localUris.isNotEmpty()

        // Both mode always uses an online image for Home. Lock alternates local/online.
        if (target == "both") {
            val usedThisRun = mutableSetOf<String>()
            val home = findUnusedWallpaper(history, sourceUrls, usedThisRun)
                ?: return@withContext noWallpaper(context)

            var lockBitmap: Bitmap? = null
            var lockOnline: DownloadedWallpaper? = null
            var lockIsLocal = false

            if (nextLocal) {
                val localUri = localUris.random()
                lockBitmap = decodeLocalBitmap(context, localUri)
                lockIsLocal = lockBitmap != null
            }
            if (lockBitmap == null) {
                lockOnline = findUnusedWallpaper(history, sourceUrls, usedThisRun)
                lockBitmap = lockOnline?.bitmap
            }

            if (lockBitmap == null) {
                home.bitmap.recycle()
                saveStatus(context, "Could not load a Lock Screen image; check selected local images or online sources")
                return@withContext Result.retry()
            }

            val homeApplied = applyBitmap(context, home.bitmap, "home")
            if (homeApplied) {
                history.logWallpaper(home.identifier, home.imageName, home.sourceUrl, home.contentHash)
            }

            val lockApplied = applyBitmap(context, lockBitmap, "lock")
            if (lockApplied && lockOnline != null) {
                history.logWallpaper(
                    lockOnline.identifier, lockOnline.imageName, lockOnline.sourceUrl, lockOnline.contentHash
                )
            }
            if (lockApplied && localUris.isNotEmpty()) {
                prefs.edit().putBoolean("next_lock_source_local", !nextLocal).apply()
            }

            home.bitmap.recycle()
            lockBitmap.recycle()
            val success = homeApplied && lockApplied
            saveStatus(
                context,
                if (success) {
                    "Home changed online; Lock Screen changed " + if (lockIsLocal) "from a local image" else "online"
                } else "One or both wallpaper changes failed"
            )
            return@withContext if (success) Result.success() else Result.retry()
        }

        // Lock-only: alternate a random local image and an online image.
        if (nextLocal) {
            val bitmap = decodeLocalBitmap(context, localUris.random())
            if (bitmap != null) {
                val applied = applyBitmap(context, bitmap, "lock")
                bitmap.recycle()
                if (applied) {
                    prefs.edit().putBoolean("next_lock_source_local", false).apply()
                }
                saveStatus(context, if (applied) "Lock Screen changed from a local image" else "Lock Screen wallpaper apply failed")
                return@withContext if (applied) Result.success() else Result.retry()
            }
            // If a saved file was deleted or became unreadable, fall back online this run.
            saveStatus(context, "Selected local image unavailable; using online wallpaper")
        }

        val online = findUnusedWallpaper(history, sourceUrls, mutableSetOf())
            ?: return@withContext noWallpaper(context)
        val applied = applyBitmap(context, online.bitmap, "lock")
        if (applied) {
            history.logWallpaper(online.identifier, online.imageName, online.sourceUrl, online.contentHash)
            if (localUris.isNotEmpty()) prefs.edit().putBoolean("next_lock_source_local", true).apply()
        }
        online.bitmap.recycle()
        saveStatus(context, if (applied) "Lock Screen changed online" else "Lock Screen wallpaper apply failed")
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
        // Limit page scraping per run to reduce network use, CPU use and battery drain.
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

    private fun decodeLocalBitmap(context: Context, uriString: String): Bitmap? {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            } ?: return null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            val maxDimension = 2048
            while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (e: Exception) {
            Log.w("WallpaperWorker", "Could not read local image $uriString", e)
            null
        }
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

        var sample = 1
        val maxDimension = 2048
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

    private fun noWallpaper(context: Context): Result {
        saveStatus(context, "No unused online wallpaper found; check the source sites and network")
        return Result.retry()
    }

    private fun saveStatus(context: Context, status: String) {
        context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
            .edit().putString("last_result", status).apply()
        Log.i("WallpaperWorker", status)
    }
}
