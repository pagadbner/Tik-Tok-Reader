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
 * Acquires TikTok's anonymous device cookie in an invisible, unattached WebView.
 * Page completion is not treated as proof that asynchronous cookie storage is complete.
 */
class TikTokWebViewCookieProvider(context: Context) {
    private val appContext = context.applicationContext

    fun acquireTtwid(timeoutMs: Long = DEFAULT_TIMEOUT_MS): String {
        require(timeoutMs in 5_000L..60_000L) { "Cookie timeout must be between 5 and 60 seconds." }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        extractTtwid(cookieManager, COOKIE_URLS)?.let { return it }

        val latch = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val result = AtomicReference<String?>()
        val failure = AtomicReference<String?>()
        val webViewRef = AtomicReference<WebView?>()
        val main = Handler(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + timeoutMs

        lateinit var poll: Runnable
        fun finish(value: String? = null, error: String? = null) {
            if (!completed.compareAndSet(false, true)) return
            if (value != null) result.set(value)
            if (error != null) failure.set(error)
            latch.countDown()
        }

        main.post {
            val webView = try {
                WebView(appContext).apply {
                    visibility = View.GONE
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = false
                    settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            // HTML loaded: continue polling for asynchronous Set-Cookie processing.
                            main.removeCallbacks(poll)
                            main.post(poll)
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError
                        ) {
                            if (request.isForMainFrame) {
                                failure.set("TikTok no pudo cargar en WebView (error " + error.errorCode + ").")
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
                finish(error = "No se pudo iniciar WebView: " + creationError.message)
                null
            }

            if (webView != null) {
                webViewRef.set(webView)
                poll = object : Runnable {
                    override fun run() {
                        if (completed.get()) return
                        val cookie = extractTtwid(cookieManager, COOKIE_URLS)
                        if (cookie != null) {
                            finish(value = cookie)
                            return
                        }
                        if (System.currentTimeMillis() >= deadline) {
                            finish(error = failure.get()
                                ?: "TikTok cargó, pero no entregó ttwid en el tiempo de espera. Puede requerir consentimiento o verificación.")
                            return
                        }
                        main.postDelayed(this, COOKIE_POLL_INTERVAL_MS)
                    }
                }
                try {
                    webView.loadUrl(HOME_URL)
                    main.post(poll)
                    main.postDelayed({
                        if (!completed.get()) {
                            finish(error = failure.get()
                                ?: "TikTok no completó la preparación de cookies en segundo plano.")
                        }
                    }, timeoutMs)
                } catch (loadError: Exception) {
                    finish(error = "No se pudo cargar TikTok en WebView: " + loadError.message)
                }
            }
        }

        val finished = latch.await(timeoutMs + 2_000L, TimeUnit.MILLISECONDS)
        if (!finished) {
            completed.set(true)
            failure.set("Se agotó el tiempo de espera de WebView al preparar cookies.")
        }

        // Cleanup always runs on Android's UI thread.
        main.post {
            main.removeCallbacksAndMessages(null)
            cookieManager.flush()
            webViewRef.getAndSet(null)?.apply {
                stopLoading()
                webViewClient = WebViewClient()
                destroy()
            }
        }

        if (!finished) throw IOException(failure.get())
        return result.get() ?: throw IOException(
            failure.get() ?: "TikTok no proporcionó una cookie ttwid válida."
        )
    }

    private fun extractTtwid(manager: CookieManager, urls: List<String>): String? {
        for (url in urls) {
            val header = runCatching { manager.getCookie(url) }.getOrNull().orEmpty()
            val raw = header.split(';')
                .asSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("ttwid=") }
                ?.substringAfter("ttwid=")
                ?.takeIf { it.matches(Regex("[A-Za-z0-9._-]{8,512}")) }
            if (raw != null) return raw
        }
        return null
    }

    companion object {
        private const val HOME_URL = "https://www.tiktok.com/"
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val COOKIE_POLL_INTERVAL_MS = 350L
        private val COOKIE_URLS = listOf(
            "https://www.tiktok.com/",
            "https://tiktok.com/",
            "https://m.tiktok.com/"
        )
    }
}
