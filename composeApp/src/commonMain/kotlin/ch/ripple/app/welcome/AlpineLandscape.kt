package ch.ripple.app.welcome

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.clearAndSetSemantics

/** Original vector artwork: layered Alpine ridges, a chalet and a gently moving flag. */
@Composable
fun AlpineLandscape(modifier: Modifier, reduceMotion: Boolean) {
    val flutter = if (reduceMotion) 0f else flagFlutter()
    Canvas(modifier.clearAndSetSemantics { }) {
        scale(size.width / 400f, size.height / 260f, pivot = Offset.Zero) {
            fun shape(color: Color, block: Path.() -> Unit) = drawPath(Path().apply(block), color)
            shape(Color(0xFFEEDBD6)) {
                moveTo(0f, 128f); lineTo(48f, 75f); quadraticTo(59f, 62f, 68f, 71f)
                lineTo(106f, 100f); lineTo(133f, 123f); lineTo(181f, 92f)
                quadraticTo(191f, 84f, 201f, 93f); lineTo(258f, 131f)
                lineTo(319f, 86f); lineTo(365f, 119f); lineTo(400f, 101f)
                lineTo(400f, 260f); lineTo(0f, 260f); close()
            }
            shape(Color(0xFFF5E5E0)) {
                moveTo(0f, 158f); cubicTo(80f, 128f, 104f, 155f, 148f, 126f)
                cubicTo(207f, 159f, 238f, 115f, 287f, 139f)
                lineTo(348f, 113f); lineTo(400f, 144f); lineTo(400f, 260f); lineTo(0f, 260f); close()
            }
            shape(Color(0xFFDFADA6)) {
                moveTo(0f, 181f); cubicTo(66f, 170f, 68f, 143f, 118f, 159f)
                cubicTo(182f, 179f, 221f, 142f, 266f, 164f)
                cubicTo(317f, 181f, 341f, 147f, 400f, 154f)
                lineTo(400f, 260f); lineTo(0f, 260f); close()
            }
            shape(Color(0xFFBA5550)) {
                moveTo(0f, 174f); cubicTo(83f, 158f, 121f, 217f, 196f, 190f)
                cubicTo(267f, 166f, 321f, 173f, 400f, 131f)
                lineTo(400f, 260f); lineTo(0f, 260f); close()
            }
            // A tiny chalet, grounded in the hillside rather than an app illustration card.
            drawRect(Color(0xFFAB6449), Offset(254f, 154f), Size(65f, 42f))
            drawRect(Color(0xFF874330), Offset(296f, 154f), Size(23f, 42f))
            shape(Color(0xFF732F29)) {
                moveTo(244f, 157f); lineTo(268f, 137f); lineTo(305f, 137f)
                lineTo(328f, 157f); close()
            }
            drawLine(Color(0xFFD69A7A), Offset(254f, 169f), Offset(318f, 169f), 1f)
            drawRect(Color(0xFF663C31), Offset(281f, 174f), Size(9f, 22f))
            listOf(262f, 302f).forEach { x ->
                drawRect(Color(0xFFEBD0A1), Offset(x, 164f), Size(6f, 8f))
                drawLine(Color(0xFF663C31), Offset(x + 3f, 164f), Offset(x + 3f, 172f), 1f)
            }
            drawLine(Color(0xFF6C4C42), Offset(344f, 79f), Offset(344f, 185f), 1.6f)
            shape(Color(0xFFA32930)) {
                moveTo(345f, 80f); cubicTo(362f, 74f + flutter, 370f, 91f - flutter, 387f, 85f)
                lineTo(387f, 113f); cubicTo(370f, 119f - flutter, 362f, 103f + flutter, 345f, 109f); close()
            }
            drawLine(Color.White, Offset(365f, 88f + flutter / 3), Offset(365f, 106f + flutter / 3), 5f)
            drawLine(Color.White, Offset(356f, 97f + flutter / 3), Offset(374f, 97f + flutter / 3), 5f)
            shape(Color(0xFF8A2027)) {
                moveTo(0f, 218f); cubicTo(85f, 196f, 115f, 219f, 179f, 209f)
                cubicTo(249f, 199f, 308f, 173f, 400f, 164f)
                lineTo(400f, 260f); lineTo(0f, 260f); close()
            }
            shape(Color(0xFF691D25)) {
                moveTo(0f, 254f); cubicTo(138f, 233f, 247f, 215f, 400f, 228f)
                lineTo(400f, 260f); lineTo(0f, 260f); close()
            }
            drawPath(Path().apply {
                moveTo(15f, 251f); cubicTo(95f, 228f, 140f, 237f, 187f, 213f)
            }, Color(0xFFCC8980), style = androidx.compose.ui.graphics.drawscope.Stroke(1.2f, cap = StrokeCap.Round))
        }
    }
}

@Composable
fun RippleCrest(modifier: Modifier) {
    Canvas(modifier.clearAndSetSemantics { }) {
        scale(size.width / 60f, size.height / 30f, pivot = Offset.Zero) {
            drawPath(Path().apply {
                moveTo(4f, 21f); cubicTo(13f, 5f, 15f, 6f, 23f, 14f)
                cubicTo(31f, 22f, 33f, 2f, 42f, 5f); cubicTo(48f, 6f, 51f, 16f, 56f, 22f)
            }, Color(0xFF9B242C), style = androidx.compose.ui.graphics.drawscope.Stroke(10f, cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun flagFlutter(): Float {
    val transition = rememberInfiniteTransition(label = "Alpine breeze")
    val value by transition.animateFloat(-2f, 2f, infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "Flag")
    return value
}
