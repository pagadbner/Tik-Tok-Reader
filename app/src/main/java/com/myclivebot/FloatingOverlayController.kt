package com.myclivebot

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.provider.Settings
import android.os.Build

/**
 * Small, service-owned overlay. It deliberately has no dependency on the TikTok
 * connector, local bridge, or speech pipeline; actions are delegated to the service.
 */
internal class FloatingOverlayController(
    private val context: Context,
    private val onMuteToggle: () -> Unit,
    private val onSkipNext: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private var statusLabel: TextView? = null
    private var muteButton: TextView? = null
    private var actionsRow: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var muted = false
    private var expanded = true

    fun show(statusText: String, isMuted: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) return
        muted = isMuted
        if (root != null) {
            update(statusText, isMuted)
            return
        }

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(0xF21A2034.toInt(), 0xFF7166A8.toInt(), 18)
            elevation = dp(12).toFloat()
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dragHandle = TextView(context).apply {
            text = "TIKLBOT  ·  LIVE"
            setTextColor(0xFFB9B0FF.toInt())
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .06f
            setPadding(dp(2), dp(5), dp(8), dp(5))
        }
        val status = TextView(context).apply {
            text = statusText.take(34)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val collapse = actionText("−") {
            expanded = !expanded
            actionsRow?.visibility = if (expanded) View.VISIBLE else View.GONE
            it.text = if (expanded) "−" else "+"
        }
        collapse.contentDescription = "Contraer o expandir controles flotantes"
        header.addView(dragHandle)
        header.addView(status)
        header.addView(collapse)
        panel.addView(header)

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val mute = actionText(muteLabel()) { onMuteToggle() }
        mute.layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) }
        val skip = actionText("OMITIR") { onSkipNext() }
        skip.layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(6) }
        actions.addView(mute)
        actions.addView(skip)
        panel.addView(actions)

        val params = WindowManager.LayoutParams(
            dp(270),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(120)
        }

        dragHandle.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = (startX - (event.rawX - touchX).toInt()).coerceAtLeast(0)
                        params.y = (startY + (event.rawY - touchY).toInt()).coerceAtLeast(0)
                        runCatching { windowManager.updateViewLayout(panel, params) }
                        return true
                    }
                    MotionEvent.ACTION_UP -> return true
                }
                return false
            }
        })

        try {
            windowManager.addView(panel, params)
            root = panel
            statusLabel = status
            muteButton = mute
            actionsRow = actions
            layoutParams = params
            update(status, isMuted)
        } catch (_: WindowManager.BadTokenException) {
            root = null
            statusLabel = null
            muteButton = null
            actionsRow = null
            layoutParams = null
        } catch (_: SecurityException) {
            root = null
            statusLabel = null
            muteButton = null
            actionsRow = null
            layoutParams = null
        }
    }

    fun update(status: String, isMuted: Boolean) {
        muted = isMuted
        statusLabel?.text = status.take(60)
        muteButton?.text = muteLabel()
    }

    fun hide() {
        val view = root ?: return
        runCatching { windowManager.removeView(view) }
        root = null
        statusLabel = null
        muteButton = null
        actionsRow = null
        layoutParams = null
    }

    private fun muteLabel() = if (muted) "ACTIVAR VOZ" else "SILENCIAR"

    private fun actionText(label: String, onClick: (TextView) -> Unit): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            background = roundedBackground(0xFF34304F.toInt(), 0x667F75B8, 12)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick(this) }
        }

    private fun roundedBackground(fill: Int, stroke: Int, radiusDp: Int) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), stroke)
        }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
