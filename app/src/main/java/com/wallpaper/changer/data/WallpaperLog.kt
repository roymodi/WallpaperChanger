package com.wallpaper.changer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "wallpaper_log")
data class WallpaperLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val identifier: String,        // Unique image URL or hash
    val imageName: String,         // Filename or descriptive name
    val sourceUrl: String,         // The website URL it came from
    val timestamp: Long = System.currentTimeMillis()
)