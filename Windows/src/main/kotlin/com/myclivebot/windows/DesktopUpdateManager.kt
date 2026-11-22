package com.myclivebot.windows

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.json.JSONArray

data class DesktopUpdateInfo(
    val versionName: String,
    val installerUrl: String,
    val checksumUrl: String
)

object DesktopUpdateManager {
    private const val RELEASE_URL = "https://api.github.com/repos/pagadbner/TikLBot/releases/tags/latest"
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    fun checkForUpdate(currentVersion: String): DesktopUpdateInfo? {
        val request = Request.Builder().url(RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "TikTokReader-Windows-Updater")
            .build()
        val json = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("GitHub respondió HTTP ${response.code}.")
            JSONObject(response.body?.string().orEmpty())
        }
        val assets = json.optJSONArray("assets") ?: return null
        return parseDesktopUpdateInfo(json.optString("body"), assets, currentVersion)
    }

    fun downloadAndVerify(update: DesktopUpdateInfo): File {
        val directory = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "Tik Tok Reader/updates")
        if (!directory.exists() && !directory.mkdirs()) error("No se pudo crear la carpeta de actualizaciones.")
        val temporary = File(directory, "TikTokReader-Windows-Setup.exe.part")
        val installer = File(directory, "TikTokReader-Windows-Setup.exe")
        val actual = MessageDigest.getInstance("SHA-256")
        try {
            val request = Request.Builder().url(update.installerUrl).header("User-Agent", "TikTokReader-Windows-Updater").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Descarga del instalador fallida (HTTP ${response.code}).")
                val body = response.body ?: error("GitHub devolvió un archivo vacío.")
                body.byteStream().use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > 700L * 1024L * 1024L) error("El instalador supera el tamaño permitido.")
                            actual.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            if (!temporary.isFile || temporary.length() < 1_000_000L) error("El instalador descargado está incompleto.")
            val checksumRequest = Request.Builder().url(update.checksumUrl).header("User-Agent", "TikTokReader-Windows-Updater").build()
            val expected = client.newCall(checksumRequest).execute().use { response ->
                if (!response.isSuccessful) error("No se pudo obtener el SHA-256 (HTTP ${response.code}).")
                Regex("(?i)\\b[0-9a-f]{64}\\b").find(response.body?.string().orEmpty())?.value
                    ?: error("El archivo SHA-256 no contiene un hash válido.")
            }
            val actualHash = actual.digest().joinToString("") { "%02x".format(it) }
            if (!actualHash.equals(expected, true)) error("El SHA-256 no coincide. Instalación cancelada.")
            // Replace the previous installer only after the new file passed SHA-256.
            // A network/checksum failure must never delete an earlier verified download.
            try {
                Files.move(
                    temporary.toPath(),
                    installer.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), installer.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return installer
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    fun launchInstaller(installer: File) {
        require(installer.isFile && installer.length() > 0L) { "No hay instalador verificado." }
        ProcessBuilder(
            installer.absolutePath,
            "/VERYSILENT",
            "/SUPPRESSMSGBOXES",
            "/NORESTART",
            "/SP-"
        ).start()
    }

    internal fun compareVersions(left: String, right: String): Int {
        val a = left.split('.').map { it.toIntOrNull() ?: 0 }
        val b = right.split('.').map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(a.size, b.size)) {
            val delta = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
            if (delta != 0) return delta
        }
        return 0
    }
}


/** Pure release-metadata parser so updater decisions can be regression-tested without network access. */
internal fun parseDesktopUpdateInfo(
    releaseBody: String,
    assets: JSONArray,
    currentVersion: String
): DesktopUpdateInfo? {
    val version = Regex("(?m)^WindowsVersionName:\\s*(\\S+)").find(releaseBody)
        ?.groupValues?.get(1) ?: return null
    if (DesktopUpdateManager.compareVersions(version, currentVersion) <= 0) return null

    var installer: String? = null
    var checksum: String? = null
    for (index in 0 until assets.length()) {
        val asset = assets.optJSONObject(index) ?: continue
        when (asset.optString("name")) {
            "TikTokReader-Windows-Setup.exe" -> installer = asset.optString("browser_download_url")
            "TikTokReader-Windows-Setup.exe.sha256" -> checksum = asset.optString("browser_download_url")
        }
    }
    if (installer.isNullOrBlank() || checksum.isNullOrBlank()) return null
    return DesktopUpdateInfo(version, installer, checksum)
}
