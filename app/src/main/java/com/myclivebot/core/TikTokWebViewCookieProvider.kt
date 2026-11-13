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
import java.util.concurrent.atomic.AtomicReference

/**
 * Loads TikTok in an unattached, invisible WebView. It never opens an Activity
 * or displays a browser window. It does not log in, bypass challenges, or click
 * consent dialogs; if TikTok requires human interaction this provider fails closed.
 */
class TikTokWebViewCookieProvider(context: Context) {
    private val appContext = context.applicationContext

    fun acquireTtwid(timeoutMs: Long = 25_000L): String {
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        extractTtwid(cookies.getCookie(HOME_URL))?.let { return it }

        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>()
        val failure = AtomicReference<String?>()
        val webViewRef = AtomicReference<WebView?>()
        val main = Handler(Looper.getMainLooper())

        main.post {
            val webView = try {
                WebView(appContext).apply {
                    visibility = View.GONE
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = false
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            val cookie = extractTtwid(cookies.getCookie(HOME_URL))
                            if (cookie != null) result.set(cookie)
                            else failure.set("TikTok cargó HTML en WebView, pero no emitió ttwid.")
                            latch.countDown()
                        }

                        @Suppress("DEPRECATION")
                        override fun onReceivedError(
                            view: WebView,
                            errorCode: Int,
                            description: String,
                            failingUrl: String
                        ) {
                            failure.set("WebView no pudo cargar TikTok: $description")
                            latch.countDown()
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError
                        ) {
                            if (request.isForMainFrame) {
                                failure.set("WebView no pudo cargar TikTok: ${error.description}")
                                latch.countDown()
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                failure.set("No se pudo iniciar WebView: ${error.message}")
                latch.countDown()
                null
            }

            if (webView != null) {
                webViewRef.set(webView)
                webView.loadUrl(HOME_URL)
                main.postDelayed({
                    if (latch.count > 0L) {
                        failure.set("Tiempo agotado al preparar cookies de TikTok en segundo plano.")
                        latch.countDown()
                    }
                }, timeoutMs)
            }
        }

        if (!latch.await(timeoutMs + 2_000L, TimeUnit.MILLISECONDS)) {
            throw IOException("WebView no terminó la preparación de cookies.")
        }
        main.post {
            // CookieManager persists accepted Set-Cookie values independently of this view.
            cookies.flush()
            webViewRef.getAndSet(null)?.apply {
                stopLoading()
                webViewClient = WebViewClient()
                destroy()
            }
        }
        return result.get() ?: throw IOException(
            failure.get() ?: "TikTok no proporcionó ttwid mediante WebView."
        )
    }

    private fun extractTtwid(header: String?): String? {
        val raw = header.orEmpty().split(';')
            .firstOrNull { it.trim().startsWith("ttwid=") }
            ?.trim()?.substringAfter("ttwid=")
            ?.takeIf { it.matches(Regex("[A-Za-z0-9._-]{8,512}")) }
        return raw
    }

    companion object {
        private const val HOME_URL = "https://www.tiktok.com/"
    }
}
