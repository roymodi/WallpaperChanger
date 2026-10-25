package com.wallpaper.changer

import android.content.Context
import com.wallpaper.changer.data.AppDatabase
import com.wallpaper.changer.data.WallpaperLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persistent history used to prevent a previously applied image from being reused.
 * Stores image URL, filename, source page and SHA-256 content fingerprint.
 */
class DatabaseHelper(context: Context) {

    private val dao = AppDatabase.getInstance(context).wallpaperLogDao()

    suspend fun isWallpaperUsed(identifier: String): Boolean =
        withContext(Dispatchers.IO) { dao.exists(identifier) }

    suspend fun isContentHashUsed(contentHash: String): Boolean =
        withContext(Dispatchers.IO) { dao.existsContentHash(contentHash) }

    suspend fun logWallpaper(
        identifier: String,
        imageName: String,
        sourceUrl: String,
        contentHash: String
    ) {
        withContext(Dispatchers.IO) {
            dao.insert(WallpaperLog(
                identifier = identifier,
                imageName = imageName,
                sourceUrl = sourceUrl,
                contentHash = contentHash
            ))
        }
    }
}
