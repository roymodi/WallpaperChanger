package com.wallpaper.changer

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Small bounded cache: at most five images and 40 MiB total.
 * Files are written atomically so interrupted downloads do not leave partial images.
 */
class CacheManager(context: Context) {
    private val cacheDir = File(context.cacheDir, "wallpaper_cache").apply { mkdirs() }

    data class CachedWallpaper(val file: File, val imageUrl: String, val sourceUrl: String)

    fun getCurrentCacheSize(): Long =
        cacheDir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    fun getReadyCount(): Int =
        cacheDir.listFiles()?.count { it.isFile && it.name.endsWith(".jpg") && it.length() > 0 } ?: 0

    fun getCachedFile(imageUrl: String): File? {
        val file = File(cacheDir, filename(imageUrl))
        return file.takeIf { it.isFile && it.length() > 0 }
    }

    @Synchronized
    fun saveToCache(imageUrl: String, sourceUrl: String, bytes: ByteArray): CachedWallpaper? {
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
        return try {
            val id = identifier(imageUrl)
            val file = File(cacheDir, "${PREFIX}$id.jpg")
            val temp = File(cacheDir, "${PREFIX}$id.tmp")
            temp.writeBytes(bytes)
            if (file.exists()) file.delete()
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
            val meta = JSONObject().apply {
                put("imageUrl", imageUrl)
                put("sourceUrl", sourceUrl)
            }
            val metaTemp = File(cacheDir, "${PREFIX}$id.meta.tmp")
            metaTemp.writeText(meta.toString())
            val metaFile = metaFileFor(file)
            if (metaFile.exists()) metaFile.delete()
            if (!metaTemp.renameTo(metaFile)) {
                metaTemp.copyTo(metaFile, overwrite = true)
                metaTemp.delete()
            }
            file.setLastModified(System.currentTimeMillis())
            enforceCacheLimit()
            if (!file.exists()) null else CachedWallpaper(file, imageUrl, sourceUrl)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun pickUnusedWallpaper(
        excludeIdentifiers: Set<String>,
        dbHelper: DatabaseHelper
    ): CachedWallpaper? {
        val files = cacheDir.listFiles()?.filter { it.isFile && it.name.endsWith(".jpg") } ?: return null
        val candidates = files.mapNotNull { file ->
            val meta = metaFileFor(file)
            if (!meta.exists()) return@mapNotNull null
            try {
                val json = JSONObject(meta.readText())
                val imageUrl = json.getString("imageUrl")
                val sourceUrl = json.getString("sourceUrl")
                if (excludeIdentifiers.contains(imageUrl) || dbHelper.isWallpaperUsed(imageUrl)) {
                    null
                } else CachedWallpaper(file, imageUrl, sourceUrl)
            } catch (_: Exception) {
                null
            }
        }
        return candidates.randomOrNull()?.also { it.file.setLastModified(System.currentTimeMillis()) }
    }

    @Synchronized
    fun consumeWallpaper(cached: CachedWallpaper) {
        cached.file.delete()
        metaFileFor(cached.file).delete()
    }

    @Synchronized
    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    @Synchronized
    private fun enforceCacheLimit() {
        val images = cacheDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".jpg") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        var total = 0L
        var kept = 0
        for (file in images) {
            val size = file.length()
            if (kept < MAX_IMAGES && total + size <= MAX_CACHE_BYTES) {
                total += size
                kept++
            } else {
                file.delete()
                metaFileFor(file).delete()
            }
        }
        // Clean up incomplete temporary files and orphaned metadata.
        cacheDir.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    private fun filename(url: String) = "${PREFIX}${identifier(url)}.jpg"
    private fun metaFileFor(file: File) = File(cacheDir, file.name.removeSuffix(".jpg") + ".meta")

    private fun identifier(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }

    companion object {
        private const val PREFIX = "wp_"
        private const val MAX_IMAGES = 5
        private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        private const val MAX_CACHE_BYTES = 40L * 1024 * 1024
    }
}