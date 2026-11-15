package com.myclivebot

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class AppUpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val checksumUrl: String,
    val releaseNotes: String
)

internal object AppUpdateManager {
    private const val RELEASE_API = "https://api.github.com/repos/pagadbner/TikLBot/releases/latest"
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    fun checkForUpdate(context: Context): AppUpdateInfo? {
        val request = Request.Builder()
            .url(RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "TikLBot-Android-Updater")
            .get()
            .build()
        val release = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("GitHub respondió HTTP ${response.code}")
            JSONObject(response.body?.string() ?: error("Respuesta vacía de GitHub"))
        }
        if (release.optBoolean("draft", true) || release.optBoolean("prerelease", true)) return null
        val body = release.optString("body")
        val code = Regex("(?im)^\\s*VersionCode\\s*[:=]\\s*(\\d+)\\s*$")
            .find(body)?.groupValues?.get(1)?.toLongOrNull()
            ?: error("La publicación oficial no incluye un VersionCode válido. No se instalará una versión sin identificar.")
        val name = Regex("(?im)^\\s*VersionName\\s*[:=]\\s*([^\\r\\n]+)")
            .find(body)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: release.optString("name").takeIf { it.isNotBlank() }
            ?: release.optString("tag_name", "Nueva versión")
        val assets = release.optJSONArray("assets") ?: error("La publicación no contiene archivos")
        var apkUrl: String? = null
        var checksumUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            when (asset.optString("name")) {
                "TikLBot-latest.apk" -> apkUrl = asset.optString("browser_download_url")
                "TikLBot-latest.apk.sha256" -> checksumUrl = asset.optString("browser_download_url")
            }
        }
        val installed = if (Build.VERSION.SDK_INT >= 28) {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
        }
        // versionCode is the authoritative Android version identifier. Never compare display names.
        if (code <= installed) return null

        val assetPrefix = "https://github.com/pagadbner/TikLBot/releases/download/"
        val verifiedApkUrl = apkUrl?.takeIf { it.startsWith(assetPrefix) }
            ?: error("No se encontró la APK oficial TikLBot-latest.apk en GitHub Releases.")
        val verifiedChecksumUrl = checksumUrl?.takeIf { it.startsWith(assetPrefix) }
            ?: error("No se encontró el archivo SHA-256 oficial de la APK.")

        return AppUpdateInfo(code, name, verifiedApkUrl, verifiedChecksumUrl, body)
    }

    fun downloadAndVerify(context: Context, update: AppUpdateInfo): File {
        val folder = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(folder, "TikLBot-update-${update.versionCode}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        val apkRequest = Request.Builder().url(update.apkUrl).header("User-Agent", "TikLBot-Android-Updater").get().build()
        client.newCall(apkRequest).execute().use { response ->
            if (!response.isSuccessful) error("No se pudo descargar la APK (HTTP ${response.code})")
            val body = response.body ?: error("La descarga llegó vacía")
            body.byteStream().use { input ->
                FileOutputStream(apk).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
        }
        if (!apk.isFile || apk.length() <= 0L) error("La APK descargada está vacía")
        val expectedRequest = Request.Builder().url(update.checksumUrl).header("User-Agent", "TikLBot-Android-Updater").get().build()
        val expected = client.newCall(expectedRequest).execute().use { response ->
            if (!response.isSuccessful) error("No se pudo verificar el SHA-256 (HTTP ${response.code})")
            Regex("(?i)\\b[0-9a-f]{64}\\b").find(response.body?.string().orEmpty())?.value
                ?: error("El archivo SHA-256 no tiene un hash válido")
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) {
            apk.delete()
            error("La verificación SHA-256 falló. La APK se descartó por seguridad.")
        }
        return apk
    }

    fun installOrOpenPermissionSettings(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            val permissionIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(permissionIntent)
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val installIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(installIntent)
        return true
    }
}
