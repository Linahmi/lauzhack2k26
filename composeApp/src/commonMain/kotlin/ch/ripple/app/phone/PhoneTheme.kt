package ch.ripple.app.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Routes × UBS tokens, the same values as the desktop's Rt (docs/DECISIONS.md, 2026-11-22).
 * shortcut: copied because the desktop's object is jvm-only; merge into one commonMain object
 * when the desktop owner moves theirs.
 */
object Tk {
    val Ink = Color(0xFF1C1C1C)
    val Ink2 = Color(0xFF5A5D5C)
    val Line = Color(0xFFE0DED6)
    val Chalk = Color(0xFFF9F9F7)
    val Track = Color(0xFFECEAE3)
    val White = Color(0xFFFFFFFF)
    /** Main action and the Routes mark only. */
    val Red = Color(0xFFE60000)
    val LinkRed = Color(0xFFDA0000)
    /** Below the cushion, and what-ifs. */
    val Bronze = Color(0xFF86671D)
    val BronzeBar = Color(0xFFAF8626)
    /** The one finding; confirmed facts. */
    val Gold = Color(0xFFF1E2BC)
    val R = RoundedCornerShape(3.dp)
    val Card = RoundedCornerShape(4.dp)
}

/** shortcut: system sans (Roboto on Android) until Source Sans 3 is bundled as a resource. */
private val F = FontFamily.SansSerif

object Ty {
    val Title = TextStyle(fontFamily = F, fontWeight = FontWeight.Light, fontSize = 28.sp, lineHeight = 34.sp, color = Tk.Ink)
    val Finding = TextStyle(fontFamily = F, fontWeight = FontWeight.Light, fontSize = 22.sp, lineHeight = 30.sp, color = Tk.Ink)
    val Big = TextStyle(fontFamily = F, fontWeight = FontWeight.Light, fontSize = 34.sp, lineHeight = 40.sp, color = Tk.Ink)
    val Body = TextStyle(fontFamily = F, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp, color = Tk.Ink)
    val Strong = Body.copy(fontWeight = FontWeight.SemiBold)
    val Label = TextStyle(fontFamily = F, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 19.sp, color = Tk.Ink2)
    val Small = TextStyle(fontFamily = F, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, color = Tk.Ink2)
    val Button = TextStyle(fontFamily = F, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
}

/** Light grey of the routes not taken in the Routes logo. */
val RouteGrey = Color(0xFFBDBBB0)

/**
 * The Routes logo mark: a starting point, three possible routes, the chosen one in red with a
 * check. Drawn on a 46×40 grid.
 */
@Composable
fun RoutesMark(height: Int = 30, chosen: Color = Tk.Red, other: Color = RouteGrey) =
    Canvas(Modifier.size(width = (height * 46f / 40f).dp, height = height.dp)) {
        val u = size.height / 40f
        fun o(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x * u, y * u)
        val line = Stroke(width = 3.6f * u, cap = StrokeCap.Round)
        fun branch(endY: Float) = Path().apply { moveTo(4 * u, 20 * u); cubicTo(18 * u, 20 * u, 20 * u, endY * u, 36 * u, endY * u) }
        // Routes not taken: a line to a hollow end.
        drawPath(branch(34f), other, style = line)
        drawLine(other, o(4f, 20f), o(36f, 20f), 3.6f * u, StrokeCap.Round)
        listOf(20f, 34f).forEach { y ->
            drawCircle(Color.White, 3.2f * u, o(40.5f, y))
            drawCircle(other, 3.2f * u, o(40.5f, y), style = Stroke(2.4f * u))
        }
        // The chosen route, with its check.
        drawPath(branch(6f), chosen, style = line)
        drawCircle(chosen, 4.6f * u, o(40.5f, 6f))
        drawPath(Path().apply { moveTo(38.4f * u, 6.3f * u); lineTo(40f * u, 7.9f * u); lineTo(42.8f * u, 4.6f * u) }, Color.White,
            style = Stroke(1.6f * u, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawCircle(Tk.Ink, 3.6f * u, o(4f, 20f))
    }

/** "Routes" with the red s, as in the logo lockup. */
@Composable
fun RoutesLockup(height: Int = 30) = androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
    RoutesMark(height)
    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
    Text(androidx.compose.ui.text.buildAnnotatedString {
        append("Route")
        pushStyle(androidx.compose.ui.text.SpanStyle(color = Tk.Red)); append("s"); pop()
    }, style = Ty.Strong.copy(fontSize = (height * 0.85f).sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp))
}

@Composable
fun Primary(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    Box(modifier.heightIn(min = 48.dp).background(if (enabled) Tk.Red else Tk.Track, Tk.R)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center) { Text(text, style = Ty.Button, color = if (enabled) Color.White else Tk.Ink2) }

@Composable
fun Secondary(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
    Box(modifier.heightIn(min = 48.dp).border(1.dp, Tk.Ink, Tk.R).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center) { Text(text, style = Ty.Button, color = Tk.Ink) }

@Composable
fun LinkText(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
    Text(text, style = Ty.Label.copy(color = Tk.LinkRed, textDecoration = TextDecoration.Underline),
        modifier = modifier.clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp))

/** Where a value comes from. Gold = confirmed by the person; outlined = anything else. */
enum class Tag(val label: String) {
    CONFIRMED("Confirmed by you"), ESTIMATE("Your estimate"), RANGE("Your range"), EXAMPLE("Example data"),
    UNKNOWN("Not known"), WHATIF("What-if, not saved"), ASSUMED("Assumed by Routes"), COMPUTED("Computed"), EXTERNAL("External source"),
}

@Composable
fun TagChip(tag: Tag) {
    val confirmed = tag == Tag.CONFIRMED
    val whatIf = tag == Tag.WHATIF
    Text(tag.label, style = Ty.Small.copy(fontWeight = FontWeight.SemiBold, color = if (whatIf) Tk.Bronze else if (confirmed) Tk.Ink else Tk.Ink2),
        modifier = Modifier.background(if (confirmed) Tk.Gold else Tk.White, Tk.R)
            .then(if (confirmed) Modifier else Modifier.border(1.dp, if (whatIf) Tk.BronzeBar else Tk.Line, Tk.R))
            .padding(horizontal = 6.dp, vertical = 1.dp))
}

@Composable
fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Tk.Line))

@Composable
fun Notice(text: String, action: String? = null, onAction: () -> Unit = {}) =
    Column(Modifier.fillMaxWidth().border(1.dp, Tk.BronzeBar, Tk.Card).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text, style = Ty.Body.copy(fontSize = 14.sp))
        action?.let { LinkText(it, onClick = onAction) }
    }

@Composable
fun Legal() = BasicText("Independent prototype · not made or endorsed by UBS", style = Ty.Small.copy(fontSize = 11.sp))
