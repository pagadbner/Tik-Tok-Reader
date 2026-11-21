package com.myclivebot.windows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun TikLBotLogo(size: Dp = 42.dp) {
    Canvas(Modifier.size(size)) {
        val scale = this.size.width / 512f
        drawRoundRect(
            brush = Brush.linearGradient(listOf(Color(0xFF17132D), Color(0xFF080B18))),
            size = this.size,
            cornerRadius = CornerRadius(this.size.width * 0.23f)
        )
        drawCircle(Color(0x448B5CF6), radius = 154f * scale, center = Offset(this.size.width / 2, this.size.height / 2))
        drawRoundRect(
            brush = Brush.linearGradient(listOf(Color(0xFFA855F7), Color(0xFF7C3AED), Color(0xFF22D3EE))),
            topLeft = Offset(112f * scale, 97f * scale),
            size = Size(288f * scale, 288f * scale),
            cornerRadius = CornerRadius(118f * scale)
        )
        drawRoundRect(
            color = Color(0xFF15142A).copy(alpha = .72f),
            topLeft = Offset(166f * scale, 171f * scale),
            size = Size(180f * scale, 138f * scale),
            cornerRadius = CornerRadius(68f * scale)
        )
        listOf(12f to 246f, 64f to 220f, 108f to 198f, 52f to 226f, 24f to 240f).forEach { (height, y) ->
            val x = when (listOf(12f,64f,108f,52f,24f).indexOf(height)) { 0 -> 202f; 1 -> 232f; 2 -> 262f; 3 -> 292f; else -> 322f }
            drawLine(
                brush = Brush.linearGradient(listOf(Color.White, Color(0xFFBFF7FF))),
                start = Offset(x * scale, y * scale),
                end = Offset(x * scale, (y + height) * scale),
                strokeWidth = 17f * scale,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }
        drawCircle(Color(0xFF22D3EE), 17f * scale, Offset(374f * scale, 126f * scale))
        drawCircle(Color(0xFFC084FC), 9f * scale, Offset(136f * scale, 366f * scale))
    }
}
