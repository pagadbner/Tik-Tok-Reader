package com.myclivebot.windows

import com.myclivebot.core.TtwidCookieParser
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.web.WebView
import java.io.IOException
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

/**
 * Anonymous TikTok cookie recovery for Windows.
 *
 * Installs a public JDK CookieManager before JavaFX WebKit starts so response
 * cookies (including HttpOnly cookies when delivered through CookieHandler)
 * can be read without JavaScript or exposing a browser window.
 */
object DesktopTtwidCookieProvider {
    private const val HOME_URL = "https://www.tiktok.com/"
    private const val DEFAULT_TIMEOUT_MS = 30_000L

    @Volatile private var cookieManager: CookieManager? = null
    private val installLock = Any()

    fun install(): Boolean = synchronized(installLock) {
        cookieManager?.let { return@synchronized true }
        val current = CookieHandler.getDefault()
        val manager = when (current) {
            null -> CookieManager(null, CookiePolicy.ACCEPT_ALL).also { CookieHandler.setDefault(it) }
            is CookieManager -> current
            else -> return@synchronized false
        }
        manager.setCookiePolicy(CookiePolicy.ACCEPT_ALL)
        cookieManager = manager
        true
    }

    fun acquireTtwid(timeoutMs: Long = DEFAULT_TIMEOUT_MS): String {
        require(timeoutMs in 5_000L..60_000L) { "El tiempo de espera de cookies debe estar entre 5 y 60 segundos." }
        check(!Platform.isFxApplicationThread()) { "La recuperación ttwid debe ejecutarse fuera del hilo JavaFX." }
        val manager = cookieManager ?: (if (install()) cookieManager else null)
            ?: throw IOException("No se pudo preparar el almacén seguro de cookies del navegador.")
        val panelRef = AtomicReference<JFXPanel?>()
        val viewRef = AtomicReference<WebView?>()
        val loadFinished = CountDownLatch(1)
        val loadFailure = AtomicReference<String?>()

        try {
            if (SwingUtilities.isEventDispatchThread()) {
                panelRef.set(JFXPanel())
            } else {
                SwingUtilities.invokeAndWait { panelRef.set(JFXPanel()) }
            }
            Platform.setImplicitExit(false)
            Platform.runLater {
                try {
                    val view = WebView()
                    viewRef.set(view)
                    panelRef.get()?.scene = Scene(view)
                    view.engine.userAgent =
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
                    view.engine.loadWorker.stateProperty().addListener { _, _, state ->
                        if (state == Worker.State.SUCCEEDED || state == Worker.State.FAILED || state == Worker.State.CANCELLED) {
                            if (state != Worker.State.SUCCEEDED) {
                                loadFailure.set(view.engine.loadWorker.exception?.message ?: "TikTok no completó la carga del navegador.")
                            }
                            loadFinished.countDown()
                        }
                    }
                    view.engine.load(HOME_URL)
                } catch (error: Exception) {
                    loadFailure.set(error.message ?: "No se pudo iniciar el navegador integrado.")
                    loadFinished.countDown()
                }
            }

            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            var loaded = false
            while (System.nanoTime() < deadline) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) break
                if (!loaded) {
                    loaded = loadFinished.await(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(300)), TimeUnit.NANOSECONDS)
                } else {
                    Thread.sleep(250)
                }
                findTtwid(manager.cookieStore.cookies)?.let { return it }
                if (loaded && loadFailure.get() != null) {
                    throw IOException("No se pudo cargar TikTok en el navegador integrado: ${loadFailure.get()}")
                }
            }
            throw IOException(
                loadFailure.get()
                    ?: "TikTok no entregó una cookie ttwid válida mediante el navegador integrado durante el tiempo de espera."
            )
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Se interrumpió la recuperación de la cookie ttwid.", error)
        } finally {
            Platform.runLater {
                runCatching { viewRef.get()?.engine?.load("about:blank") }
                runCatching { panelRef.get()?.scene = null }
            }
        }
    }

    internal fun findTtwidCookie(cookies: List<HttpCookie>): String? =
        cookies.asSequence()
            .filter { it.name.equals("ttwid", ignoreCase = true) && !it.hasExpired() }
            .mapNotNull { TtwidCookieParser.validate(it.value) }
            .firstOrNull()
}
