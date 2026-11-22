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

@Composable
fun RoutesMark(size: Int = 20, color: Color = Tk.Red) = Canvas(Modifier.size(size.dp)) {
    val s = this.size.width / 24f
    val stroke = Stroke(width = 2.4f * s, cap = StrokeCap.Round)
    drawPath(Path().apply { moveTo(7 * s, 21 * s); lineTo(7 * s, 14 * s); cubicTo(7 * s, 10.5f * s, 9.5f * s, 8 * s, 13 * s, 8 * s); lineTo(19 * s, 8 * s) }, color, style = stroke)
    drawPath(Path().apply { moveTo(7 * s, 14 * s); cubicTo(7 * s, 10.5f * s, 5.5f * s, 8 * s, 4 * s, 6 * s) }, color, style = stroke)
    drawCircle(color, 1.7f * s, androidx.compose.ui.geometry.Offset(19 * s, 8 * s))
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
