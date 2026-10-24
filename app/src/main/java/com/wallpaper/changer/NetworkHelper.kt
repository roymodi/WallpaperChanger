package com.wallpaper.changer

import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads image candidates from ordinary HTML wallpaper galleries.
 * JavaScript-only galleries and sites requiring login may still need a dedicated API.
 */
class NetworkHelper {
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun scrapeImageUrls(pageUrl: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val html = response.body?.string()?.take(MAX_HTML_CHARS) ?: return@withContext emptyList()
                val doc = Jsoup.parse(html, response.request.url.toString())
                val found = linkedSetOf<String>()

                // Prefer full-resolution Open Graph / Twitter images when present.
                doc.select("meta[property=og:image], meta[name=twitter:image], meta[property=og:image:secure_url]")
                    .forEach { addCandidate(found, it.attr("content")) }

                doc.select("img[src], img[data-src], img[data-original], img[data-lazy-src], source[srcset], img[srcset]")
                    .forEach { element ->
                        listOf("src", "data-src", "data-original", "data-lazy-src")
                            .map { element.attr(it) }
                            .filter { it.isNotBlank() }
                            .forEach { addCandidate(found, element.absUrl(it)) }

                        // srcset entries are "URL width"; split on commas and keep the URL.
                        element.attr("srcset").split(',')
                            .map { it.trim().substringBefore(Regex("\\s+")) }
                            .filter { it.isNotBlank() }
                            .forEach { addCandidate(found, element.absUrl(it)) }
                    }

                found.take(MAX_CANDIDATES).toList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun downloadBytes(imageUrl: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(imageUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "image/avif,image/webp,image/png,image/jpeg,image/*;q=0.8")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                val type = body.contentType()?.type
                if (type != "image") return@withContext null
                val declaredLength = body.contentLength()
                if (declaredLength > MAX_IMAGE_BYTES) return@withContext null

                // Read in bounded chunks to avoid unbounded memory use.
                val output = java.io.ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_IMAGE_BYTES) return@withContext null
                        output.write(buffer, 0, read)
                    }
                }
                val bytes = output.toByteArray()
                if (!isDecodableImage(bytes)) null else bytes
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun addCandidate(out: MutableSet<String>, raw: String) {
        val value = raw.trim()
        if (value.isEmpty() || value.startsWith("data:", true)) return
        try {
            val url = okhttp3.HttpUrl.Companion.toHttpUrlOrNull(value) ?: return
            if (url.isHttps || url.isHttp) {
                val lower = url.encodedPath.lowercase()
                // Extension is not mandatory: many CDNs serve images without one.
                if (!lower.endsWith(".svg") && !lower.endsWith(".gif") &&
                    !url.host.contains("gravatar.com") && !url.host.contains("logo")) {
                    out.add(url.toString())
                }
            }
        } catch (_: Exception) { }
    }

    private fun isDecodableImage(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth in 320..12000 && options.outHeight in 320..12000 &&
            options.outMimeType in setOf("image/jpeg", "image/png", "image/webp", "image/avif")
    }

    companion object {
        const val MAX_IMAGE_BYTES = 8L * 1024 * 1024
        private const val MAX_HTML_CHARS = 2_000_000
        private const val MAX_CANDIDATES = 100
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
    }
}