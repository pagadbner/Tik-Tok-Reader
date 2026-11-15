package com.myclivebot.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Reads the public TikTok profile page only to display its public avatar in Home.
 * It is independent from LIVE connection, authentication and reader services.
 */
object TikTokProfileLookup {
    private val client = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun fetchAvatarUrl(username: String): String? = withContext(Dispatchers.IO) {
        val clean = username.trim().removePrefix("@").substringBefore("/").takeIf {
            it.matches(Regex("[A-Za-z0-9._]{1,24}"))
        } ?: return@withContext null

        runCatching {
            val request = Request.Builder()
                .url("https://www.tiktok.com/@$clean")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "es-MX,es;q=0.9,en;q=0.7")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()?.let(::extractAvatarUrl)
            }
        }.getOrNull()
    }

    internal fun extractAvatarUrl(html: String): String? {
        val raw = Regex("\"avatarLarger\"\\s*:\\s*\"([^\"]+)\"")
            .find(html)?.groupValues?.getOrNull(1) ?: return null
        val url = raw
            .replace("\\\\u002F", "/")
            .replace("\\u002F", "/")
            .replace("\\\\u0026", "&")
            .replace("\\u0026", "&")
            .replace("\\\\u003D", "=")
            .replace("\\u003D", "=")
            .replace("\\\\u003F", "?")
            .replace("\\u003F", "?")
            .replace("\\\\/", "/")
            .replace("\\/", "/")
            .replace("\\", "")
        val parsed = runCatching { URI(url) }.getOrNull() ?: return null
        val host = parsed.host.orEmpty().lowercase()
        return url.takeIf {
            parsed.scheme == "https" &&
                (host.endsWith("tiktokcdn.com") || host.endsWith("tiktokcdn-us.com") ||
                    host.endsWith("byteoversea.com") || host.endsWith("ibytedtos.com"))
        }
    }
}
