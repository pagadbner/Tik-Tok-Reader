package com.myclivebot.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Gets TikTok's anonymous ttwid in an invisible WebView.
 *
 * Calls are single-flight across provider instances. A second start waits for the
 * first acquisition to finish, then rechecks CookieManager before creating a view.
 * Only callbacks owned by this acquisition are cancelled during cleanup.
 */
class TikTokWebViewCookieProvider(private val context: Context) {
    // Keep the Service context: WebView needs a fully themed Android Context, but no Activity/window.

    fun acquireTtwid(timeoutMs: Long = DEFAULT_TIMEOUT_MS): String =
        synchronized(ACQUISITION_LOCK) {
            acquireTtwidLocked(timeoutMs)
        }

    private fun acquireTtwidLocked(timeoutMs: Long): String {
        require(timeoutMs in 5_000L..60_000L) {
            "Cookie timeout must be between 5 and 60 seconds."
        }
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "ttwid acquisition must run off the Android main thread."
        }

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)

        // Reuse a cookie already stored by HTTP or a previous WebView attempt.
        extractTtwid(cookies)?.let { return it }

        val latch = CountDownLatch(1)
        val cleanupLatch = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val result = AtomicReference<String?>()
        val failure = AtomicReference<String?>()
        val webViewRef = AtomicReference<WebView?>()
        val main = Handler(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + timeoutMs

        lateinit var poll: Runnable
        lateinit var timeout: Runnable

        fun finish(value: String? = null, error: String? = null) {
            if (!completed.compareAndSet(false, true)) return
            if (value != null) result.set(value)
            if (error != null) failure.set(error)
            latch.countDown()
        }

        poll = object : Runnable {
            override fun run() {
                if (completed.get()) return
                val cookie = extractTtwid(cookies)
                if (cookie != null) {
                    finish(value = cookie)
                    return
                }
                if (System.currentTimeMillis() >= deadline) {
                    finish(
                        error = failure.get()
                            ?: "TikTok cargó, pero no entregó ttwid durante el tiempo de espera. Puede requerir consentimiento o verificación."
                    )
                    return
                }
                main.postDelayed(this, COOKIE_POLL_INTERVAL_MS)
            }
        }

        timeout = Runnable {
            finish(
                error = failure.get()
                    ?: "TikTok no completó la preparación de cookies en segundo plano."
            )
        }

        main.post {
            if (completed.get()) return@post
            val webView = try {
                WebView(context).apply {
                    visibility = View.GONE
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = false
                    settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            // Do not treat HTML completion as cookie completion.
                            main.removeCallbacks(poll)
                            main.post(poll)
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError
                        ) {
                            // Ignore subresource failures; keep polling for the main document's cookies.
                            if (request.isForMainFrame) {
                                failure.set(
                                    "TikTok informó un error de carga principal en WebView (código ${error.errorCode})."
                                )
                                main.removeCallbacks(poll)
                                main.post(poll)
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            val scheme = request.url.scheme.orEmpty()
                            return scheme != "https" && scheme != "http"
                        }
                    }
                }
            } catch (creationError: Exception) {
                finish(error = "No se pudo iniciar WebView: ${creationError.message}")
                null
            }

            if (webView != null && !completed.get()) {
                webViewRef.set(webView)
                try {
                    webView.loadUrl(HOME_URL)
                    main.post(poll)
                    main.postDelayed(timeout, timeoutMs)
                } catch (loadError: Exception) {
                    finish(error = "No se pudo cargar TikTok en WebView: ${loadError.message}")
                }
            } else if (webView != null) {
                webView.destroy()
            }
        }

        val finished = latch.await(timeoutMs + OUTER_WAIT_GRACE_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            finish(error = "Se agotó el tiempo de espera al preparar WebView.")
        }

        // Clean up only this operation's callbacks. Never clear the main Looper queue.
        main.post {
            main.removeCallbacks(poll)
            main.removeCallbacks(timeout)
            webViewRef.getAndSet(null)?.apply {
                stopLoading()
                webViewClient = WebViewClient()
                destroy()
            }
            cleanupLatch.countDown()
        }
        cleanupLatch.await(CLEANUP_WAIT_MS, TimeUnit.MILLISECONDS)
        // flush() may perform disk I/O, so keep it off the main thread.
        runCatching { cookies.flush() }

        if (!finished && result.get() == null) {
            throw IOException(failure.get() ?: "WebView no pudo preparar las cookies.")
        }
        return result.get() ?: throw IOException(
            failure.get() ?: "TikTok no proporcionó una cookie ttwid válida."
        )
    }

    private fun extractTtwid(manager: CookieManager): String? {
        for (url in COOKIE_URLS) {
            val header = runCatching { manager.getCookie(url) }.getOrNull()
            TtwidCookieParser.fromCookieHeader(header)?.let { return it }
        }
        return null
    }

    companion object {
        private val ACQUISITION_LOCK = Any()
        private const val HOME_URL = "https://www.tiktok.com/"
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val COOKIE_POLL_INTERVAL_MS = 350L
        private const val OUTER_WAIT_GRACE_MS = 2_000L
        private const val CLEANUP_WAIT_MS = 2_000L
        private val COOKIE_URLS = listOf(
            "https://www.tiktok.com/",
            "https://tiktok.com/",
            "https://m.tiktok.com/"
        )
    }
}
