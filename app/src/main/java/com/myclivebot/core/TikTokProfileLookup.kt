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

    suspend fun fetchLiveStatus(username: String): Boolean? = withContext(Dispatchers.IO) {
        val clean = username.trim().removePrefix("@").substringBefore("/").takeIf {
            it.matches(Regex("[A-Za-z0-9._]{1,24}"))
        } ?: return@withContext null

        runCatching {
            val url = okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("www.tiktok.com")
                .addPathSegments("api-live/user/room/")
                .addQueryParameter("aid", "1988")
                .addQueryParameter("app_name", "tiktok_web")
                .addQueryParameter("device_platform", "web_pc")
                .addQueryParameter("uniqueId", clean)
                .addQueryParameter("sourceType", "54")
                .build()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36")
                .header("Accept", "application/json")
                .header("Accept-Language", "es-MX,es;q=0.9,en;q=0.8")
                .header("Referer", "https://www.tiktok.com/@$clean/live")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()?.let(::parseLiveStatus)
            }
        }.getOrNull()
    }

    internal fun parseLiveStatus(body: String): Boolean? = runCatching {
        val json = org.json.JSONObject(body)
        if (json.optInt("statusCode", 0) != 0) return@runCatching null
        val data = json.optJSONObject("data") ?: return@runCatching null
        val user = data.optJSONObject("user") ?: return@runCatching null
        val liveRoom = data.optJSONObject("liveRoom") ?: return@runCatching false
        val roomId = user.optString("roomId")
        roomId.isNotBlank() && roomId != "0" && liveRoom.optInt("status", 4) == 2
    }.getOrNull()

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
