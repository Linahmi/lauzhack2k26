package ch.ripple.app.assistant

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun MicrophoneControl(
    listening: Boolean, processing: Boolean, available: Boolean, reduceMotion: Boolean,
    speaking: Boolean = false, audioLevel: Float? = null, actionLabel: String? = null, onClick: () -> Unit,
) {
    val red = MaterialTheme.colorScheme.primary
    val pulse = if ((listening || processing || speaking) && !reduceMotion) listeningPulse() else .35f
    val label = actionLabel ?: when { listening -> "Stop listening"; processing -> "Processing"; !available -> "Microphone unavailable"; else -> "Start microphone" }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.sizeIn(maxWidth = 284.dp, maxHeight = 284.dp).fillMaxWidth().aspectRatio(1f).clip(CircleShape)
            .clickable(enabled = available && !processing, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }) {
            Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
                val center = Offset(size.width / 2, size.height / 2)
                val radius = size.minDimension * .43f
                drawCircle(Brush.radialGradient(0f to Color(0xFFFFFBF8), .4f to Color(0xFFFFF8F4), .82f to Color(0xFFF7D3CD), 1f to Color(0xFFFBEEEA),
                    center = center, radius = radius * 1.17f), radius * 1.16f, center)
                drawCircle(Color(0xFFE8B8B0), radius, center, style = Stroke(1.5.dp.toPx()))
                drawArc(red, -90f, if (listening || processing) 140f + pulse * 180f else 230f,
                    false, Offset(center.x - radius, center.y - radius),
                    androidx.compose.ui.geometry.Size(radius * 2, radius * 2), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                val heights = listOf(18f, 32f, 48f, 34f, 20f)
                heights.forEachIndexed { index, base ->
                    val height = base.dp.toPx() * if (audioLevel != null && (listening || speaking))
                        (.35f + audioLevel.coerceIn(0f, 1f) * 1.5f) else if (listening) (.65f + pulse * .55f) else 1f
                    val x = center.x + (index - 2) * 11.dp.toPx()
                    drawLine(red, Offset(x, center.y - height / 2), Offset(x, center.y + height / 2), 5.dp.toPx(), StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun listeningPulse(): Float {
    val transition = rememberInfiniteTransition(label = "Listening")
    val pulse by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(950), RepeatMode.Reverse), label = "Voice ring")
    return pulse
}
