package ch.ripple.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.ripple.core.Chf

/*
 * The Routes canvas: month-end cash of the selected route (hardest tested case), one comparison
 * route, the person's cushion and, after a confirmed change, the route before it. Every point is
 * an engine value; motion only interpolates between two computed routes, never shows new figures.
 */

/** One route to draw: opening cash followed by each month's closing cash. */
internal data class RouteLine(val label: String, val points: List<Chf>)

private const val PAD_L = 74f
private const val PAD_R = 200f
private const val PAD_T = 26f
private const val PAD_B = 34f

private class Geom(val w: Float, val h: Float, val n: Int, val lo: Float, val hi: Float) {
    fun x(i: Int) = PAD_L + (w - PAD_L - PAD_R) * i / (n - 1).coerceAtLeast(1)
    fun y(v: Float) = PAD_T + (h - PAD_T - PAD_B) * (1f - (v - lo) / (hi - lo).coerceAtLeast(1f))
}

private fun Chf.f() = rappen / 100f

/** Animates a route from its previous computed shape to the new one (instant with reduced motion). */
@Composable
private fun morph(target: List<Float>, reduce: Boolean): List<Float> {
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val t = remember { Animatable(1f) }
    LaunchedEffect(target) {
        val now = if (from.size == to.size) from.zip(to) { a, b -> a + (b - a) * t.value } else to
        from = if (now.size == target.size) now else target
        to = target
        if (reduce) t.snapTo(1f) else { t.snapTo(0f); t.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
    }
    return if (from.size == to.size) from.zip(to) { a, b -> a + (b - a) * t.value } else to
}

@Composable
private fun morphValue(target: Float, reduce: Boolean): Float = morph(listOf(target), reduce).first()

/**
 * [onMonth] opens a month (index into closing balances). [marker] carries the shared-element
 * modifier so the key month can grow into its explanation.
 */
@Composable
internal fun RouteChart(
    selected: RouteLine,
    compare: RouteLine?,
    before: RouteLine?,
    cushion: Chf,
    keyMonth: Int,
    belowMonths: List<Int>,
    onMonth: (Int) -> Unit,
    marker: Modifier,
) {
    val reduce = LocalReduceMotion.current
    val measurer = rememberTextMeasurer()
    val all = selected.points + (compare?.points.orEmpty()) + (before?.points.orEmpty()) + cushion + Chf.ZERO
    val loT = all.minOf { it.f() }
    val hiT = all.maxOf { it.f() }
    val span = (hiT - loT).coerceAtLeast(500f)
    val lo = morphValue(loT - span * 0.08f, reduce)
    val hi = morphValue(hiT + span * 0.12f, reduce)
    val sel = morph(selected.points.map { it.f() }, reduce)
    val cmp = compare?.let { morph(it.points.map { p -> p.f() }, reduce) }
    val cush = morphValue(cushion.f(), reduce)
    var hover by remember { mutableStateOf<Int?>(null) }
    val n = selected.points.size
    val density = LocalDensity.current

    BoxWithConstraints(
        Modifier.fillMaxWidth().height(330.dp).semantics {
            contentDescription = "${selected.label}: lowest ${monthName(keyMonth)}, ${selected.points[keyMonth + 1].money()}." +
                (compare?.let { " Compared with ${it.label}." } ?: "")
        },
    ) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { 330.dp.toPx() }
        val g = Geom(wPx, hPx, n, lo, hi)
        Canvas(
            Modifier.fillMaxWidth().height(330.dp)
                .pointerInput(n, wPx) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            val p = e.changes.first().position
                            hover = when (e.type) {
                                PointerEventType.Exit -> null
                                else -> (1 until n).minByOrNull { kotlin.math.abs(g.x(it) - p.x) }?.takeIf { kotlin.math.abs(g.x(it) - p.x) < 40f }
                            }
                            if (e.type == PointerEventType.Release) hover?.let { onMonth(it - 1) }
                        }
                    }
                },
        ) {
            // Zero and cushion references.
            if (lo < 0f) hLine(g, 0f, Rt.Line, null)
            hLine(g, cush, Rt.Ink, PathEffect.dashPathEffect(floatArrayOf(7f, 6f)))
            label(measurer, "your cushion ${cushion.money()}", Offset(g.x(n - 1) + 10f, g.y(cush) - 9f), RtType.Small.copy(color = Rt.Ink, fontWeight = FontWeight.SemiBold))
            if (lo < 0f) label(measurer, "CHF 0", Offset(8f, g.y(0f) - 9f), RtType.Small)

            before?.let { b -> route(g, b.points.map { it.f() }, Rt.Ink2.copy(alpha = 0.55f), 1.6f, PathEffect.dashPathEffect(floatArrayOf(4f, 5f))) }
            cmp?.let { c ->
                route(g, c, Rt.Ink2.copy(alpha = 0.38f), 2f, null)
                label(measurer, compare.label, Offset(g.x(n - 1) + 10f, g.y(c.last()) + 4f), RtType.Small.copy(color = Rt.Ink2))
            }
            route(g, sel, Rt.Ink, 3f, null)
            label(measurer, selected.label, Offset(g.x(n - 1) + 10f, g.y(sel.last()) - 22f), RtType.Small.copy(color = Rt.Ink, fontWeight = FontWeight.SemiBold))

            // Months below the cushion: bronze points AND a ▾ under the month name (not colour alone).
            belowMonths.forEach { m -> drawCircle(Rt.BronzeBar, 5f, Offset(g.x(m + 1), g.y(sel[m + 1]))) }
            for (i in 1 until n) {
                val m = i - 1
                val every = if (n > 14) 2 else 1
                if (m % every == 0 || m == keyMonth) {
                    val below = m in belowMonths
                    label(measurer, monthName(m).take(3) + if (below) " ▾" else "", Offset(g.x(i) - 12f, hPx - PAD_B + 10f),
                        RtType.Small.copy(color = if (below) Rt.Bronze else Rt.Ink2, fontWeight = if (m == keyMonth) FontWeight.SemiBold else FontWeight.Normal))
                }
            }
            label(measurer, "Today", Offset(g.x(0) - 18f, hPx - PAD_B + 10f), RtType.Small)

            // The key month: ringed, labelled with its engine value.
            val kx = g.x(keyMonth + 1); val ky = g.y(sel[keyMonth + 1])
            drawCircle(Rt.White, 9f, Offset(kx, ky)); drawCircle(Rt.Ink, 9f, Offset(kx, ky), style = Stroke(2.5f))
            val tag = "${monthName(keyMonth)}: ${selected.points[keyMonth + 1].money()}"
            val tagStyle = RtType.Small.copy(color = Rt.Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            val tagW = measurer.measure(tag, tagStyle).size.width
            // Near the right edge the route's own label sits there: put the key-month tag on the left.
            val tagX = if (kx + tagW / 2 > g.x(n - 1) - 20f) kx - tagW - 16f else (kx - tagW / 2f).coerceAtLeast(PAD_L)
            val tagY = if (ky + 40f > hPx - PAD_B) ky - 34f else ky + 14f
            label(measurer, tag, Offset(tagX, tagY), tagStyle)

            hover?.let { i ->
                drawLine(Rt.Line, Offset(g.x(i), PAD_T), Offset(g.x(i), hPx - PAD_B), 1.5f)
                drawCircle(Rt.Ink, 4.5f, Offset(g.x(i), g.y(sel[i])))
                val text = "${monthName(i - 1)}: ${selected.points[i].money()}" + (compare?.let { " · ${it.points[i].money()} ${it.label.lowercase()}" } ?: "")
                label(measurer, text, Offset((g.x(i) + 8f).coerceAtMost(wPx - 330f), PAD_T - 22f), RtType.Small.copy(color = Rt.Ink))
            }
        }
        // A real, focusable target on the key month: keyboard users open it with Enter/Space.
        val size = 28.dp
        Box(
            marker.offset(x = with(density) { (g.x(keyMonth + 1)).toDp() } - size / 2, y = with(density) { g.y(sel[keyMonth + 1]).toDp() } - size / 2)
                .size(size).focusRing().clickable(role = Role.Button) { onMonth(keyMonth) }
                .semantics { contentDescription = "Open ${monthName(keyMonth)}" },
        )
    }
}

private fun DrawScope.route(g: Geom, v: List<Float>, color: Color, width: Float, effect: PathEffect?) {
    val p = Path()
    v.forEachIndexed { i, y -> if (i == 0) p.moveTo(g.x(i), g.y(y)) else p.lineTo(g.x(i), g.y(y)) }
    drawPath(p, color, style = Stroke(width, cap = StrokeCap.Round, pathEffect = effect))
}

private fun DrawScope.hLine(g: Geom, v: Float, color: Color, effect: PathEffect?) =
    drawLine(color, Offset(PAD_L, g.y(v)), Offset(g.w - PAD_R, g.y(v)), 1.4f, pathEffect = effect)

private fun DrawScope.label(m: TextMeasurer, text: String, at: Offset, style: TextStyle) =
    drawText(m, text, at, style)

/**
 * A slider whose track shows where the engine says the answer turns: gold where every month
 * keeps the cushion, plain where it doesn't. Values snap to the input's own step.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun BoundarySlider(lo: Long, hi: Long, step: Long, value: Long, turn: Turn?, description: String, onChange: (Long) -> Unit) {
    val keeps: (Long) -> Boolean = { v ->
        when (turn) {
            is Turn.UpTo -> v <= turn.value.rappen / 100
            is Turn.From -> v >= turn.value.rappen / 100
            Turn.AllKeep -> true
            else -> false
        }
    }
    Column {
        Box(Modifier.fillMaxWidth().height(40.dp)) {
            Canvas(Modifier.fillMaxWidth().height(40.dp)) {
                val inset = 10.dp.toPx()
                val y = size.height / 2
                val w = size.width - 2 * inset
                fun x(v: Long) = inset + w * ((v - lo).toFloat() / (hi - lo).coerceAtLeast(1))
                drawLine(Rt.Track, Offset(inset, y), Offset(inset + w, y), 6.dp.toPx())
                val band: Pair<Long, Long>? = when (turn) {
                    is Turn.UpTo -> lo to turn.value.rappen / 100
                    is Turn.From -> turn.value.rappen / 100 to hi
                    Turn.AllKeep -> lo to hi
                    else -> null
                }
                band?.let { (a, b) -> drawLine(Rt.Gold, Offset(x(a), y), Offset(x(b), y), 6.dp.toPx()) }
                if (turn is Turn.UpTo || turn is Turn.From) {
                    val t = if (turn is Turn.UpTo) turn.value else (turn as Turn.From).value
                    drawLine(Rt.Ink, Offset(x(t.rappen / 100), y - 10.dp.toPx()), Offset(x(t.rappen / 100), y + 10.dp.toPx()), 2.dp.toPx())
                }
            }
            Slider(
                value = value.coerceIn(lo, hi).toFloat(),
                onValueChange = { onChange((lo + Math.round((it - lo) / step.toFloat()) * step).coerceIn(lo, hi)) },
                valueRange = lo.toFloat()..hi.toFloat(),
                colors = SliderDefaults.colors(thumbColor = if (turn != null && !keeps(value) && turn !is Turn.NoSingleBoundary) Rt.Bronze else Rt.Ink, activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent),
                track = { st -> SliderDefaults.Track(st, Modifier.height(6.dp), colors = SliderDefaults.colors(activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent), drawStopIndicator = null, thumbTrackGapSize = 0.dp) },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
            )
        }
    }
}

/**
 * The Routes strip: one mark per month on a stem hanging from the person's cushion. A stem's
 * length is the exact distance from the cushion (same scale for every month), so "how far above
 * or below" reads at a glance without axes. Ghost marks show another route or the version before
 * the last change. Every month is a focusable target: click/Enter opens it, again closes it.
 */
@Composable
internal fun RouteStrip(
    points: List<Chf>,
    ghost: List<Chf>?,
    cushion: Chf,
    keyMonth: Int,
    openMonth: Int?,
    below: List<Int>,
    onMonth: (Int) -> Unit,
    onCushion: (() -> Unit)? = null,
) {
    val reduce = LocalReduceMotion.current
    val measurer = rememberTextMeasurer()
    val n = points.size
    val all = points + ghost.orEmpty() + cushion
    val loT = minOf(all.minOf { it.f() }, cushion.f())
    val hiT = all.maxOf { it.f() }
    val span = (hiT - loT).coerceAtLeast(500f)
    val lo = morphValue(loT - span * 0.06f, reduce)
    val hi = morphValue(hiT + span * 0.06f, reduce)
    val v = morph(points.map { it.f() }, reduce)
    val gv = ghost?.takeIf { it.size == n }?.let { morph(it.map { p -> p.f() }, reduce) }
    val c = morphValue(cushion.f(), reduce)
    var hover by remember { mutableStateOf<Int?>(null) }
    val topDp = 30.dp
    val markDp = 150.dp
    val frac = (1f - (c - lo) / (hi - lo).coerceAtLeast(1f)).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(230.dp)) {
        Canvas(Modifier.fillMaxWidth().height(230.dp)) {
            val top = topDp.toPx()
            val markH = markDp.toPx()
            val colW = size.width / n
            fun x(i: Int) = colW * i + colW / 2
            fun y(value: Float) = top + markH * (1f - (value - lo) / (hi - lo).coerceAtLeast(1f))
            openMonth?.let { m -> drawRect(Rt.Chalk, Offset(colW * m, 0f), androidx.compose.ui.geometry.Size(colW, size.height)) }
            if (lo < 0f && c > 0f) drawLine(Rt.Line, Offset(0f, y(0f)), Offset(size.width, y(0f)), 1f)
            drawLine(Rt.Ink, Offset(0f, y(c)), Offset(size.width, y(c)), 1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            // A faint thread keeps the months in time order.
            val thread = Path()
            v.forEachIndexed { i, value -> if (i == 0) thread.moveTo(x(i), y(value)) else thread.lineTo(x(i), y(value)) }
            drawPath(thread, Rt.Line, style = Stroke(1.2f))
            v.forEachIndexed { i, value ->
                val isBelow = i in below
                val col = if (isBelow) Rt.BronzeBar else Rt.Ink
                drawLine(col.copy(alpha = if (isBelow) 0.9f else 0.35f), Offset(x(i), y(c)), Offset(x(i), y(value)), 2.2f, cap = StrokeCap.Round)
                gv?.getOrNull(i)?.let { g -> drawCircle(Rt.Ink2, 5f, Offset(x(i), y(g)), style = Stroke(1.4f)) }
                drawCircle(col, if (i == keyMonth || i == openMonth) 6.5f else 5f, Offset(x(i), y(value)))
                if (i == keyMonth) drawCircle(Rt.Ink, 11f, Offset(x(i), y(value)), style = Stroke(1.8f))
                val label = monthName(i).take(3) + if (isBelow) " ▾" else ""
                val st = RtType.Small.copy(color = if (isBelow) Rt.Bronze else Rt.Ink2, fontWeight = if (i == keyMonth || i == openMonth) FontWeight.SemiBold else FontWeight.Normal)
                val w = measurer.measure(label, st).size.width
                if (n <= 18 || i % 2 == 0 || i == keyMonth || i == openMonth) drawText(measurer, label, Offset(x(i) - w / 2f, top + markH + 18f), st)
                if (i == keyMonth || i == openMonth || i == hover) {
                    val tag = points[i].money()
                    val ts = RtType.Small.copy(color = if (isBelow) Rt.Bronze else Rt.Ink, fontWeight = FontWeight.SemiBold)
                    val tw = measurer.measure(tag, ts).size.width
                    val above = value >= c
                    drawText(measurer, tag, Offset((x(i) - tw / 2f).coerceIn(0f, size.width - tw), if (above) y(value) - 30f else y(value) + 12f), ts)
                }
            }
            openMonth?.let { m -> drawText(measurer, "▲", Offset(x(m) - 5f, size.height - 18f), RtType.Small.copy(color = Rt.Ink)) }
        }
        Row(Modifier.fillMaxWidth().height(230.dp)) {
            points.forEachIndexed { i, p ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().focusRing()
                        .pointerInput(i) {
                            awaitPointerEventScope {
                                while (true) {
                                    val e = awaitPointerEvent()
                                    if (e.type == PointerEventType.Enter || e.type == PointerEventType.Move) hover = i
                                    if (e.type == PointerEventType.Exit && hover == i) hover = null
                                }
                            }
                        }
                        .clickable(role = Role.Tab, onClickLabel = if (openMonth == i) "Close ${monthName(i)}" else "Open ${monthName(i)}") { onMonth(i) }
                        .semantics { contentDescription = "${monthName(i)}: ${p.money()}" + if (i in below) ", below your cushion" else "" },
                )
            }
        }
        Text(
            "your cushion ${cushion.money()}",
            style = RtType.Small.copy(color = Rt.Ink2, textDecoration = if (onCushion != null) androidx.compose.ui.text.style.TextDecoration.Underline else null),
            modifier = Modifier.offset(x = 4.dp, y = topDp + markDp * frac + 4.dp)
                .then(if (onCushion != null) Modifier.focusRing().clickable(role = Role.Button, onClickLabel = "Try another cushion") { onCushion() } else Modifier),
        )
    }
}
