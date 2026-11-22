package com.myclivebot.windows

import org.cef.CefBrowser
import org.cef.CefClient
import org.cef.CefApp
import java.awt.BorderLayout
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/**
 * Dedicated YouTube sign-in window.
 *
 * It uses the application's existing JCEF profile, stored under LOCALAPPDATA,
 * so the official embedded player can use the same browser session. Cookies
 * are never exported to external processes or copied into yt-dlp.
 */
object DesktopYoutubeAccountWindow {
    @Volatile
    private var frame: JFrame? = null

    @Volatile
    private var browser: CefBrowser? = null

    @Volatile
    private var client: CefClient? = null

    fun open() {
        DesktopJcefRuntime.initialize().whenComplete { cefApp: CefApp?, error: Throwable? ->
            if (error != null || cefApp == null) {
                SwingUtilities.invokeLater {
                    javax.swing.JOptionPane.showMessageDialog(
                        null,
                        "No se pudo abrir el inicio de sesión de YouTube: ${error?.cause?.message ?: error?.message ?: "error de Chromium"}",
                        "Tik Tok Reader",
                        javax.swing.JOptionPane.ERROR_MESSAGE
                    )
                }
                return@whenComplete
            }

            SwingUtilities.invokeLater {
                val existing = frame
                if (existing != null && existing.isDisplayable) {
                    existing.isVisible = true
                    existing.toFront()
                    existing.requestFocus()
                    return@invokeLater
                }

                try {
                    val newClient = cefApp.createClient()
                    val newBrowser = newClient.createBrowser("https://www.youtube.com/", false, false)
                    val newFrame = JFrame("Cuenta de YouTube · Tik Tok Reader").apply {
                        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
                        layout = BorderLayout()
                        add(newBrowser.uiComponent, BorderLayout.CENTER)
                        setSize(1100, 760)
                        minimumSize = java.awt.Dimension(800, 600)
                        setLocationRelativeTo(null)
                        addWindowListener(object : WindowAdapter() {
                            override fun windowClosed(event: WindowEvent) {
                                if (frame === this@apply) {
                                    frame = null
                                    browser = null
                                    client = null
                                }
                                runCatching { newBrowser.close(true) }
                                runCatching { newClient.dispose() }
                            }
                        })
                    }

                    client = newClient
                    browser = newBrowser
                    frame = newFrame
                    newFrame.isVisible = true
                } catch (failure: Exception) {
                    runCatching { browser?.close(true) }
                    runCatching { client?.dispose() }
                    browser = null
                    client = null
                    frame = null
                    javax.swing.JOptionPane.showMessageDialog(
                        null,
                        "No se pudo iniciar la ventana de YouTube: ${failure.message ?: "error desconocido"}",
                        "Tik Tok Reader",
                        javax.swing.JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }
    }
}
