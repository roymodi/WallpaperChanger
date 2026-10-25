package com.wallpaper.changer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface WallpaperLogDao {
    @Query("SELECT EXISTS(SELECT 1 FROM wallpaper_log WHERE identifier = :identifier)")
    suspend fun exists(identifier: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM wallpaper_log WHERE contentHash = :contentHash AND contentHash != '')")
    suspend fun existsContentHash(contentHash: String): Boolean

    @Insert
    suspend fun insert(log: WallpaperLog)
}
