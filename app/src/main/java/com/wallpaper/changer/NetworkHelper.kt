package com.wallpaper.changer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Finds wallpaper image links and downloads one image only when a scheduled run is due.
 */
class NetworkHelper {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun scrapeImageUrls(pageUrl: String): List<String> = withContext(Dispatchers.IO) {
        if (isDirectImageUrl(pageUrl)) return@withContext listOf(pageUrl)
        try {
            val document: Document = Jsoup.connect(pageUrl)
                .userAgent("Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
                .timeout(8000)
                .get()

            val imageUrls = mutableListOf<String>()
            val selectors = listOf(
                "img[src]", "img[data-src]", "figure img", ".wallpaper img",
                ".thumb img", "a[href$=.jpg] img"
            )
            for (selector in selectors) {
                for (element in document.select(selector)) {
                    val src = element.attr("abs:src")
                        .ifEmpty { element.attr("abs:data-src") }
                        .ifEmpty { element.attr("abs:data-original") }
                    if (src.isNotBlank() && isValidImageUrl(src)) imageUrls.add(src)
                }
                if (imageUrls.isNotEmpty()) break
            }
            imageUrls.distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun downloadBytes(imageUrl: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(imageUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                val maxBytes = 15 * 1024 * 1024
                if (body.contentLength() > maxBytes.toLong()) return@withContext null

                body.byteStream().use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) return@withContext null
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isDirectImageUrl(url: String): Boolean {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return false
        val path = parsed.path.lowercase()
        return listOf(".jpg", ".jpeg", ".png", ".webp", ".bmp").any { path.endsWith(it) } ||
            parsed.host.equals("images.unsplash.com", ignoreCase = true)
    }

    private fun isValidImageUrl(url: String): Boolean {
        val lower = url.lowercase()
        return (lower.contains(".jpg") || lower.contains(".jpeg") ||
            lower.contains(".png") || lower.contains(".webp")) &&
            !lower.contains("icon") && !lower.contains("logo") &&
            !lower.contains("avatar") && !lower.contains("thumb")
    }
}
