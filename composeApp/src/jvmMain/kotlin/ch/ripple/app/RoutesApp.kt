package ch.ripple.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ch.ripple.core.Briefing
import ch.ripple.core.Chf
import ch.ripple.core.Correction
import ch.ripple.core.DecisionGraph
import ch.ripple.core.DecisionKind
import ch.ripple.core.DecisionNode
import ch.ripple.core.DecisionSession
import ch.ripple.core.ExpenseReduction
import ch.ripple.core.GraphChange
import ch.ripple.core.Hypothesis
import ch.ripple.core.InputState
import ch.ripple.core.PersonalCommitment
import ch.ripple.core.RejectionScope
import ch.ripple.core.SampleData
import ch.ripple.core.briefing
import ch.ripple.core.correct
import ch.ripple.core.explore
import ch.ripple.core.offeredRepair
import ch.ripple.core.select
import ch.ripple.core.startGraph
import ch.ripple.core.tradeOffs

@Composable
fun RoutesApp(reduceMotion: Boolean = false, escPresses: Int = 0) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Rt.Ink, onPrimary = Rt.White, background = Rt.White, surface = Rt.White, onSurface = Rt.Ink, outline = Rt.Line)) {
        CompositionLocalProvider(LocalReduceMotion provides reduceMotion) { RoutesScreen(escPresses) }
    }
}

/** What the last confirmed change did to the tightest month, and which version it replaced. */
private data class ChangeNote(val because: String, val beforeMonth: Int, val before: Chf, val afterMonth: Int, val after: Chf, val previousId: String)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun RoutesScreen(escPresses: Int) {
    var graph by remember { mutableStateOf(startGraph(SampleData.alexEvolvingSession)) }
    var confirmedId by remember { mutableStateOf(graph.activeNodeId) }
    var openMonth by remember { mutableStateOf<Int?>(null) }
    var visited by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<ChangeNote?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val reduce = LocalReduceMotion.current

    val node = graph.active
    val briefing = remember(graph, node.id) { graph.briefing(node.id) }
    val key = remember(briefing) { keyMoment(briefing) }

    fun attempt(block: () -> Unit) {
        error = null
        runCatching(block).onFailure { error = it.message ?: "Routes couldn't do that." }
    }
    fun confirm(c: Correction, because: String) = attempt {
        val before = keyMoment(graph.briefing(confirmedId))
        val previous = confirmedId
        val u = graph.correct(c, fromId = confirmedId)
        val after = keyMoment(u.graph.briefing(u.node.id))
        graph = u.graph.select(u.node.id)
        confirmedId = u.node.id
        note = ChangeNote(because, before.month, before.moneyLeft, after.month, after.moneyLeft, previous)
    }
    fun explore(h: Hypothesis) = attempt {
        val u = graph.explore(h, fromId = confirmedId)
        graph = u.graph.select(u.node.id)
    }
    fun choosePath(optionId: String?) = attempt {
        graph = if (optionId == null) graph.select(confirmedId) else {
            val existing = graph.children(confirmedId).lastOrNull {
                it.session.activeOptionId == optionId && it.session.plannedCut == null && it.session.hypotheticalValues.isEmpty()
            }
            existing?.let { graph.select(it.id) } ?: graph.explore(Hypothesis.TryOption(optionId), fromId = confirmedId).let { it.graph.select(it.node.id) }
        }
        openMonth = null
    }
    fun view(id: String) { graph = graph.select(id); openMonth = null }
    fun back() { openMonth = null; visited = true }

    LaunchedEffect(escPresses) { if (escPresses > 0 && openMonth != null) back() }

    Box(Modifier.fillMaxSize().background(Rt.White)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TopBar(node.session.profile.name)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val side = if (maxWidth > 1180.dp) 160.dp else 48.dp
                Column(Modifier.padding(start = side, end = 48.dp, top = 24.dp, bottom = 40.dp).widthIn(max = 880.dp)) {
                    error?.let { Text("Routes couldn't do that: $it", style = RtType.Body, modifier = Modifier.padding(bottom = 16.dp)) }
                    SharedTransitionLayout {
                        AnimatedContent(
                            targetState = openMonth != null,
                            transitionSpec = {
                                if (reduce) EnterTransition.None togetherWith ExitTransition.None
                                else fadeIn(tween(320, 120)) togetherWith fadeOut(tween(180))
                            },
                        ) { inMoment ->
                            val shared = if (reduce) Modifier else Modifier.sharedBounds(rememberSharedContentState("moment"), this@AnimatedContent)
                            if (!inMoment) DecisionView(
                                graph, node, briefing, key, confirmedId, note, visited, shared,
                                onOpen = { openMonth = key.month }, onPath = ::choosePath, onView = ::view,
                            ) else MomentView(
                                node, briefing, key, openMonth ?: key.month, confirmedId, note, shared,
                                onBack = ::back, onMonth = { openMonth = it }, onView = ::view,
                                onConfirm = ::confirm, onExplore = ::explore,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(56.dp))
        }
        Text(
            "Independent prototype for the UBS AI Financial Twin challenge. Not made or endorsed by UBS. Fictional profile; not a forecast or financial advice.",
            style = RtType.Small,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Rt.White).padding(start = 40.dp, end = 40.dp, top = 10.dp, bottom = 18.dp),
        )
    }
}

@Composable
private fun TopBar(name: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        RoutesMark()
        Spacer(Modifier.width(9.dp))
        Text("Routes", style = RtType.Body.copy(fontWeight = FontWeight.Bold, fontSize = RtType.Body.fontSize))
        Spacer(Modifier.weight(1f))
        Text(name, style = RtType.Small)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(32.dp).background(Rt.Chalk, Rt.Radius), contentAlignment = Alignment.Center) {
            Text(name.take(1), style = RtType.Label)
        }
    }
}

/** Routes' own forked-path mark, drawn in the action red. */
@Composable
private fun RoutesMark() {
    androidx.compose.foundation.Canvas(Modifier.size(22.dp).semantics { contentDescription = "Routes" }) {
        val s = size.width / 24f
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(2.4f * s, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(7 * s, 21 * s); lineTo(7 * s, 14 * s); cubicTo(7 * s, 10.5f * s, 9.5f * s, 8 * s, 13 * s, 8 * s); lineTo(19 * s, 8 * s)
            moveTo(7 * s, 14 * s); cubicTo(7 * s, 10.5f * s, 5.5f * s, 8 * s, 4 * s, 6 * s)
        }
        drawPath(p, Rt.Red, style = stroke)
        drawCircle(Rt.Red, 1.7f * s, androidx.compose.ui.geometry.Offset(19 * s, 8 * s))
    }
}

/* ============================== The decision ============================== */

@Composable
private fun DecisionView(
    graph: DecisionGraph, node: DecisionNode, briefing: Briefing, key: KeyMoment, confirmedId: String,
    note: ChangeNote?, visited: Boolean, shared: Modifier,
    onOpen: () -> Unit, onPath: (String?) -> Unit, onView: (String) -> Unit,
) {
    val session = node.session
    val decision = node.summary.decision
    var showPaths by remember { mutableStateOf(visited) }
    var showVersions by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        StateBanner(node, confirmedId, onView)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("You asked: “${decision.intention}”", style = RtType.Label)
            Text(plain(decision.labelKey), style = RtType.Title)
            Text(comparedWith(session), style = RtType.Lead)
        }
        Text(finding(key, session), style = RtType.Finding, modifier = Modifier.padding(top = 18.dp))
        if (note != null && node.id == confirmedId) ChangeLine(note, onView)
        Button(
            onClick = onOpen, modifier = shared.focusRing(), shape = Rt.Radius,
            colors = ButtonDefaults.buttonColors(containerColor = Rt.Red, contentColor = Rt.White),
            contentPadding = PaddingValues(horizontal = 26.dp, vertical = 16.dp),
        ) { Text((if (visited) "Look at ${monthName(key.month)} again" else "See what happens in ${monthName(key.month)}") + "  →", style = RtType.Button) }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            LinkText(if (showPaths) "Hide other paths" else "Compare other paths") { showPaths = !showPaths }
            if (graph.path(confirmedId).size > 1) LinkText(if (showVersions) "Hide earlier versions" else "Earlier versions") { showVersions = !showVersions }
        }
        Reveal(showPaths) { OtherPaths(graph, node, confirmedId, onPath) }
        Reveal(showVersions) { Versions(graph, node, confirmedId, onView) }
    }
}

private fun comparedWith(s: DecisionSession): String {
    if (s.options.firstOrNull { it.id == s.activeOptionId }?.kind == DecisionKind.STAY_CURRENT) return "This is the path you're on now."
    val stay = s.options.firstOrNull { it.kind == DecisionKind.STAY_CURRENT }
    return "Compared with " + (stay?.let { plain(it.labelKey).replaceFirstChar(Char::lowercase) } ?: "your current path")
}

private fun cushionWords(key: KeyMoment, s: DecisionSession) =
    if (s.constraints.minCashBuffer == null) "a ${key.cushion.money()} cushion" else "your ${key.cushion.money()} cushion"

/** The finding sentence, assembled from the key moment. Every amount is an engine value. */
private fun finding(key: KeyMoment, s: DecisionSession) = buildAnnotatedString {
    val mark = SpanStyle(background = Rt.Gold, fontWeight = FontWeight.Normal)
    val month = monthName(key.month)
    val condition = if (key.lowerPayMonths.isNotEmpty())
        "if your pay is ${s.stress.lowIncomeReduction.money()} lower in ${monthList(key.lowerPayMonths)}"
    else "in the hardest case Routes tested"
    val n = key.monthsBelowCushion.size
    when {
        key.moneyLeft.isNegative -> {
            append("${condition.replaceFirstChar(Char::uppercase)}, ")
            withStyle(mark) { append("you'd run out of money in $month") }
            append(": ${(-key.moneyLeft).money()} short.")
        }
        n == 1 -> {
            append("Most months look fine. But $condition, ")
            withStyle(mark) { append("$month could be tight") }
            append(": you'd have ${key.moneyLeft.money()} left, below ${cushionWords(key, s)}.")
        }
        n > 1 -> {
            append("$n months could fall below ${cushionWords(key, s)} $condition. ")
            withStyle(mark) { append("The tightest is $month") }
            append(": ${key.moneyLeft.money()} left.")
        }
        else -> {
            append("Even $condition, ")
            withStyle(mark) { append("every month stays above ${cushionWords(key, s)}") }
            append(". The tightest is $month: ${key.moneyLeft.money()} left.")
        }
    }
}

@Composable
private fun ChangeLine(note: ChangeNote, onView: (String) -> Unit) {
    val text = if (note.beforeMonth == note.afterMonth && note.before == note.after)
        "Updated because ${note.because}. Money left in ${monthName(note.afterMonth)} is unchanged: ${note.after.money()}."
    else if (note.beforeMonth == note.afterMonth)
        "Updated because ${note.because}: ${monthName(note.afterMonth)} ${note.before.money()} → ${note.after.money()}."
    else "Updated because ${note.because}: the tightest month is now ${monthName(note.afterMonth)} (${note.after.money()}); before, it was ${monthName(note.beforeMonth)} (${note.before.money()})."
    Row(Modifier.background(Rt.Chalk, Rt.Radius).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text, style = RtType.Body, modifier = Modifier.weight(1f, fill = false))
        LinkText("See the earlier version") { onView(note.previousId) }
    }
}

@Composable
private fun StateBanner(node: DecisionNode, confirmedId: String, onView: (String) -> Unit) {
    val text = when {
        node.hypothetical -> "What-if, not saved to your plan" + (whatIfDescription(node)?.let { ": $it" } ?: "") + "."
        node.id != confirmedId -> "You're looking at an earlier version. Your plan has changed since."
        else -> return
    }
    Row(Modifier.fillMaxWidth().border(1.dp, Rt.Ink, Rt.Radius).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = RtType.Body, modifier = Modifier.weight(1f))
        LinkText(if (node.hypothetical) "Back to your plan" else "Back to the latest") { onView(confirmedId) }
    }
}

private fun whatIfDescription(node: DecisionNode): String? {
    val s = node.session
    return s.plannedCut?.let { "spending ${it.monthly.money()} less each month from ${monthName(it.startMonth)}" }
        ?: s.options.firstOrNull { it.id == s.activeOptionId }?.let { plain(it.labelKey).replaceFirstChar(Char::lowercase) }
}

@Composable
private fun OtherPaths(graph: DecisionGraph, node: DecisionNode, confirmedId: String, onPath: (String?) -> Unit) {
    val confirmed = graph.node(confirmedId).session
    val options = remember(graph, confirmedId) { graph.tradeOffs(confirmedId) }
    Column {
        Text("Other paths, with the same facts", style = RtType.Label, modifier = Modifier.padding(bottom = 6.dp))
        options.forEach { t ->
            val viewing = node.session.activeOptionId == t.optionId && node.session.plannedCut == null && node.session.hypotheticalValues.isEmpty()
            val name = plain(t.optionId?.let { id -> confirmed.options.first { it.id == id }.labelKey } ?: confirmed.decisionLabelKey)
            Row(
                Modifier.fillMaxWidth().focusRing().clickable(role = Role.Button) { onPath(t.optionId) }
                    .border(BorderStroke(1.dp, Rt.Line)).padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, style = RtType.Body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.width(260.dp))
                Text(
                    if (t.bufferKeptWithoutChanges) "No tight months. Lowest: ${t.worstMinimumCash.money()}"
                    else "Can dip below the cushion. Lowest: ${t.worstMinimumCash.money()}",
                    style = RtType.Body.copy(color = if (t.bufferKeptWithoutChanges) Rt.Ink else Rt.Bronze), modifier = Modifier.weight(1f),
                )
                Text(if (viewing) "Viewing" else "Look at it", style = RtType.Small)
            }
        }
    }
}

@Composable
private fun Versions(graph: DecisionGraph, node: DecisionNode, confirmedId: String, onView: (String) -> Unit) {
    Column {
        Text("Every version is kept", style = RtType.Label, modifier = Modifier.padding(bottom = 6.dp))
        graph.path(confirmedId).forEachIndexed { i, v ->
            val left = keyMoment(graph.briefing(v.id))
            Row(
                Modifier.fillMaxWidth().focusRing().clickable(role = Role.Button) { onView(v.id) }
                    .border(BorderStroke(1.dp, Rt.Line)).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Version ${i + 1}", style = RtType.Label, modifier = Modifier.width(110.dp))
                Text(versionLabel(v), style = RtType.Body, modifier = Modifier.weight(1f))
                Text("${monthName(left.month)}: ${left.moneyLeft.money()}" + if (v.id == node.id) " · viewing" else "", style = RtType.Small)
            }
        }
    }
}

private fun versionLabel(n: DecisionNode): String = when (val c = n.change) {
    GraphChange.Start -> "Where you started"
    is GraphChange.Corrected -> when (val k = c.correction) {
        is Correction.AnswerInput -> "You confirmed ${plain(n.session.inputs.first { it.id == k.inputId }.questionKey).replaceFirstChar(Char::lowercase)}"
        is Correction.RejectRepair -> "You said no to spending less"
        is Correction.RemoveRejection -> "You took back a “not for me”"
        is Correction.SetMinCashBuffer -> "You set your cushion"
        is Correction.SetCommitment -> "You added ${k.commitment.labelKey}"
        else -> "You updated your plan"
    }
    is GraphChange.Explored -> "A what-if"
    is GraphChange.MarketDataUpdated -> "New reference figures"
}

/* ============================== One month, explained ============================== */

@Composable
private fun MomentView(
    node: DecisionNode, briefing: Briefing, key: KeyMoment, month: Int, confirmedId: String, note: ChangeNote?, shared: Modifier,
    onBack: () -> Unit, onMonth: (Int) -> Unit, onView: (String) -> Unit,
    onConfirm: (Correction, String) -> Unit, onExplore: (Hypothesis) -> Unit,
) {
    val reduce = LocalReduceMotion.current
    val session = node.session
    val record = key.months[month]
    val below = record.closingCash < key.cushion
    var why by remember { mutableStateOf(false) }
    var sure by remember { mutableStateOf(false) }
    var math by remember { mutableStateOf(false) }
    var cushionEdit by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LinkText(plain(node.summary.decision.labelKey), onBack)
            Text("›", style = RtType.Body.copy(color = Rt.Ink2))
            Text(monthName(month), style = RtType.Body.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.weight(1f))
            Text("Esc to go back", style = RtType.Small)
        }
        StateBanner(node, confirmedId, onView)
        MonthRow(key, month, onMonth)

        Column(
            shared.fillMaxWidth().background(Rt.White, Rt.CardRadius).border(1.dp, Rt.Line, Rt.CardRadius)
                .then(if (reduce) Modifier else Modifier.animateContentSize(tween(380))).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                AnimatedMoney(record.closingCash, RtType.Big.copy(color = if (below) Rt.Bronze else Rt.Ink))
                Text(" left at the end of ${monthName(month)}", style = RtType.Big)
            }
            Meter(record.closingCash, key.cushion, key.months.maxOf { it.closingCash })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val diff = record.closingCash - key.cushion
                Text(
                    "That's ${(if (diff.isNegative) -diff else diff).money()} ${if (diff.isNegative) "below" else "above"} ${cushionWords(key, session)}" +
                        if (session.constraints.minCashBuffer == null) ", Routes' starting guess." else ".",
                    style = RtType.Lead,
                )
                LinkText(if (cushionEdit) "Close" else "Change it") { cushionEdit = !cushionEdit }
            }
            Reveal(cushionEdit) {
                AmountEditor("Cushion you want to keep", key.cushion, "Use this cushion") {
                    onConfirm(Correction.SetMinCashBuffer(it), "you set your cushion to ${it.money()}"); cushionEdit = false
                }
            }
            if (note != null && node.id == confirmedId) ChangeLine(note, onView)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (why) SecondaryButton("Hide what led here") { why = false } else PrimaryButton("What led here?") { why = true }
                SecondaryButton(if (sure) "Hide how sure" else "How sure is this?") { sure = !sure }
                Spacer(Modifier.width(8.dp))
                LinkText(if (math) "Hide the math" else "Show the math") { math = !math }
            }
            Reveal(sure) { HowSure(briefing, key, session) }
            Reveal(why) { WhatLedHere(node, briefing, key, month, confirmedId, onConfirm, onExplore) }
            Reveal(math) { TheMath(record) }
        }
    }
}

@Composable
private fun MonthRow(key: KeyMoment, selected: Int, onMonth: (Int) -> Unit) {
    val wide = key.months.size > 8
    Row(
        Modifier.fillMaxWidth().then(if (wide) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        key.months.forEach { r ->
            val on = r.month == selected
            val below = r.closingCash < key.cushion
            Column(
                (if (wide) Modifier.width(130.dp) else Modifier.weight(1f))
                    .focusRing().clickable(role = Role.Tab) { onMonth(r.month) }
                    .background(if (on) Rt.White else Rt.Chalk, Rt.Radius)
                    .border(if (on) 2.dp else 0.dp, if (on) Rt.Ink else Rt.Chalk, Rt.Radius)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Text(monthName(r.month).take(3), style = RtType.Small)
                AnimatedMoney(r.closingCash, RtType.Body.copy(fontWeight = FontWeight.SemiBold, color = if (below) Rt.Bronze else Rt.Ink))
                Text(if (below) "below cushion" else " ", style = RtType.Small.copy(color = Rt.Bronze))
            }
        }
    }
}

@Composable
private fun Meter(value: Chf, cushion: Chf, highest: Chf) {
    val reduce = LocalReduceMotion.current
    val cap = maxOf(highest.rappen, cushion.rappen * 2, 1L).toFloat()
    val f by animateFloatAsState((value.rappen.coerceAtLeast(0) / cap).coerceIn(0f, 1f), if (reduce) snap() else tween(700))
    val c = (cushion.rappen / cap).coerceIn(0f, 1f)
    BoxWithConstraints(Modifier.fillMaxWidth().height(40.dp).semantics { contentDescription = "${value.money()} left, cushion ${cushion.money()}" }) {
        Text("${cushion.money()} cushion", style = RtType.Small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.offset(x = (maxWidth * c - 52.dp).coerceAtLeast(0.dp)))
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(6.dp).background(Rt.Track)) {
            Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(if (value < cushion) Rt.BronzeBar else Rt.Ink))
        }
        Box(Modifier.align(Alignment.BottomStart).offset(x = maxWidth * c, y = 6.dp).width(2.dp).height(18.dp).background(Rt.Ink))
    }
}

@Composable
private fun HowSure(b: Briefing, key: KeyMoment, s: DecisionSession) {
    val t = tested(b)
    val uncertain = s.inputs.filter { s.state(it.id) !is InputState.Exact && it.id !in s.hypotheticalValues }
    Column(Modifier.fillMaxWidth().background(Rt.Chalk, Rt.Radius).padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("What if things go differently?", style = RtType.Label)
        Text(
            buildString {
                if (uncertain.isNotEmpty()) append("Routes tried ${t.inputCombinations} values for what you don't know yet and kept the hardest one. ")
                append("With it, Routes tried ${t.cases} versions of the next ${key.months.size} months, with up to ${s.stress.maxLowIncomeMonths} months of pay ${s.stress.lowIncomeReduction.money()} lower. ")
                append(if (t.dipping == 0) "None of them dip below the cushion." else "${t.dipping} of them dip below the cushion.")
            },
            style = RtType.Body,
        )
        Text("This counts tested cases. It is not a chance of it happening.", style = RtType.Small)
    }
}

@Composable
private fun TheMath(r: ch.ripple.core.MonthRecord) {
    Column(Modifier.fillMaxWidth().background(Rt.Chalk, Rt.Radius).padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("How ${monthName(r.month)} adds up", style = RtType.Label, modifier = Modifier.padding(bottom = 4.dp))
        MathLine("Money at the start", r.openingCash.money())
        MathLine("Pay", "+ ${r.income.money()}")
        MathLine("Living costs" + if (r.commitments.rappen > 0) " (incl. ${r.commitments.money()} you must keep paying)" else "", "− ${r.expenses.money()}")
        if (r.oneOffCosts.rappen > 0) MathLine("One-off costs", "− ${r.oneOffCosts.money()}")
        if (r.transfersToRestricted.rappen > 0) MathLine("Set aside (not spent, you get it back)", "− ${r.transfersToRestricted.money()}")
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(Rt.Ink))
        MathLine("Left at the end", r.closingCash.money(), bold = true)
        Text("Cash only: no tax, pension, debt or investments are modelled. Month 1 is shown as January. Same inputs give the same result every time.", style = RtType.Small, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun MathLine(label: String, value: String, bold: Boolean = false) {
    val st = RtType.Body.copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
    Row(Modifier.fillMaxWidth().widthIn(max = 560.dp)) { Text(label, style = st, modifier = Modifier.weight(1f)); Text(value, style = st) }
}

/* ============================== What led here ============================== */

@Composable
private fun WhatLedHere(
    node: DecisionNode, b: Briefing, key: KeyMoment, month: Int, confirmedId: String,
    onConfirm: (Correction, String) -> Unit, onExplore: (Hypothesis) -> Unit,
) {
    val session = node.session
    val list = remember(node.id, month) { causes(session, b, month) }
    var openInput by remember { mutableStateOf<String?>(null) }
    var commitmentForm by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row { Text("What led here", style = RtType.Label, modifier = Modifier.weight(1f)); Text("Routes tests a hard case, not a forecast", style = RtType.Small) }
        list.forEachIndexed { i, c ->
            when (c) {
                is Cause.LowerPay -> CauseRow(i + 1, "Your pay could be ${c.reduction.money()} lower in ${monthList(c.months)}", "if things go differently")
                is Cause.SetAside -> CauseRow(i + 1, "${c.label.ifBlank { "Money set aside" }}: ${c.amount.money()} in ${monthName(c.month)}. Not spent; you can't use it while it's held.", "in the case shown")
                is Cause.OneOff -> CauseRow(i + 1, "${c.label.ifBlank { "One-off cost" }}: ${c.amount.money()} in ${monthName(c.month)}", "in the case shown")
                is Cause.Commitment -> CauseRow(i + 1, "${c.label}: ${c.monthly.money()} every month", "you must keep paying")
                is Cause.Input -> InputCause(i + 1, c, session, key, month, open = openInput == c.input.id,
                    onToggle = { openInput = if (openInput == c.input.id) null else c.input.id },
                    onConfirm = { v -> onConfirm(Correction.AnswerInput(c.input.id, InputState.Exact(v)), "you confirmed ${plain(c.input.questionKey).replaceFirstChar(Char::lowercase)}"); openInput = null })
            }
        }
        // Adjustments: offered only on the confirmed plan, never as an instruction.
        val offered = b.offeredRepair
        if (offered != null && !node.hypothetical) Detour(offered, onTry = { onExplore(Hypothesis.ApplyCut(offered)) },
            onRefuse = { onConfirm(Correction.RejectRepair(offered, RejectionScope.THIS_AMOUNT_OR_MORE), "you said no to spending ${offered.monthly.money()} or more less") })
        if (node.summary.status == ch.ripple.core.NodeStatus.ONLY_UNACCEPTABLE_REPAIRS)
            Text("Routes found no other small change that fits what you said. The other paths may.", style = RtType.Body.copy(color = Rt.Ink2))
        session.constraints.rejections.forEach { r ->
            Row(Modifier.fillMaxWidth().background(Rt.Chalk, Rt.Radius).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("You said no to spending ${r.action.monthly.money()} or more less each month. Routes won't suggest it again.", style = RtType.Body, modifier = Modifier.weight(1f))
                if (!node.hypothetical && node.id == confirmedId) LinkText("Undo") { onConfirm(Correction.RemoveRejection(r.id), "you took back your “not for me”") }
            }
        }
        if (!node.hypothetical && node.id == confirmedId) {
            Spacer(Modifier.height(4.dp))
            LinkText(if (commitmentForm) "Close" else "Something you must keep paying every month?") { commitmentForm = !commitmentForm }
            Reveal(commitmentForm) {
                CommitmentForm { label, amount ->
                    val id = "commitment-" + (session.constraints.commitments.size + 1)
                    onConfirm(Correction.SetCommitment(PersonalCommitment(id, label, amount)), "you added $label")
                    commitmentForm = false
                }
            }
        }
    }
}

@Composable
private fun CauseRow(n: Int, text: String, source: String, background: androidx.compose.ui.graphics.Color = Rt.Chalk, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().background(background, Rt.Radius).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).background(Rt.White, Rt.Radius), contentAlignment = Alignment.Center) { Text("$n", style = RtType.Small.copy(fontWeight = FontWeight.Bold)) }
        Spacer(Modifier.width(12.dp))
        Text(text, style = RtType.Body, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        if (trailing != null) trailing() else Text(source, style = RtType.Small)
    }
}

@Composable
private fun InputCause(
    n: Int, c: Cause.Input, session: DecisionSession, key: KeyMoment, month: Int, open: Boolean,
    onToggle: () -> Unit, onConfirm: (Chf) -> Unit,
) {
    val name = plain(c.input.questionKey)
    val text = when {
        c.whatIf != null -> "$name in this what-if: ${c.whatIf.money()}. Not a confirmed fact."
        c.state is InputState.Exact -> "$name: ${c.state.value.money()}. You confirmed it."
        c.state is InputState.Range -> "$name: between ${c.state.low.money()} and ${c.state.high.money()}, you said."
        else -> "$name: you don't know it yet. Routes tried ${c.input.low.money()} to ${c.input.high.money()}."
    }
    val confirmed = c.state is InputState.Exact
    Column(
        Modifier.fillMaxWidth()
            .background(if (confirmed && !open) Rt.Gold else if (open) Rt.White else Rt.Chalk, Rt.Radius)
            .border(if (open) 1.5.dp else 0.dp, if (open) Rt.Ink else Rt.Chalk, Rt.Radius),
    ) {
        CauseRow(n, text, "", androidx.compose.ui.graphics.Color.Transparent) { LinkText(if (open) "Close" else if (confirmed) "Change it" else "Check it", onToggle) }
        Reveal(open) {
            Column(Modifier.padding(start = 52.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                c.holdsUpTo?.let { Text("Up to ${it.money()}, every month stays at or above the cushion in every case Routes tested.", style = RtType.Body.copy(color = Rt.Ink2)) }
                InputExplorer(c, session, key, month, onConfirm)
            }
        }
    }
}

/** Trying a value is a what-if computed on a copy; only “That's the real amount” changes the plan. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun InputExplorer(c: Cause.Input, session: DecisionSession, key: KeyMoment, month: Int, onConfirm: (Chf) -> Unit) {
    val lo = c.input.low.rappen / 100
    val hi = c.input.high.rappen / 100
    val start = ((c.state as? InputState.Exact)?.value ?: c.whatIf ?: c.testedValue ?: c.input.high).rappen / 100
    var text by remember { mutableStateOf(start.toString()) }
    val value = text.toLongOrNull()?.let { Chf.francs(it) }
    val result = remember(value, session) { value?.let { v -> runCatching { preview(session, c.input.id, v) }.getOrNull() } }
    if (hi > lo) {
        Column {
            Slider(
                value = (text.toLongOrNull() ?: start).coerceIn(lo, hi).toFloat(),
                onValueChange = { text = it.toLong().toString() },
                valueRange = lo.toFloat()..hi.toFloat(),
                colors = SliderDefaults.colors(thumbColor = Rt.Ink, activeTrackColor = Rt.Ink, inactiveTrackColor = Rt.Track),
                track = { st ->
                    SliderDefaults.Track(
                        sliderState = st, modifier = Modifier.height(6.dp), thumbTrackGapSize = 0.dp, drawStopIndicator = null,
                        colors = SliderDefaults.colors(activeTrackColor = Rt.Ink, inactiveTrackColor = Rt.Track),
                    )
                },
                modifier = Modifier.semantics { contentDescription = "Try a value for ${plain(c.input.questionKey)}" },
            )
            Row { Text(c.input.low.money(), style = RtType.Small, modifier = Modifier.weight(1f)); c.holdsUpTo?.let { Text("turns at ${it.money()}", style = RtType.Small, modifier = Modifier.weight(1f)) }; Text(c.input.high.money(), style = RtType.Small) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = text, onValueChange = { t -> text = t.filter(Char::isDigit).take(9) },
            prefix = { Text("CHF ") }, singleLine = true, modifier = Modifier.width(190.dp),
            label = { Text("Try an amount") },
        )
        Text(
            when {
                value == null -> "Enter an amount in francs."
                result == null -> "Routes can't use that amount."
                else -> "At ${value.money()}, the tightest month would be ${monthName(result.month)} with ${result.moneyLeft.money()} left" +
                    (if (result.belowCushion) ", below the cushion." else ".") + " Just trying: nothing is saved."
            },
            style = RtType.Body, modifier = Modifier.weight(1f),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PrimaryButton("That's the real amount", enabled = value != null && result != null) { value?.let(onConfirm) }
    }
}

@Composable
private fun Detour(r: ExpenseReduction, onTry: () -> Unit, onRefuse: () -> Unit) {
    Row(Modifier.fillMaxWidth().border(1.dp, Rt.Line, Rt.Radius).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("One way around it: spend ${r.monthly.money()} less each month from ${monthName(r.startMonth)}.", style = RtType.Body, modifier = Modifier.weight(1f))
        SecondaryButton("Try it", onTry)
        SecondaryButton("Not for me", onRefuse)
    }
}

@Composable
private fun CommitmentForm(onAdd: (String, Chf) -> Unit) {
    var label by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(label, { label = it.take(40) }, label = { Text("What is it? e.g. support for my mum") }, singleLine = true, modifier = Modifier.width(300.dp))
        OutlinedTextField(amount, { amount = it.filter(Char::isDigit).take(7) }, prefix = { Text("CHF ") }, label = { Text("Every month") }, singleLine = true, modifier = Modifier.width(170.dp))
        PrimaryButton("Add", enabled = label.isNotBlank() && amount.toLongOrNull() != null) { onAdd(label.trim(), Chf.francs(amount.toLong())) }
    }
}

@Composable
private fun AmountEditor(label: String, current: Chf, action: String, onUse: (Chf) -> Unit) {
    var text by remember { mutableStateOf((current.rappen / 100).toString()) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(text, { text = it.filter(Char::isDigit).take(9) }, prefix = { Text("CHF ") }, label = { Text(label) }, singleLine = true, modifier = Modifier.width(240.dp))
        PrimaryButton(action, enabled = text.toLongOrNull() != null) { onUse(Chf.francs(text.toLong())) }
    }
}

/* ============================== Small parts ============================== */

@Composable
private fun AnimatedMoney(value: Chf, style: TextStyle) {
    val reduce = LocalReduceMotion.current
    AnimatedContent(value, transitionSpec = {
        if (reduce) EnterTransition.None togetherWith ExitTransition.None
        else (fadeIn(tween(350, 150)) + slideInVertically(tween(350, 150)) { it / 3 }) togetherWith (fadeOut(tween(180)) + slideOutVertically(tween(180)) { -it / 3 })
    }) { Text(it.money(), style = style) }
}

@Composable
private fun Reveal(visible: Boolean, content: @Composable () -> Unit) {
    val reduce = LocalReduceMotion.current
    AnimatedVisibility(
        visible,
        enter = if (reduce) EnterTransition.None else fadeIn(tween(250, 80)) + expandVertically(tween(320)),
        exit = if (reduce) ExitTransition.None else fadeOut(tween(150)) + shrinkVertically(tween(260)),
    ) { content() }
}

@Composable
private fun LinkText(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = RtType.Body.copy(color = Rt.LinkRed, textDecoration = TextDecoration.Underline),
        modifier = Modifier.focusRing().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 2.dp, vertical = 2.dp),
    )
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick, Modifier.focusRing(), enabled = enabled, shape = Rt.Radius,
        colors = ButtonDefaults.buttonColors(containerColor = Rt.Red, contentColor = Rt.White, disabledContainerColor = Rt.Track, disabledContentColor = Rt.Ink2),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    ) { Text(text, style = RtType.Button) }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick, Modifier.focusRing(), shape = Rt.Radius, border = BorderStroke(1.dp, Rt.Ink),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Rt.Ink),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    ) { Text(text, style = RtType.Button) }
}
