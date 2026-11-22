package ch.ripple.app

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.ripple.core.Chf
import java.io.File

/** Routes × UBS tokens, as locked in docs/DECISIONS.md (2026-11-22). */
internal object Rt {
    val Ink = Color(0xFF1C1C1C)
    val Ink2 = Color(0xFF5A5D5C)
    val Line = Color(0xFFE0DED6)
    val Chalk = Color(0xFFF9F9F7)
    val Track = Color(0xFFECEAE3)
    val White = Color(0xFFFFFFFF)
    /** Main action and the Routes mark only. */
    val Red = Color(0xFFE60000)
    /** Underlined contextual actions. */
    val LinkRed = Color(0xFFDA0000)
    /** Money below the cushion: text and bar. */
    val Bronze = Color(0xFF86671D)
    val BronzeBar = Color(0xFFAF8626)
    /** The one thing that matters; confirmed facts. */
    val Gold = Color(0xFFF1E2BC)
    val Radius = RoundedCornerShape(3.dp)
    val CardRadius = RoundedCornerShape(4.dp)
}

/**
 * Source Sans 3 is the approved face but is not installed here; Segoe UI (Windows) is the
 * closest available humanist sans with a Light weight. Other systems fall back to SansSerif.
 */
internal val RoutesFont: FontFamily = run {
    val dir = File(System.getenv("WINDIR") ?: "C:/Windows", "Fonts")
    val files = listOf("segoeuil.ttf" to FontWeight.Light, "segoeui.ttf" to FontWeight.Normal,
        "seguisb.ttf" to FontWeight.SemiBold, "segoeuib.ttf" to FontWeight.Bold).map { (n, w) -> File(dir, n) to w }
    if (files.all { it.first.exists() }) FontFamily(files.map { (f, w) -> Font(f, w) }) else FontFamily.SansSerif
}

internal object RtType {
    val Title = TextStyle(fontFamily = RoutesFont, fontWeight = FontWeight.Light, fontSize = 52.sp, lineHeight = 58.sp, letterSpacing = (-0.5).sp, color = Rt.Ink)
    val Finding = TextStyle(fontFamily = RoutesFont, fontWeight = FontWeight.Light, fontSize = 29.sp, lineHeight = 40.sp, color = Rt.Ink)
    val Big = TextStyle(fontFamily = RoutesFont, fontWeight = FontWeight.Light, fontSize = 40.sp, lineHeight = 46.sp, color = Rt.Ink)
    val Lead = TextStyle(fontFamily = RoutesFont, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 26.sp, color = Rt.Ink2)
    val Body = TextStyle(fontFamily = RoutesFont, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp, color = Rt.Ink)
    val Label = TextStyle(fontFamily = RoutesFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 19.sp, color = Rt.Ink2)
    val Small = TextStyle(fontFamily = RoutesFont, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, color = Rt.Ink2)
    val Button = TextStyle(fontFamily = RoutesFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
}

/** Set from the View menu. When true every transition is instant. */
internal val LocalReduceMotion = compositionLocalOf { false }

internal fun <T> motion(reduce: Boolean, ms: Int = 450): AnimationSpec<T> = if (reduce) snap() else tween(ms)

/** A visible keyboard focus ring for custom clickable elements. Place it before clickable(). */
internal fun Modifier.focusRing(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.isFocused }
        .border(2.dp, if (focused) Rt.Ink else Color.Transparent, Rt.Radius)
}

/** Display money for people: `CHF 1'500`, `−CHF 200`; cents only when present. Engine values stay exact. */
internal fun Chf.money(): String {
    val abs = kotlin.math.abs(rappen)
    val francs = (abs / 100).toString().reversed().chunked(3).joinToString("'").reversed()
    val cents = if (abs % 100 == 0L) "" else "." + (abs % 100).toString().padStart(2, '0')
    return (if (rappen < 0) "−" else "") + "CHF " + francs + cents
}

/** The profile has no calendar dates; Routes shows month 1 as January (stated in “Show the math”). */
internal fun monthName(i: Int): String {
    val n = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")[i % 12]
    return if (i < 12) n else "$n, year ${i / 12 + 1}"
}

internal fun monthList(months: List<Int>): String = when (months.size) {
    0 -> ""
    1 -> monthName(months[0])
    else -> months.dropLast(1).joinToString(", ") { monthName(it) } + " and " + monthName(months.last())
}

/** Plain-language names for the fixture's label keys; other keys shown readably; human labels as they are. */
internal fun plain(key: String): String = mapOf(
    "option.move" to "Moving out now",
    "option.stay" to "Staying at home",
    "option.delay_move" to "Moving out two months later",
    "question.deposit" to "The deposit",
)[key] ?: if (Regex("""[a-z_]+(\.[a-z_]+)+""").matches(key)) key.substringAfter('.').replace('_', ' ').replaceFirstChar { it.uppercase() } else key
