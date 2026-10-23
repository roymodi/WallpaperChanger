package com.wallpaper.changer

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Manages a rotating buffer of 5 wallpapers with strict 264 MB cap.
 * Each wallpaper has a companion .meta JSON with its source URL.
 */
class CacheManager(context: Context) {

    private val cacheDir: File = File(context.cacheDir, "wallpaper_cache").apply {
        if (!exists()) mkdirs()
    }

    companion object {
        private const val MAX_CACHE_SIZE_BYTES = 264L * 1024 * 1024
        private const val CACHE_FILENAME_PREFIX = "wp_"
    }

    data class CachedWallpaper(
        val file: File,
        val imageUrl: String,
        val sourceUrl: String
    )

    fun getCurrentCacheSize(): Long {
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    fun getReadyCount(): Int {
        return cacheDir.listFiles()
            ?.count { it.isFile && it.name.endsWith(".jpg") } ?: 0
    }

    fun getCachedFile(imageUrl: String): File? {
        val identifier = imageUrl.hashCode().toString()
        val file = File(cacheDir, "${CACHE_FILENAME_PREFIX}${identifier}.jpg")
        return if (file.exists()) file else null
    }

    fun enforceCacheLimit() {
        val files = cacheDir.listFiles() ?: return
        var currentSize = files.sumOf { it.length() }
        if (currentSize <= MAX_CACHE_SIZE_BYTES) return
        val sortedFiles = files.sortedBy { it.lastModified() }
        for (file in sortedFiles) {
            if (currentSize <= MAX_CACHE_SIZE_BYTES) break
            val fileSize = file.length()
            if (file.delete()) {
                currentSize -= fileSize
                getMetaFileFor(file)?.delete()
            }
        }
    }

    fun saveToCache(imageUrl: String, sourceUrl: String, bytes: ByteArray): CachedWallpaper? {
        return try {
            val identifier = imageUrl.hashCode().toString()
            val filename = "${CACHE_FILENAME_PREFIX}${identifier}.jpg"
            val file = File(cacheDir, filename)
            file.writeBytes(bytes)
            file.setLastModified(System.currentTimeMillis())

            val meta = JSONObject().apply {
                put("imageUrl", imageUrl)
                put("sourceUrl", sourceUrl)
            }
            File(cacheDir, "${CACHE_FILENAME_PREFIX}${identifier}.meta")
                .writeText(meta.toString())

            enforceCacheLimit()
            CachedWallpaper(file, imageUrl, sourceUrl)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun pickUnusedWallpaper(
        excludeIdentifiers: Set<String>,
        dbHelper: DatabaseHelper
    ): CachedWallpaper? {
        val files = cacheDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".jpg") }
            ?: return null

        val candidates = files.mapNotNull { jpg ->
            val meta = getMetaFileFor(jpg) ?: return@mapNotNull null
            try {
                val json = JSONObject(meta.readText())
                val imageUrl = json.getString("imageUrl")
                val sourceUrl = json.getString("sourceUrl")
                if (excludeIdentifiers.contains(imageUrl)) return@mapNotNull null
                if (dbHelper.isWallpaperUsed(imageUrl)) return@mapNotNull null
                CachedWallpaper(jpg, imageUrl, sourceUrl)
            } catch (e: Exception) {
                null
            }
        }

        if (candidates.isEmpty()) return null
        val chosen = candidates.random()
        chosen.file.setLastModified(System.currentTimeMillis())
        return chosen
    }

    fun consumeWallpaper(cached: CachedWallpaper) {
        cached.file.delete()
        getMetaFileFor(cached.file)?.delete()
    }

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    private fun getMetaFileFor(jpgFile: File): File? {
        val metaName = jpgFile.name.replace(".jpg", ".meta")
        val meta = File(cacheDir, metaName)
        return if (meta.exists()) meta else null
    }
}