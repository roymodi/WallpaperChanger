package com.wallpaper.changer.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "wallpaper_log")
data class WallpaperLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val identifier: String,        // Canonical direct image URL
    val imageName: String,         // Filename or descriptive name
    val sourceUrl: String,         // Source page or custom URL
    @ColumnInfo(defaultValue = "''")
    val contentHash: String = "",  // SHA-256 fingerprint prevents duplicate files from different URLs
    val timestamp: Long = System.currentTimeMillis()
)
