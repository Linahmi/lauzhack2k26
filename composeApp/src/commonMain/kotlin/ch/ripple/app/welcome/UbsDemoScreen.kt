package ch.ripple.app.welcome

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.ripple.app.components.PrimaryButton

@Composable
fun UbsDemoScreen(modifier: Modifier = Modifier, onBack: () -> Unit, onContinue: () -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val minimumHeight = maxHeight
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState())
            .heightIn(min = minimumHeight).padding(horizontal = 28.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(48.dp)) {
                    TextButton(onBack, Modifier.align(Alignment.CenterStart).size(48.dp)) {
                        Text("‹", fontSize = 30.sp, modifier = Modifier.semantics { contentDescription = "Back" })
                    }
                }
                Spacer(Modifier.height(28.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    BankKeyMark(Modifier.size(48.dp))
                    Text("UBS", fontFamily = FontFamily.Serif, color = MaterialTheme.colorScheme.primary,
                        fontSize = 64.sp, lineHeight = 72.sp)
                }
                Spacer(Modifier.height(34.dp))
                Text("Connect to UBS", Modifier.semantics { heading() }, fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold, fontSize = 33.sp, lineHeight = 42.sp)
                Spacer(Modifier.height(32.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Benefit(0, "Securely link your account")
                    Benefit(1, "View your transactions and spending")
                    Benefit(2, "Get personalized guidance")
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Spacer(Modifier.height(32.dp))
                PrimaryButton("Connect", onContinue, Modifier.fillMaxWidth())
                Text("Demo prototype · no real UBS connection", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
@Composable
private fun Benefit(kind: Int, title: String) {
    val ink = MaterialTheme.colorScheme.onSurface
    Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFFFFFCF9),
        border = BorderStroke(1.dp, Color(0xFFECE3DD))) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(30.dp).clearAndSetSemantics { }) {
            val stroke = Stroke(1.7.dp.toPx())
            val w = size.width
            val h = size.height
            when (kind) {
                0 -> {
                    drawRoundRect(ink, Offset(w * .17f, h * .4f), Size(w * .66f, h * .5f), CornerRadius(3.dp.toPx()), style = stroke)
                    drawArc(ink, 180f, 180f, false, Offset(w * .32f, h * .08f), Size(w * .36f, h * .6f), style = stroke)
                }
                1 -> {
                    drawRoundRect(ink, Offset(w * .17f, h * .1f), Size(w * .66f, h * .8f), CornerRadius(3.dp.toPx()), style = stroke)
                    listOf(.31f, .5f, .69f).forEachIndexed { index, x ->
                        drawLine(ink, Offset(w * x, h * .73f), Offset(w * x, h * (.55f - index * .12f)), stroke.width)
                    }
                }
                else -> drawPath(Path().apply {
                    moveTo(w * .12f, h * .8f); lineTo(w * .45f, h * .5f); lineTo(w * .85f, h * .18f)
                    moveTo(w * .45f, h * .5f); lineTo(w * .85f, h * .72f)
                    moveTo(w * .6f, h * .18f); lineTo(w * .85f, h * .18f); lineTo(w * .85f, h * .43f)
                }, ink, style = stroke)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)

        }
    }
    }
}

/** An original key motif for the demo, not the official UBS trademark artwork. */
@Composable
private fun BankKeyMark(modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.clearAndSetSemantics { }) {
        val c = center
        val stroke = Stroke(1.5.dp.toPx())
        listOf(0f, 120f, 240f).forEach { angle ->
            rotate(angle) {
                drawLine(ink, c, Offset(c.x, size.height * .18f), stroke.width)
                drawCircle(ink, size.width * .12f, Offset(c.x, size.height * .16f), style = stroke)
                drawLine(ink, Offset(c.x, size.height * .3f), Offset(c.x + size.width * .13f, size.height * .3f), stroke.width)
                drawLine(ink, Offset(c.x, size.height * .4f), Offset(c.x + size.width * .1f, size.height * .4f), stroke.width)
            }
        }
        drawCircle(ink, size.width * .1f, c, style = stroke)
    }
}
