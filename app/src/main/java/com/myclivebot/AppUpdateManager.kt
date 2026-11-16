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

internal fun isNewerAppVersion(installedVersionCode: Long, publishedVersionCode: Long): Boolean =
    publishedVersionCode > installedVersionCode

internal fun parsePublishedVersionCode(releaseBody: String): Long? =
    Regex("(?im)^\\s*VersionCode\\s*[:=]\\s*(\\d+)\\s*$")
        .find(releaseBody)?.groupValues?.get(1)?.toLongOrNull()

internal object AppUpdateManager {
    private const val RELEASE_API = "https://api.github.com/repos/pagadbner/TikLBot/releases/tags/updater-test-v0.13.7"
    private const val RELEASE_ASSET_PREFIX = "https://github.com/pagadbner/TikLBot/releases/download/"
    private const val APK_ASSET_NAME = "TikLBot-latest.apk"
    private const val CHECKSUM_ASSET_NAME = "TikLBot-latest.apk.sha256"
    private const val MAX_APK_BYTES = 250L * 1024L * 1024L

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
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
            if (!response.isSuccessful) {
                val detail = response.body?.string()?.take(240)?.takeIf { it.isNotBlank() }
                error("GitHub respondió HTTP ${response.code}${detail?.let { ": $it" }.orEmpty()}")
            }
            JSONObject(response.body?.string()?.takeIf { it.isNotBlank() }
                ?: error("GitHub devolvió una respuesta vacía"))
        }

        if (release.optBoolean("draft", true)) return null

        val body = release.optString("body")
        val code = parsePublishedVersionCode(body)
            ?: error("La publicación oficial no incluye un VersionCode válido.")

        val name = Regex("(?im)^\\s*VersionName\\s*[:=]\\s*([^\\r\\n]+)")
            .find(body)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
            ?: release.optString("name").takeIf { it.isNotBlank() }
            ?: release.optString("tag_name").takeIf { it.isNotBlank() }
            ?: error("La publicación oficial no identifica la versión.")

        val installed = if (Build.VERSION.SDK_INT >= 28) {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
        }
        if (!isNewerAppVersion(installed, code)) return null

        val assets = release.optJSONArray("assets") ?: error("La publicación no contiene archivos.")
        var apkUrl: String? = null
        var checksumUrl: String? = null
        var apkSize = 0L
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("state") != "uploaded") continue
            when (asset.optString("name")) {
                APK_ASSET_NAME -> {
                    apkUrl = asset.optString("browser_download_url")
                    apkSize = asset.optLong("size", 0L)
                }
                CHECKSUM_ASSET_NAME -> checksumUrl = asset.optString("browser_download_url")
            }
        }
        if (apkSize <= 0L || apkSize > MAX_APK_BYTES) {
            error("El tamaño de la APK publicada no es válido.")
        }

        val verifiedApkUrl = apkUrl?.takeIf { it.startsWith(RELEASE_ASSET_PREFIX) }
            ?: error("No se encontró la APK oficial publicada.")
        val verifiedChecksumUrl = checksumUrl?.takeIf { it.startsWith(RELEASE_ASSET_PREFIX) }
            ?: error("No se encontró el archivo SHA-256 oficial.")

        return AppUpdateInfo(code, name, verifiedApkUrl, verifiedChecksumUrl, body)
    }

    fun downloadAndVerify(context: Context, update: AppUpdateInfo): File {
        val folder = File(context.cacheDir, "updates").apply {
            if (!exists() && !mkdirs()) error("No se pudo preparar la carpeta de actualización.")
        }
        val apk = File(folder, "TikLBot-update-${update.versionCode}.apk")
        val temporaryApk = File(folder, "TikLBot-update-${update.versionCode}.apk.part")
        val digest = MessageDigest.getInstance("SHA-256")

        try {
            val apkRequest = Request.Builder()
                .url(update.apkUrl)
                .header("User-Agent", "TikLBot-Android-Updater")
                .get()
                .build()
            client.newCall(apkRequest).execute().use { response ->
                if (!response.isSuccessful) error("No se pudo descargar la APK (HTTP ${response.code}).")
                val body = response.body ?: error("GitHub devolvió una descarga vacía.")
                val declaredLength = body.contentLength()
                if (declaredLength == 0L || declaredLength > MAX_APK_BYTES) {
                    error("El tamaño de la descarga no es válido.")
                }
                body.byteStream().use { input ->
                    FileOutputStream(temporaryApk).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_APK_BYTES) error("La APK supera el tamaño permitido.")
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
            }
            if (!temporaryApk.isFile || temporaryApk.length() <= 0L) {
                error("La APK descargada está vacía.")
            }

            val checksumRequest = Request.Builder()
                .url(update.checksumUrl)
                .header("User-Agent", "TikLBot-Android-Updater")
                .get()
                .build()
            val expected = client.newCall(checksumRequest).execute().use { response ->
                if (!response.isSuccessful) error("No se pudo verificar SHA-256 (HTTP ${response.code}).")
                Regex("(?i)\\b[0-9a-f]{64}\\b").find(response.body?.string().orEmpty())?.value
                    ?: error("El archivo SHA-256 publicado no contiene un hash válido.")
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected, ignoreCase = true)) {
                error("La verificación SHA-256 falló. La APK se descartó por seguridad.")
            }

            val archiveInfo = context.packageManager.getPackageArchiveInfo(temporaryApk.absolutePath, 0)
                ?: error("El archivo descargado no es una APK Android válida.")
            val archiveVersion = if (Build.VERSION.SDK_INT >= 28) archiveInfo.longVersionCode
                else {
                    @Suppress("DEPRECATION")
                    archiveInfo.versionCode.toLong()
                }
            if (archiveInfo.packageName != context.packageName) {
                error("La APK no pertenece a TikLBot. Instalación cancelada.")
            }
            if (archiveVersion != update.versionCode) {
                error("La versión de la APK no coincide con la publicación. Instalación cancelada.")
            }

            if (apk.exists() && !apk.delete()) error("No se pudo reemplazar una descarga anterior.")
            if (!temporaryApk.renameTo(apk)) error("No se pudo preparar la APK verificada para instalar.")
            return apk
        } catch (error: Exception) {
            temporaryApk.delete()
            apk.delete()
            throw error
        }
    }

    fun installOrOpenPermissionSettings(context: Context, apk: File): Boolean {
        require(apk.isFile && apk.length() > 0L) { "No hay una APK verificada disponible." }
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
