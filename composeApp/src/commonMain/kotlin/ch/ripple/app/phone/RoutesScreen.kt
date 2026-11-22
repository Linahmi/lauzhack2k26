package ch.ripple.app.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import ch.ripple.app.KeyMoment
import ch.ripple.app.causes
import ch.ripple.app.keyMoment
import ch.ripple.app.preview
import ch.ripple.core.*

private enum class Focus { ASSUMPTIONS, COMPARE, VERSIONS, MONTH, MORE }

@Composable
fun RoutesScreen(session: RoutesSession, nav: PhoneNav, voice: VoiceState, platform: PhonePlatform) {
    val open = nav.openDecision?.let { id -> session.decisions.firstOrNull { it.id == id } }
    platform.backHandler(open != null) { nav.openDecision = null }
    if (open == null) DecisionList(session, nav) else DecisionView(session, open, nav, voice, platform)
}

@Composable
private fun DecisionList(session: RoutesSession, nav: PhoneNav) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("My Routes", style = Ty.Title)
        if (session.decisions.isEmpty()) {
            Text("Decisions you explore in a conversation appear here, ready to experiment with.", style = Ty.Body.copy(color = Tk.Ink2))
            Secondary("Go to Explore") { nav.space = Space.EXPLORE }
        }
        session.decisions.asReversed().forEach { d ->
            val k = remember(d) { keyMoment(d.graph.briefing()) }
            Column(
                Modifier.fillMaxWidth().border(1.dp, Tk.Line, Tk.Card).clickable(role = Role.Button) { session.open(d.id); nav.openDecision = d.id }.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d.title, style = Ty.Strong, modifier = Modifier.weight(1f))
                    if (d.graph.active.hypothetical) TagChip(Tag.WHATIF)
                }
                Text("“${d.spec.intention}”", style = Ty.Small)
                Text(findingText(d, k), style = Ty.Body)
                Text("${d.graph.nodes.size} versions · ${d.spec.personName}${if (d.spec.fictional) " (fictional)" else ""}", style = Ty.Small)
            }
        }
    }
}

@Composable
private fun DecisionView(session: RoutesSession, d: RoutesDecision, nav: PhoneNav, voice: VoiceState, platform: PhonePlatform) {
    val b = remember(d) { d.graph.briefing() }
    val k = remember(b) { keyMoment(b) }
    val node = d.graph.active
    var focus by remember(d.id) { mutableStateOf<Focus?>(null) }
    var month by remember(d.id) { mutableStateOf(k.month) }
    var showMonths by remember(d.id) { mutableStateOf(false) }
    platform.backHandler(focus != null) { focus = null }
    val lastTurn = session.turns.lastOrNull()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinkText("‹ My Routes") { nav.openDecision = null }
                    Spacer(Modifier.weight(1f))
                    LinkText("More") { focus = Focus.MORE }
                }
                Text(d.title, style = Ty.Title)
                StateBanner(session, d, node)
                Text(findingText(d, k), style = Ty.Finding,
                    modifier = Modifier.background(if (node.hypothetical) Tk.White else Tk.Gold, Tk.R).padding(8.dp))
                session.pending?.takeIf { it.decisionId == d.id }?.let { PendingCard(it, session) }
                // Contextual ways in; the first view stays one finding.
                Action("See the months", if (showMonths) "hide" else "${monthName(k.month)} is the lowest") { showMonths = !showMonths }
                if (showMonths) MonthChart(k, month) { month = it; focus = Focus.MONTH }
                Action("Change an assumption", "${d.graph.active.session.inputs.size} inputs, with where they come from") { focus = Focus.ASSUMPTIONS }
                Action("Compare routes", "${node.session.options.size + 1} routes, worked out now") { focus = Focus.COMPARE }
                Action("Versions", "${d.graph.nodes.size} kept · v${node.revision} shown") { focus = Focus.VERSIONS }
                Action("Why this result?", "the math of ${monthName(k.month)}") { month = k.month; focus = Focus.MONTH }
                if (lastTurn is Turn.Finding && lastTurn.decisionId == d.id) Text(lastTurn.text, style = Ty.Small)
                else if (lastTurn is Turn.Routes && session.activeDecisionId == d.id) Text(lastTurn.text, style = Ty.Small)
            }
            Composer(voice, "Ask about ${d.title.lowercase()}") { text, via -> session.open(d.id); session.handle(text, via) }
        }
        when (focus) {
            Focus.ASSUMPTIONS -> Sheet("Assumptions", { focus = null }) { Assumptions(session, d) { focus = null } }
            Focus.COMPARE -> Sheet("Compare routes", { focus = null }) { Compare(session, d, b) { focus = null } }
            Focus.VERSIONS -> Sheet("Versions", { focus = null }) { Versions(session, d, platform) { focus = null } }
            Focus.MONTH -> Sheet(monthName(month), { focus = null }) { MonthMath(d, b, k, month) }
            Focus.MORE -> Sheet(d.title, { focus = null }) { More(session, d, nav) }
            null -> Unit
        }
    }
}

@Composable
private fun StateBanner(session: RoutesSession, d: RoutesDecision, node: DecisionNode) {
    if (node.hypothetical) {
        Column(Modifier.fillMaxWidth().border(1.5.dp, Tk.BronzeBar, Tk.Card).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("What-if · not your confirmed situation", style = Ty.Strong.copy(color = Tk.Bronze))
            Text(d.graph.path(node.id).dropWhile { !it.hypothetical }.joinToString(" → ") { versionLabel(d, it) }, style = Ty.Small)
            LinkText("Back to your confirmed situation") { session.select(d.id, d.confirmedHead.id) }
        }
    } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TagChip(Tag.CONFIRMED)
        Text("your situation as you confirmed it", style = Ty.Small)
    }
}

@Composable
private fun Action(title: String, detail: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)) {
        Divider()
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = Ty.Strong)
                Text(detail, style = Ty.Small)
            }
            Text("›", style = Ty.Title.copy(color = Tk.Ink2))
        }
    }
}

/** Month-end cash in the hardest tested case; tap a month to open its math. */
@Composable
fun MonthChart(k: KeyMoment, selected: Int, onPick: (Int) -> Unit) {
    val values = k.months.map { it.closingCash.rappen.toFloat() }
    val top = (values + k.cushion.rappen.toFloat() + 1f).max()
    val bottom = minOf(0f, values.min())
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = "Month-end money, hardest tested case. Lowest: ${monthName(k.month)}, ${k.moneyLeft.money()}." }
            .pointerInput(values.size) { detectTapGestures { p -> onPick((p.x / (size.width / values.size)).toInt().coerceIn(0, values.size - 1)) } }) {
            val w = size.width / values.size
            fun y(v: Float) = size.height * (top - v) / (top - bottom)
            values.forEachIndexed { i, v ->
                val below = v < k.cushion.rappen
                val y0 = y(0f); val y1 = y(v)
                drawRect(if (below) Tk.BronzeBar else Tk.Ink, Offset(i * w + w * 0.18f, minOf(y0, y1)), Size(w * 0.64f, kotlin.math.abs(y1 - y0).coerceAtLeast(2f)))
                if (i == selected) drawRect(Tk.Ink, Offset(i * w + w * 0.1f, 0f), Size(w * 0.8f, size.height), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
            }
            if (k.cushion.rappen > 0) drawLine(Tk.Ink2, Offset(0f, y(k.cushion.rappen.toFloat())), Offset(size.width, y(k.cushion.rappen.toFloat())), 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
        }
        Row { k.months.forEach { m -> Text(monthName(m.month).take(3), style = Ty.Small, modifier = Modifier.weight(1f)) } }
        Text((if (k.cushion.rappen > 0) "- - - your ${k.cushion.money()} cushion · " else "") + "tap a month for its math", style = Ty.Small)
    }
}

@Composable
private fun ColumnScope.MonthMath(d: RoutesDecision, b: Briefing, k: KeyMoment, month: Int) {
    val r = k.months[month]
    Text("Hardest case Routes tested" + if (d.graph.active.hypothetical) " · what-if" else "", style = Ty.Small)
    Text("${r.closingCash.money()} left at the end of ${monthName(month)}", style = Ty.Big.copy(color = if (r.closingCash < k.cushion) Tk.Bronze else Tk.Ink))
    MathRow("Start of month", r.openingCash.money())
    MathRow("Money in", "+" + r.income.money())
    MathRow("Spending", "−" + r.expenses.money())
    if (r.oneOffCosts.rappen != 0L) MathRow("One-off costs", "−" + r.oneOffCosts.money())
    if (r.transfersToRestricted.rappen != 0L) MathRow("Set aside (still yours)", "−" + r.transfersToRestricted.money())
    Divider()
    MathRow("End of month", r.closingCash.money(), strong = true)
    Text("What leads here", style = Ty.Label)
    val s = d.graph.active.session
    val scenario = s.scenarioFor(b.evidence.worstCaseInputs)
    scenario.recurringChanges.filter { it.startMonth <= month && month < it.endMonth }.forEach {
        val delta = if (it.monthlyExpenseDelta.rappen != 0L) "+${it.monthlyExpenseDelta.money()} spending" else "${it.monthlyIncomeDelta.money()} pay"
        Text("• ${it.labelKey}: $delta a month, from ${monthName(it.startMonth)}", style = Ty.Body)
    }
    // Confirmed single amounts are already in the lines above; list only ranges, unknowns and what-ifs.
    causes(s, b, month).filter { c -> c !is ch.ripple.app.Cause.Input || (c.input.unit == InputUnit.CHF && (c.state !is InputState.Exact || c.whatIf != null)) }
        .forEach { c -> Text("• " + causeText(c, d), style = Ty.Body) }
    Text("All figures come from the shared Kotlin engine. Month 1 is shown as January. Cash only: tax, pension and investments are not modelled.", style = Ty.Small)
}

@Composable
private fun MathRow(label: String, value: String, strong: Boolean = false) = Row(Modifier.fillMaxWidth()) {
    Text(label, style = if (strong) Ty.Strong else Ty.Body, modifier = Modifier.weight(1f))
    Text(value, style = if (strong) Ty.Strong else Ty.Body)
}

private fun tagFor(state: InputState, whatIf: Chf?, fictionalExample: Boolean) = when {
    whatIf != null -> Tag.WHATIF
    state is InputState.Exact -> if (fictionalExample) Tag.EXAMPLE else Tag.CONFIRMED
    state is InputState.Range && state.low == state.high -> Tag.ESTIMATE
    state is InputState.Range -> Tag.RANGE
    else -> Tag.UNKNOWN
}

@Composable
private fun ColumnScope.Assumptions(session: RoutesSession, d: RoutesDecision, done: () -> Unit) {
    val s = d.graph.active.session
    var editing by remember { mutableStateOf<String?>(null) }
    val input = editing?.let { id -> s.inputs.first { it.id == id } }
    if (input == null) {
        Text("Tap one to try another value. Trying never changes your confirmed situation.", style = Ty.Small)
        s.inputs.filter { it.unit != InputUnit.MONTH_COUNT || d.spec.durationMonths != null }.forEach { i ->
            val state = s.state(i.id)
            val whatIf = s.hypotheticalValues[i.id]
            val example = d.spec.fictional && i.id in setOf(DecisionSpec.INCOME, DecisionSpec.SPENDING)
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { editing = i.id }.padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d.inputLabel(i.id), style = Ty.Strong, modifier = Modifier.weight(1f))
                    TagChip(if (whatIf == null && d.isAssumed(i.id)) Tag.ASSUMED else tagFor(state, whatIf, example))
                }
                Text(whatIf?.let { "${i.show(it)} (what-if) · confirmed: ${state.show(i)}" } ?: state.show(i), style = Ty.Body)
            }
            Divider()
        }
        Text("Also tested: ${if (s.stress.maxLowIncomeMonths > 0) "pay ${s.stress.lowIncomeReduction.money()} lower in up to ${s.stress.maxLowIncomeMonths} months (assumed)" else "no lower-pay months"}.", style = Ty.Small)
    } else InputEditor(session, d, input, done) { editing = null }
}

@Composable
private fun ColumnScope.InputEditor(session: RoutesSession, d: RoutesDecision, i: DecisionInput, done: () -> Unit, back: () -> Unit) {
    val s = d.graph.active.session
    val current = s.hypotheticalValues[i.id] ?: (s.state(i.id) as? InputState.Exact)?.value ?: i.low
    val steps = ((i.high - i.low).rappen / i.step.rappen).toInt()
    var pos by remember(i.id) { mutableStateOf(((current - i.low).rappen / i.step.rappen).toFloat()) }
    val value = Chf(i.low.rappen + pos.toLong() * i.step.rappen)
    var shown by remember(i.id) { mutableStateOf<Pair<Chf, KeyMoment>?>(null) }
    val turn = remember(i.id) { session.decision(d.id).graph.active.session.whereItTurns(i.id) }
    LinkText("‹ All assumptions", onClick = back)
    Text(d.inputLabel(i.id), style = Ty.Strong)
    Text("Confirmed: ${s.state(i.id).show(i)}", style = Ty.Small)
    Text("Trying ${i.show(value)}", style = Ty.Big.copy(color = Tk.Bronze))
    Slider(pos, { pos = it.coerceIn(0f, steps.toFloat()); shown = null }, valueRange = 0f..steps.toFloat(),
        steps = (steps - 1).coerceIn(0, 200), onValueChangeFinished = { shown = value to preview(s, i.id, value) },
        colors = SliderDefaults.colors(thumbColor = Tk.Ink, activeTrackColor = Tk.Ink, inactiveTrackColor = Tk.Track),
        modifier = Modifier.semantics { contentDescription = "${d.inputLabel(i.id)}, trying ${i.show(value)}" })
    Row { Text(i.show(i.low), style = Ty.Small, modifier = Modifier.weight(1f)); Text(i.show(i.high), style = Ty.Small) }
    turn?.passesUpTo?.takeIf { it < i.high && it >= i.low && turn.changesDecision }?.let {
        Text("Above ${i.show(it)}, the cushion isn't kept in every tested case.", style = Ty.Body)
    }
    val p = shown?.takeIf { it.first == value }?.second
    if (p != null) Text("Not saved. ${findingText(d, p, s)}", style = Ty.Body,
        modifier = Modifier.fillMaxWidth().border(1.dp, Tk.BronzeBar, Tk.R).padding(10.dp))
    else Text("Let go of the slider to see the result.", style = Ty.Small)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Secondary("Keep as what-if", Modifier.weight(1f)) { session.explore(d.id, Hypothesis.WhatIfInput(i.id, value), Via.TAP); done() }
        Primary("This is real", Modifier.weight(1f)) { session.confirm(d.id, Correction.AnswerInput(i.id, InputState.Exact(value)), Via.TAP); done() }
    }
    Text("“This is real” saves ${i.show(value)} as your confirmed ${d.inputLabel(i.id).lowercase()}; the earlier version stays in Versions.", style = Ty.Small)
}

@Composable
private fun ColumnScope.Compare(session: RoutesSession, d: RoutesDecision, b: Briefing, done: () -> Unit) {
    val alts = remember(d) { alternatives(d) }
    Text("Each route uses the same inputs and the same hardest tested case. Worked out now; nothing is saved unless you explore it.", style = Ty.Small)
    alts.forEachIndexed { idx, (label, k) ->
        Column(Modifier.fillMaxWidth().border(1.dp, if (idx == 0) Tk.Ink else Tk.Line, Tk.Card).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = Ty.Strong, modifier = Modifier.weight(1f))
                if (idx == 0) Text("shown now", style = Ty.Small)
            }
            Text("Lowest ${k.moneyLeft.money()} in ${monthName(k.month)}", style = Ty.Body.copy(color = if (k.belowCushion) Tk.Bronze else Tk.Ink))
            if (idx > 0) {
                val opt = d.graph.active.session.options.first { it.labelKey == label }
                LinkText("Explore this route (what-if)") { session.explore(d.id, Hypothesis.TryOption(opt.id), Via.TAP); done() }
            }
        }
    }
    b.offeredRepair?.let { r ->
        Divider()
        Text("A change Routes tested", style = Ty.Label)
        Text("Spending ${r.monthly.money()} less a month from ${monthName(r.startMonth)} would keep your cushion in every tested case.", style = Ty.Body)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Secondary("Try it", Modifier.weight(1f)) { session.explore(d.id, Hypothesis.ApplyCut(r), Via.TAP); done() }
            Secondary("Not for me", Modifier.weight(1f)) {
                session.confirm(d.id, Correction.RejectRepair(r, RejectionScope.THIS_AMOUNT_OR_MORE, "Not for me"), Via.TAP); done()
            }
        }
        Text("“Not for me” is saved as your limit: Routes won't suggest cutting this much or more again.", style = Ty.Small)
    }
}

fun versionLabel(d: RoutesDecision, n: DecisionNode): String = when (val c = n.change) {
    GraphChange.Start -> "Started"
    is GraphChange.Corrected -> runCatching { describe(d, c.correction) }.getOrDefault("Corrected")
    is GraphChange.Explored -> runCatching { describe(d, c.hypothesis) }.getOrDefault("What-if")
    is GraphChange.MarketDataUpdated -> "Market data updated"
}

@Composable
private fun ColumnScope.Versions(session: RoutesSession, d: RoutesDecision, platform: PhonePlatform, done: () -> Unit) {
    Text("Every change is kept. Tap one to look at it.", style = Ty.Small)
    d.graph.nodes.asReversed().forEach { n ->
        val k = remember(n.id, d) { keyMoment(d.graph.briefing(n.id)) }
        val at = d.steps.filter { it !is Step.Select }.getOrNull(n.id.removePrefix("n").toInt() - 1)?.let { platform.timeText(it.at) }.orEmpty()
        val shown = n.id == d.graph.activeNodeId
        Column(Modifier.fillMaxWidth().border(if (shown) 1.5.dp else 1.dp, if (shown) Tk.Ink else Tk.Line, Tk.Card)
            .clickable(role = Role.Button) { session.select(d.id, n.id); done() }.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("v${n.revision} · ${versionLabel(d, n)}", style = Ty.Strong, modifier = Modifier.weight(1f))
                TagChip(if (n.hypothetical) Tag.WHATIF else Tag.CONFIRMED)
            }
            Text("Lowest ${k.moneyLeft.money()} in ${monthName(k.month)}" + (if (at.isNotEmpty()) " · $at" else "") + if (shown) " · shown" else "", style = Ty.Small)
        }
    }
}

@Composable
private fun ColumnScope.More(session: RoutesSession, d: RoutesDecision, nav: PhoneNav) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Text("Open on a laptop", style = Ty.Label)
    Text("Copy this decision's data, send it to yourself, and on the laptop choose “Open a decision from your phone”. It carries the situation, changes and every version, not a transcript. Nothing syncs.", style = Ty.Body)
    Secondary("Copy this decision's data") { clipboard.setText(AnnotatedString(d.export())); copied = true }
    if (copied) Text("Copied (Routes decision format v$FORMAT_VERSION). Nothing was sent anywhere.", style = Ty.Small)
    Divider()
    Text("Your question: “${d.spec.intention}”", style = Ty.Small)
    LinkText("Delete this decision and its versions") { session.deleteDecision(d.id); nav.openDecision = null }
}
