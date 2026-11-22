package com.myclivebot.windows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Dibujo Compose fiel al vector ic_launcher_foreground.xml de Android. */
@Composable
fun TikTokReaderLogo(size: Dp = 42.dp) {
    Canvas(Modifier.size(size)) {
        val scale = this.size.width / 108f
        fun p(value: Float) = value * scale
        val bubble = Path().apply {
            moveTo(p(18f), p(39f))
            cubicTo(p(18f), p(25f), p(29f), p(14f), p(43f), p(14f))
            lineTo(p(65f), p(14f))
            cubicTo(p(79f), p(14f), p(90f), p(25f), p(90f), p(39f))
            lineTo(p(90f), p(55f))
            cubicTo(p(90f), p(69f), p(79f), p(80f), p(65f), p(80f))
            lineTo(p(48f), p(80f))
            lineTo(p(27f), p(94f))
            lineTo(p(31f), p(76f))
            cubicTo(p(23f), p(71f), p(18f), p(63f), p(18f), p(54f))
            close()
        }
        drawRect(Color(0xFF0B0B18))
        drawPath(bubble, Color(0xFF8B5CF6))
        drawRoundRect(
            color = Color(0xFF17132D),
            topLeft = Offset(p(30f), p(28f)),
            size = Size(p(48f), p(39f)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(p(14f))
        )
        drawRect(Color.White, Offset(p(39f), p(45f)), Size(p(4f), p(8f)))
        drawRect(Color.White, Offset(p(48f), p(37f)), Size(p(4f), p(24f)))
        drawRect(Color.White, Offset(p(57f), p(32f)), Size(p(4f), p(34f)))
        drawRect(Color.White, Offset(p(66f), p(41f)), Size(p(4f), p(16f)))
        drawCircle(Color(0xFF22D3EE), p(5f), Offset(p(88f), p(17f)))
    }
}
