package com.wallpaper.changer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WallpaperLogDao {
    @Query("SELECT * FROM wallpaper_log ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<WallpaperLog>>

    @Query("SELECT EXISTS(SELECT 1 FROM wallpaper_log WHERE identifier = :identifier)")
    suspend fun exists(identifier: String): Boolean

    @Insert
    suspend fun insert(log: WallpaperLog)

    @Query("DELETE FROM wallpaper_log")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM wallpaper_log")
    suspend fun getCount(): Int
}