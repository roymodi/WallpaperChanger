package com.wallpaper.changer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.InputStream
import java.net.URL

/**
 * Handles HTML scraping to find image URLs and downloading image data.
 * Uses Jsoup for parsing and OkHttp for robust downloading.
 */
class NetworkHelper {

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Fetches the HTML content of a page and extracts image URLs.
     * Returns a list of absolute image URLs found on the page.
     */
    suspend fun scrapeImageUrls(pageUrl: String): List<String> = withContext(Dispatchers.IO) {
        val imageUrls = mutableListOf<String>()
        try {
            val document: Document = Jsoup.connect(pageUrl)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(15000)
                .get()

            // Generic selectors for common wallpaper sites
            // Unsplash uses figure/img with srcset; mob.org uses .wallpaper img
            val selectors = listOf(
                "img[src]",
                "img[data-src]",
                "figure img",
                ".wallpaper img",
                ".thumb img",
                "a[href\$=.jpg] img"
            )

            for (selector in selectors) {
                val elements = document.select(selector)
                for (element in elements) {
                    // Try src, then data-src, then srcset
                    val src = element.attr("abs:src").ifEmpty {
                        element.attr("abs:data-src").ifEmpty {
                            element.attr("srcset").split(" ").firstOrNull() ?: ""
                        }
                    }
                    if (src.isNotEmpty() && isValidImageUrl(src)) {
                        imageUrls.add(src)
                    }
                }
                if (imageUrls.isNotEmpty()) break // Use first working selector
            }

            // Deduplicate
            return@withContext imageUrls.distinct()
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext emptyList()
        }
    }

    /**
     * Downloads an image from a URL and returns a Bitmap.
     */
    suspend fun downloadImage(imageUrl: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(imageUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val inputStream: InputStream? = response.body?.byteStream()
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            response.close()
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Downloads raw bytes for caching without decoding.
     */
    suspend fun downloadBytes(imageUrl: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(imageUrl).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val bytes = response.body?.bytes()
            response.close()
            bytes
        } catch (e: Exception) {
            null
        }
    }

    private fun isValidImageUrl(url: String): Boolean {
        val lower = url.lowercase()
        return (lower.contains(".jpg") || lower.contains(".jpeg") ||
                lower.contains(".png") || lower.contains(".webp")) &&
                !lower.contains("icon") && !lower.contains("logo") &&
                !lower.contains("avatar") && !lower.contains("thumb")
    }
}