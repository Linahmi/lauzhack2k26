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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ch.ripple.core.Briefing
import ch.ripple.core.Chf
import ch.ripple.core.Correction
import ch.ripple.core.DecisionDraft
import ch.ripple.core.DecisionGraph
import ch.ripple.core.DecisionInput
import ch.ripple.core.DecisionKind
import ch.ripple.core.DecisionNode
import ch.ripple.core.DecisionSession
import ch.ripple.core.DecisionSpec
import ch.ripple.core.Discovery
import ch.ripple.core.Way
import ch.ripple.core.findWays
import ch.ripple.core.ExampleDecisions
import ch.ripple.core.ExpenseReduction
import ch.ripple.core.GraphChange
import ch.ripple.core.Hypothesis
import ch.ripple.core.InputState
import ch.ripple.core.InputUnit
import ch.ripple.core.OPTION_LATER
import ch.ripple.core.PersonalCommitment
import ch.ripple.core.RejectionScope
import ch.ripple.core.SampleData
import ch.ripple.core.apply
import ch.ripple.core.brief
import ch.ripple.core.briefing
import ch.ripple.core.correct
import ch.ripple.core.explore
import ch.ripple.core.interpretDecision
import ch.ripple.core.offeredRepair
import ch.ripple.core.outsideHorizon
import ch.ripple.core.select
import ch.ripple.core.startGraph
import ch.ripple.core.toSession
import ch.ripple.core.tradeOffs
import ch.ripple.core.whereItTurns
import ch.ripple.core.wholeUnits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun RoutesApp(reduceMotion: Boolean = false, escPresses: Int = 0, plansDir: java.io.File? = null) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Rt.Ink, onPrimary = Rt.White, background = Rt.White, surface = Rt.White, onSurface = Rt.Ink, outline = Rt.Line)) {
        CompositionLocalProvider(LocalReduceMotion provides reduceMotion) { RoutesRoot(escPresses, plansDir) }
    }
}

/** Where the person is: asking, describing their situation, or exploring one evolving twin. */
private sealed interface Stage {
    data object Ask : Stage
    data class Describe(val draft: DecisionDraft, val from: DecisionSpec?) : Stage
    data class Explore(
        val session: DecisionSession, val spec: DecisionSpec?, val serial: Int, val graph: DecisionGraph? = null, val origin: String? = null,
        /** Saved-plan identity; null for the Alex example, which is not built from a spec. */
        val planId: String? = null, val createdAt: Long = 0,
    ) : Stage
    data object Import : Stage
}

@Composable
private fun RoutesRoot(escPresses: Int, plansDir: java.io.File?) {
    var stage by remember { mutableStateOf<Stage>(Stage.Ask) }
    var serial by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val reduce = LocalReduceMotion.current
    val store = remember { plansDir?.let(::LaptopPlans) ?: LaptopPlans() }
    var plans by remember { mutableStateOf(store.load()) }
    fun newId() = "laptop-" + System.currentTimeMillis()
    fun open(spec: DecisionSpec) = runCatching { spec.toSession() }
        .onSuccess { serial++; error = null; stage = Stage.Explore(it, spec, serial, planId = newId(), createdAt = System.currentTimeMillis()) }
        .onFailure { error = it.message }
    fun openPlan(d: ch.ripple.app.phone.RoutesDecision) = runCatching { d.graph }
        .onSuccess { g -> serial++; error = null; stage = Stage.Explore(d.spec.toSession(), d.spec, serial, g, planId = d.id, createdAt = d.createdAt) }
        .onFailure { error = it.message }
    // Every change is saved at once. Untouched fictional examples are not plans, so they aren't saved.
    fun keep(s: Stage.Explore, g: DecisionGraph) {
        val spec = s.spec ?: return
        val id = s.planId ?: return
        if (spec.fictional && g.nodes.size == 1 && plans.none { it.id == id }) return
        val plan = planOf(id, spec, s.createdAt, g, plans.firstOrNull { it.id == id })
        plans = plans.filterNot { it.id == id } + plan
        runCatching { store.save(plans) }.onFailure { error = "your plan couldn't be saved: ${it.message}" }
    }
    fun forget(id: String) {
        plans = plans.filterNot { it.id == id }
        runCatching { store.save(plans) }.onFailure { error = "the change couldn't be saved: ${it.message}" }
    }
    LaunchedEffect(escPresses) { if (escPresses > 0 && (stage is Stage.Describe || stage == Stage.Import)) stage = Stage.Ask }

    val fictional = (stage as? Stage.Explore)?.session?.profile?.fictional
    Box(Modifier.fillMaxSize().background(Rt.White)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TopBar((stage as? Stage.Explore)?.session?.profile?.name, fictional == true) { stage = Stage.Ask }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val side = if (maxWidth > 1180.dp) 120.dp else 48.dp
                Column(Modifier.padding(start = side, end = 48.dp, top = 8.dp, bottom = 40.dp).widthIn(max = 1040.dp)) {
                    error?.let { Text("Routes couldn't do that: $it", style = RtType.Body, modifier = Modifier.padding(bottom = 16.dp)) }
                    AnimatedContent(
                        targetState = stage,
                        contentKey = { when (it) { Stage.Ask -> "ask"; Stage.Import -> "import"; is Stage.Describe -> "describe"; is Stage.Explore -> "explore-${it.serial}" } },
                        transitionSpec = { if (reduce) EnterTransition.None togetherWith ExitTransition.None else fadeIn(tween(300, 90)) togetherWith fadeOut(tween(150)) },
                    ) { s ->
                        when (s) {
                            Stage.Ask -> AskView(
                                onAsk = { q -> error = null; stage = Stage.Describe(interpretDecision(q), null) },
                                onAlex = { serial++; stage = Stage.Explore(SampleData.alexEvolvingSession, null, serial) },
                                onSam = { open(ExampleDecisions.samStudyAbroad) },
                                onNoor = { open(ExampleDecisions.noorFewerHours) },
                                onImport = { error = null; stage = Stage.Import },
                                plans = plans.sortedByDescending { p -> p.steps.maxOfOrNull { it.at } ?: p.createdAt },
                                plansProblem = store.problem,
                                onPlan = ::openPlan, onForget = ::forget,
                            )
                            Stage.Import -> ImportView(onBack = { stage = Stage.Ask }) { d ->
                                serial++
                                val versions = d.graph.nodes.size
                                stage = Stage.Explore(d.spec.toSession(), d.spec, serial, d.graph,
                                    "Opened from your phone: $versions version${if (versions == 1) "" else "s"}, recomputed by the engine on this laptop. Changes here stay on this laptop; nothing syncs back.",
                                    planId = d.id, createdAt = d.createdAt)
                            }
                            is Stage.Describe -> DescribeView(s.draft, s.from, onBack = { stage = Stage.Ask }, onReady = ::open)
                            is Stage.Explore -> TwinView(s.session, s.spec, escPresses,
                                onEdit = s.spec?.let { spec -> { stage = Stage.Describe(draftOf(spec), spec) } }, initial = s.graph, origin = s.origin,
                                onChanged = { g -> keep(s, g) })
                        }
                    }
                }
            }
            Spacer(Modifier.height(56.dp))
        }
        Text(
            "Independent prototype for the UBS AI Financial Twin challenge. Not made or endorsed by UBS. " +
                (if (fictional == false) "Your plans are saved only on this computer" else "Fictional examples") + "; not a forecast or financial advice.",
            style = RtType.Small,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Rt.White).padding(start = 40.dp, end = 40.dp, top = 10.dp, bottom = 18.dp),
        )
    }
}

@Composable
private fun TopBar(name: String?, fictional: Boolean, onHome: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.focusRing().clickable(role = Role.Button, onClickLabel = "Start again", onClick = onHome).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            RoutesMark()
            Spacer(Modifier.width(9.dp))
            Text("Routes", style = RtType.Body.copy(fontWeight = FontWeight.Bold))
        }
        Spacer(Modifier.weight(1f))
        if (name != null) {
            Text(if (fictional) "$name · fictional example" else name, style = RtType.Small)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(32.dp).background(Rt.Chalk, Rt.Radius), contentAlignment = Alignment.Center) { Text(name.take(1), style = RtType.Label) }
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

/* ============================== The twin ============================== */

/** What the last confirmed change did to the tightest month, and which version it replaced. */
private data class ChangeNote(val because: String, val beforeMonth: Int, val before: Chf, val afterMonth: Int, val after: Chf, val previousId: String)

/** A value being tried. inputId null = the cushion. Never stored in the graph. */
private data class Trial(val inputId: String?, val value: Chf) {
    fun applyTo(s: DecisionSession): DecisionSession =
        if (inputId == null) s.apply(Correction.SetMinCashBuffer(value)) else s.apply(Hypothesis.WhatIfInput(inputId, value))
}

/** Everything drawn for one node (and an optional trial), computed off the UI thread. */
private data class View(
    val nodeId: String, val trial: Trial?, val session: DecisionSession, val briefing: Briefing, val key: KeyMoment,
    val compareLabel: String?, val compare: KeyMoment?, val problem: String? = null,
)

private fun computeView(nodeId: String, base: DecisionSession, trial: Trial?): View {
    val s = trial?.applyTo(base) ?: base
    val b = brief(s)
    val compareId: String? = if (s.activeOptionId == null) s.options.firstOrNull { it.kind == DecisionKind.STAY_CURRENT }?.id else null
    val hasCompare = s.activeOptionId != null || compareId != null
    val compare = if (hasCompare) keyMoment(briefFor(s, compareId)) else null
    val label = if (!hasCompare) null else plain(compareId?.let { id -> s.options.first { it.id == id }.labelKey } ?: s.decisionLabelKey)
    return View(nodeId, trial, s, b, keyMoment(b), label, compare)
}

/** Where an open figure editor sits: under the "worth trying" line, inside a month, or in the full list. */
/** What the one investigation area under the route shows. Exactly one at a time. */
private sealed interface Focus {
    /** The default: a tested way to keep the cushion (index into the discovery), or the route being compared. */
    data class Way(val index: Int = 0) : Focus
    data object Ways : Focus
    data class Month(val month: Int) : Focus
    /** A figure (input id, or [CUSHION]); [from] is where it was opened, for the way back. */
    data class Figure(val id: String, val from: Focus? = null) : Focus
    data class Sums(val month: Int) : Focus
    data object Figures : Focus
    data object Versions : Focus
    data object Chart : Focus
}

private val DEFAULT_FOCUS: Focus = Focus.Way(0)

@Composable
private fun TwinView(
    start: DecisionSession, spec: DecisionSpec?, escPresses: Int, onEdit: (() -> Unit)?, initial: DecisionGraph? = null, origin: String? = null,
    onChanged: (DecisionGraph) -> Unit = {},
) {
    var graph by remember { mutableStateOf(initial ?: startGraph(start)) }
    LaunchedEffect(graph) { onChanged(graph) }
    // An imported history may end on a what-if: the plan is its nearest confirmed ancestor.
    var confirmedId by remember {
        mutableStateOf(generateSequence(graph.active) { n -> n.parentId?.let(graph::node) }.first { !it.hypothetical }.id)
    }
    var focus by remember { mutableStateOf(DEFAULT_FOCUS) }
    var note by remember { mutableStateOf<ChangeNote?>(null) }
    var before by remember { mutableStateOf<Pair<String, List<Chf>>?>(null) }
    var trial by remember { mutableStateOf<Trial?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val node = graph.active
    val fallback = remember(graph, node.id) { computeView(node.id, node.session, null) }
    var view by remember { mutableStateOf(fallback) }
    // Live what-if: debounced while dragging, computed by the engine on a worker thread.
    LaunchedEffect(node.id, trial) {
        if (trial != null) delay(60)
        val t = trial
        val r = withContext(Dispatchers.Default) { runCatching { computeView(node.id, node.session, t) } }
        view = r.getOrElse { e -> (if (view.nodeId == node.id) view else fallback).copy(problem = e.message ?: "Routes can't use that value.") }
    }
    val shown = if (view.nodeId == node.id) view else fallback

    fun attempt(block: () -> Unit) {
        error = null
        runCatching(block).onFailure { error = it.message ?: "Routes couldn't do that." }
    }
    fun settle() { trial = null; focus = DEFAULT_FOCUS }
    fun confirm(c: Correction, because: String) = attempt {
        val beforeKey = keyMoment(graph.briefing(confirmedId))
        val previous = confirmedId
        val u = graph.correct(c, fromId = confirmedId)
        val after = keyMoment(u.graph.briefing(u.node.id))
        graph = u.graph.select(u.node.id)
        confirmedId = u.node.id
        note = ChangeNote(because, beforeKey.month, beforeKey.moneyLeft, after.month, after.moneyLeft, previous)
        before = u.node.id to beforeKey.route
        settle()
    }
    fun explore(h: Hypothesis) = attempt {
        val u = graph.explore(h, fromId = confirmedId)
        graph = u.graph.select(u.node.id)
        settle()
    }
    fun choosePath(optionId: String?) = attempt {
        graph = if (optionId == null) graph.select(confirmedId) else {
            val existing = graph.children(confirmedId).lastOrNull {
                it.session.activeOptionId == optionId && it.session.plannedCut == null && it.session.hypotheticalValues.isEmpty()
            }
            existing?.let { graph.select(it.id) } ?: graph.explore(Hypothesis.TryOption(optionId), fromId = confirmedId).let { it.graph.select(it.node.id) }
        }
        settle()
    }
    fun view(id: String) { graph = graph.select(id); settle() }
    // Choosing the item already in focus returns to the default; a new focus ends any try.
    fun toggle(f: Focus) { focus = if (focus == f) DEFAULT_FOCUS else f; trial = null }

    LaunchedEffect(escPresses) { if (escPresses > 0) { focus = (focus as? Focus.Figure)?.from ?: DEFAULT_FOCUS; trial = null } }

    val sources = spec?.changes?.mapNotNull { c -> c.source?.let { c.id to it } }?.toMap().orEmpty()
    Column {
        error?.let { Text("Routes couldn't do that: $it", style = RtType.Body, modifier = Modifier.padding(bottom = 16.dp)) }
        origin?.let { Text(it, style = RtType.Small, modifier = Modifier.padding(bottom = 10.dp)) }
        Workspace(
            graph, node, shown, confirmedId, note, trial, spec, sources,
            beforeRoute = before?.takeIf { it.first == node.id && trial == null }?.second,
            focus = focus, onFocus = { focus = it; trial = null }, onToggle = ::toggle,
            onPath = ::choosePath, onView = ::view, onEdit = onEdit,
            onTrial = { trial = it }, onConfirm = ::confirm, onExplore = ::explore,
        )
    }
}

/* ============================== One workspace ============================== */

@Composable
private fun Workspace(
    graph: DecisionGraph, node: DecisionNode, shown: View, confirmedId: String, note: ChangeNote?, trial: Trial?,
    spec: DecisionSpec?, sources: Map<String, String>, beforeRoute: List<Chf>?,
    focus: Focus, onFocus: (Focus) -> Unit, onToggle: (Focus) -> Unit,
    onPath: (String?) -> Unit, onView: (String) -> Unit, onEdit: (() -> Unit)?,
    onTrial: (Trial?) -> Unit, onConfirm: (Correction, String) -> Unit, onExplore: (Hypothesis) -> Unit,
) {
    val session = node.session
    val decision = node.summary.decision
    val key = shown.key
    val nodeBriefing = remember(graph, node.id) { graph.briefing(node.id) }
    val viewingAlternative = session.activeOptionId != null && session.plannedCut == null
    val reduce = LocalReduceMotion.current
    var discovery by remember(node.id) { mutableStateOf<Discovery?>(null) }
    LaunchedEffect(node.id) { discovery = withContext(Dispatchers.Default) { runCatching { findWays(session, nodeBriefing) }.getOrNull() } }
    var monthsSeen by remember { mutableStateOf(false) }
    if (focus is Focus.Month) monthsSeen = true

    // Never show a new value next to an old result: while the engine recomputes, the result dims.
    val pending = trial != null && shown.trial != trial
    val fade by animateFloatAsState(if (pending) 0.35f else 1f, if (reduce) snap() else tween(160))
    val openCushion = { onToggle(Focus.Figure(CUSHION)) }
    fun monthOf(f: Focus?): Int? = when (f) { is Focus.Month -> f.month; is Focus.Sums -> f.month; is Focus.Figure -> monthOf(f.from); else -> null }
    val highlighted = monthOf(focus)

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        StateBanner(node, confirmedId, onView)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("You asked: “${decision.intention}”", style = RtType.Label)
            Text(plain(decision.labelKey), style = RtType.Title)
        }
        Text(
            finding(key, shown.session, shown.briefing, onMonth = { onToggle(Focus.Month(key.month)) }, onCushion = openCushion),
            style = RtType.Finding, modifier = Modifier.alpha(fade),
        )
        if (note != null && node.id == confirmedId && trial == null) ChangeLine(note, onView)
        spec?.let { sp -> sp.outsideHorizon(shown.briefing.evidence.worstCaseInputs, if (session.activeOptionId == OPTION_LATER) sp.laterBy else 0) }
            ?.takeIf { it.isNotEmpty() }?.let { out ->
                Text("Starting this late, ${out.joinToString(" and ") { it.label.replaceFirstChar(Char::lowercase) }} would fall after the ${session.profile.horizonMonths} months shown.", style = RtType.Body.copy(color = Rt.Bronze))
            }

        // The route: the navigation surface. It stays in place whatever is being investigated.
        Column(Modifier.padding(top = 4.dp).alpha(fade)) {
            RouteStrip(
                points = key.route,
                // Reference marks: your plan while comparing a route or trying a value; else the version before your last change.
                ghost = when { viewingAlternative -> shown.compare?.route; trial != null -> keyMoment(nodeBriefing).route; else -> beforeRoute },
                cushion = key.cushion,
                keyMonth = key.month,
                openMonth = highlighted,
                below = key.monthsBelowCushion,
                // The highlighted month closes whatever was opened from it; another month opens instead.
                onMonth = { m -> if (m == highlighted) onFocus(DEFAULT_FOCUS) else onFocus(Focus.Month(m)) },
                onCushion = openCushion,
            )
            Text(
                when {
                    trial != null -> "Hollow marks: your plan before this try."
                    viewingAlternative -> "Hollow marks: your plan."
                    beforeRoute != null -> "Hollow marks: before your last change."
                    !monthsSeen -> "Click any month to see what happened in it."
                    else -> ""
                } + (if (trial != null || viewingAlternative || beforeRoute != null || !monthsSeen) " " else "") +
                    "Tested within the ${session.profile.horizonMonths} months shown and the ranges you gave; not a forecast.",
                style = RtType.Small, modifier = Modifier.padding(top = 4.dp),
            )
        }

        // The one investigation area.
        Column(Modifier.fillMaxWidth().background(Rt.Chalk, Rt.CardRadius).padding(horizontal = 24.dp, vertical = 20.dp)) {
            AnimatedContent(
                targetState = focus,
                contentKey = { it::class.simpleName + ((it as? Focus.Month)?.month ?: (it as? Focus.Sums)?.month ?: (it as? Focus.Figure)?.id ?: (it as? Focus.Way)?.index ?: "") },
                transitionSpec = { if (reduce) EnterTransition.None togetherWith ExitTransition.None else fadeIn(tween(200, 60)) togetherWith fadeOut(tween(100)) },
            ) { f ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (f) {
                        is Focus.Way -> WayFocus(f.index, discovery, node, session, nodeBriefing, shown, trial, viewingAlternative, onFocus, onPath, onTrial, onConfirm, onExplore)
                        Focus.Ways -> discovery?.let { d -> WaysFocus(d, session, onFocus) }
                        is Focus.Month -> MonthFocus(node, shown, f.month, confirmedId, sources, trial != null, onFocus, onToggle, onConfirm, onExplore)
                        is Focus.Figure -> FigureFocus(f, node, session, nodeBriefing, shown, trial, sources, onFocus, onTrial, onConfirm, onExplore)
                        is Focus.Sums -> SumsFocus(f.month, shown, sources, onFocus)
                        Focus.Figures -> FiguresFocus(session, nodeBriefing, sources, spec, onFocus)
                        Focus.Versions -> Versions(graph, node, confirmedId, onView)
                        Focus.Chart -> {
                            Header("Each month, in detail")
                            RouteChart(
                                selected = RouteLine(plain(session.activeLabelKey), listOf(session.profile.openingCash) + key.route),
                                compare = shown.compare?.let { RouteLine(shown.compareLabel ?: "", listOf(session.profile.openingCash) + it.route) },
                                before = beforeRoute?.let { RouteLine("Before", listOf(session.profile.openingCash) + it) },
                                cushion = key.cushion, keyMonth = key.month, belowMonths = key.monthsBelowCushion,
                                onMonth = { onFocus(Focus.Month(it)) }, marker = Modifier,
                            )
                        }
                    }
                }
            }
        }

        // Quiet ways into the rest; each toggles the same investigation area.
        Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            FooterLink("Each month in detail", focus == Focus.Chart) { onToggle(Focus.Chart) }
            FooterLink("All figures (${session.inputs.size + 1})", focus == Focus.Figures) { onToggle(Focus.Figures) }
            if (graph.path(confirmedId).size > 1) FooterLink("Earlier versions", focus == Focus.Versions) { onToggle(Focus.Versions) }
            onEdit?.let { LinkText("Start a new plan from these figures", it) }
        }
    }
}

@Composable
private fun FooterLink(text: String, on: Boolean, onClick: () -> Unit) =
    Text(
        text,
        style = RtType.Body.copy(color = if (on) Rt.Ink else Rt.LinkRed, textDecoration = TextDecoration.Underline, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
        modifier = Modifier.focusRing().clickable(role = Role.Tab, onClickLabel = if (on) "Close" else "Open", onClick = onClick).padding(2.dp),
    )

@Composable
private fun Header(title: String, back: Pair<String, () -> Unit>? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        back?.let { (label, go) -> LinkText("‹ $label", go) }
        Text(title, style = RtType.Label)
    }
}

/* ============================== Investigations ============================== */

@Composable
private fun WayFocus(
    index: Int, d: Discovery?, node: DecisionNode, s: DecisionSession, b: Briefing, shown: View, trial: Trial?, viewingAlternative: Boolean,
    onFocus: (Focus) -> Unit, onPath: (String?) -> Unit, onTrial: (Trial?) -> Unit,
    onConfirm: (Correction, String) -> Unit, onExplore: (Hypothesis) -> Unit,
) {
    val horizon = s.profile.horizonMonths
    when {
        viewingAlternative -> {
            Header("Comparing a route")
            shown.compare?.let { c ->
                Text("This route's lowest month is ${monthName(shown.key.month)} (${shown.key.moneyLeft.money()}); your plan's is ${monthName(c.month)} (${c.moneyLeft.money()}).", style = RtType.Body)
            }
        }
        d == null -> Text("Routes is testing what could change this…", style = RtType.Body.copy(color = Rt.Ink2))
        d.noneWork -> {
            Header("Find a way")
            Text("No tested option keeps your cushion within the $horizon months shown.", style = RtType.Finding.copy(fontSize = RtType.Finding.fontSize * 0.8f))
            Text("Routes tried: " + d.ways.joinToString("; ") { wayQuestion(it, s).trimEnd('?').replaceFirstChar(Char::lowercase) } + ". " +
                (if (d.trimRuledOutByLimits) "Spending less could work financially, but not within the limits you set. " else "") +
                "That covers this decision's figures and routes, not every possible future.", style = RtType.Body.copy(color = Rt.Ink2))
        }
        d.ways.isEmpty() -> {
            Header("What could change this")
            Text("Nothing in this decision's tested ranges changes the answer.", style = RtType.Body)
        }
        else -> {
            val w = d.ways[index.coerceIn(d.ways.indices)]
            Header(if (d.keepsCushion) "What could change this" else "Find a way")
            Text(wayQuestion(w, s), style = RtType.Finding.copy(fontSize = RtType.Finding.fontSize * 0.8f))
            when (w) {
                is Way.Amount -> LeverEditor(s.inputs.first { it.id == w.inputId }, s, b, shown, trial, !node.hypothetical, onTrial, onConfirm, onExplore, turn(w.impact))
                is Way.Timing -> LeverEditor(s.inputs.first { it.id == w.inputId }, s, b, shown, trial, !node.hypothetical, onTrial, onConfirm, onExplore, turn(w.impact))
                is Way.Route -> {
                    Text(linked(routeVerdict(w, horizon), w.lowest.money()) { onPath(w.optionId) }, style = RtType.Body)
                    PrimaryButton("Compare this route") { onPath(w.optionId) }
                }
                is Way.Trim -> {
                    Text("Yes: spending ${w.cut.monthly.money()} less each month from ${monthName(w.cut.startMonth)} keeps your cushion in every tested case. It never touches spending you said can't be cut.", style = RtType.Body)
                    if (!node.hypothetical) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton("Try it") { onExplore(Hypothesis.ApplyCut(w.cut)) }
                        SecondaryButton("Not for me") { onConfirm(Correction.RejectRepair(w.cut, RejectionScope.THIS_AMOUNT_OR_MORE), "you said no to spending ${w.cut.monthly.money()} or more less") }
                    }
                }
            }
            if (d.ways.size > 1) LinkText("Other things Routes tested (${d.ways.size - 1})") { onFocus(Focus.Ways) }
        }
    }
}

@Composable
private fun WaysFocus(d: Discovery, s: DecisionSession, onFocus: (Focus) -> Unit) {
    Header("What Routes tested", "Back" to { onFocus(DEFAULT_FOCUS) })
    d.ways.forEachIndexed { i, o ->
        Row(
            Modifier.fillMaxWidth().focusRing().clickable(role = Role.Button, onClickLabel = "Explore this") { onFocus(Focus.Way(i)) }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(wayQuestion(o, s), style = RtType.Body.copy(textDecoration = TextDecoration.Underline), modifier = Modifier.weight(1f))
            Text(if (o.works) "a tested way" else "doesn't keep it", style = RtType.Small.copy(color = if (o.works) Rt.Ink2 else Rt.Bronze))
        }
    }
}

@Composable
private fun MonthFocus(
    node: DecisionNode, shown: View, month: Int, confirmedId: String, sources: Map<String, String>, trying: Boolean,
    onFocus: (Focus) -> Unit, onToggle: (Focus) -> Unit, onConfirm: (Correction, String) -> Unit, onExplore: (Hypothesis) -> Unit,
) {
    val s = shown.session
    val key = shown.key
    val record = key.months[month]
    val diff = record.closingCash - key.cushion
    Header(monthName(month))
    // The amount opens its arithmetic; the cushion opens the cushion. Nothing here repeats the finding's sentence.
    Text(
        buildAnnotatedString {
            append("Left at the end: ")
            link("sums", { onFocus(Focus.Sums(month)) }, record.closingCash.money())
            append(", ${(if (diff.isNegative) -diff else diff).money()} ${if (diff.isNegative) "below" else "above"} ")
            link("cushion", { onFocus(Focus.Figure(CUSHION, Focus.Month(month))) }, "your cushion")
            append(".")
        },
        style = RtType.Lead.copy(color = if (record.closingCash < key.cushion) Rt.Bronze else Rt.Ink),
    )
    Text("What led here", style = RtType.Label, modifier = Modifier.padding(top = 4.dp))
    WhatLedHere(node, s, shown.briefing, month, confirmedId, sources, trying, { id -> onFocus(Focus.Figure(id, Focus.Month(month))) }, onConfirm, onExplore)
}

@Composable
private fun FigureFocus(
    f: Focus.Figure, node: DecisionNode, s: DecisionSession, b: Briefing, shown: View, trial: Trial?, sources: Map<String, String>,
    onFocus: (Focus) -> Unit, onTrial: (Trial?) -> Unit, onConfirm: (Correction, String) -> Unit, onExplore: (Hypothesis) -> Unit,
) {
    val back = f.from?.let { from -> focusTitle(from) to { onFocus(from) } }
    if (f.id == CUSHION) {
        Header("Your cushion: ${s.effectiveFloor.money()}", back)
        CushionEditor(s, shown, trial, !node.hypothetical, onTrial, onConfirm)
    } else {
        val i = s.inputs.first { it.id == f.id }
        val (value, tag) = stateWords(i, s, b, sources[i.id])
        Header("${plain(i.questionKey)}: $value, $tag", back)
        LeverEditor(i, s, b, shown, trial, !node.hypothetical, onTrial, onConfirm, onExplore)
    }
}

private fun focusTitle(f: Focus): String = when (f) {
    is Focus.Month -> monthName(f.month)
    is Focus.Sums -> "How ${monthName(f.month)} adds up"
    Focus.Figures -> "All figures"
    else -> "Back"
}

@Composable
private fun FiguresFocus(s: DecisionSession, b: Briefing, sources: Map<String, String>, spec: DecisionSpec?, onFocus: (Focus) -> Unit) {
    Header("All figures")
    (s.inputs.map { it.id } + CUSHION).forEach { id ->
        val (name, value, tag) = if (id == CUSHION) Triple("Your cushion", s.effectiveFloor.money(), if (s.constraints.minCashBuffer != null) "you chose it" else "Routes' starting point")
        else s.inputs.first { it.id == id }.let { i -> stateWords(i, s, b, sources[id]).let { (v, t) -> Triple(plain(i.questionKey), v, t) } }
        Row(
            Modifier.fillMaxWidth().focusRing().clickable(role = Role.Button, onClickLabel = "Try other values") { onFocus(Focus.Figure(id, Focus.Figures)) }.padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, style = RtType.Body.copy(textDecoration = TextDecoration.Underline), modifier = Modifier.weight(1f))
            Text(value, style = RtType.Body.copy(fontWeight = FontWeight.SemiBold))
            Text("  $tag", style = RtType.Small, modifier = Modifier.width(250.dp))
        }
    }
    spec?.situation?.takeIf { it.cannotCut > Chf.ZERO }?.let { sit ->
        Text("${sit.cannotCut.money()} of your monthly spending can't be cut" + (sit.cannotCutLabel.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
            ". Routes never suggests trimming it.", style = RtType.Small)
    }
}

@Composable
private fun SumsFocus(month: Int, shown: View, sources: Map<String, String>, onFocus: (Focus) -> Unit) {
    val r = shown.key.months[month]
    val s = shown.session
    val b = shown.briefing
    Header("How ${monthName(month)} adds up", monthName(month) to { onFocus(Focus.Month(month)) })
    // A line backed by one of the person's figures opens that figure (in this same area).
    fun figure(id: String): (() -> Unit)? = if (s.inputs.any { it.id == id }) { { onFocus(Focus.Figure(id, Focus.Sums(month))) } } else null
    MathLine("Money at the start of ${monthName(month)}", r.openingCash.money())
    MathLine("Pay", "+ ${r.income.money()}", figure(DecisionSpec.INCOME))
    MathLine("Living costs" + if (r.commitments.rappen > 0) " (incl. ${r.commitments.money()} that can't be cut)" else "", "− ${r.expenses.money()}", figure(DecisionSpec.SPENDING))
    if (r.oneOffCosts.rappen > 0) MathLine("One-off costs", "− ${r.oneOffCosts.money()}")
    if (r.transfersToRestricted.rappen > 0) MathLine("Set aside (not spent, you get it back)", "− ${r.transfersToRestricted.money()}")
    Box(Modifier.widthIn(max = 560.dp).fillMaxWidth().height(1.5.dp).background(Rt.Ink))
    MathLine("Left at the end", r.closingCash.money(), bold = true)
    val money = s.inputs.filter { it.unit == InputUnit.CHF }
    if (money.isNotEmpty()) Text(
        "Where the figures come from: " + money.joinToString("; ") { i -> val (v, tag) = stateWords(i, s, b, sources[i.id]); "${plain(i.questionKey)} $v, $tag" } + ".",
        style = RtType.Small, modifier = Modifier.padding(top = 6.dp),
    )
    HowSure(b, shown.key, s)
    Text("Cash only: no tax, pension, debt or investments. Month 1 is shown as January. Same inputs give the same result every time.", style = RtType.Small)
}

@Composable
private fun MathLine(label: String, value: String, onClick: (() -> Unit)? = null, bold: Boolean = false) {
    val st = RtType.Body.copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
    Row(Modifier.widthIn(max = 560.dp).fillMaxWidth().then(if (onClick != null) Modifier.focusRing().clickable(role = Role.Button, onClickLabel = "Try other values", onClick = onClick) else Modifier)) {
        Text(label, style = st, modifier = Modifier.weight(1f))
        Text(value, style = st.copy(textDecoration = if (onClick != null) TextDecoration.Underline else null))
    }
}

/** One cause per row; a cause backed by a figure is a link to that figure. No controls are nested here. */
@Composable
private fun WhatLedHere(
    node: DecisionNode, session: DecisionSession, b: Briefing, month: Int, confirmedId: String, sources: Map<String, String>, trying: Boolean,
    onFigure: (String) -> Unit, onConfirm: (Correction, String) -> Unit, onExplore: (Hypothesis) -> Unit,
) {
    val list = remember(session, b, month) { causes(session, b, month) }
    var commitmentForm by remember { mutableStateOf(false) }
    val linkedIds = list.mapNotNull { when (it) { is Cause.Change -> it.inputId; is Cause.OneOff -> it.inputId; is Cause.SetAside -> it.inputId; else -> null } }.toSet()
    val shownCauses = list.filter { it !is Cause.Input || (it.input.unit == InputUnit.CHF && it.input.id !in linkedIds && it.input.id !in setOf(DecisionSpec.INCOME, DecisionSpec.SPENDING)) }
    val tagFor = { id: String -> session.inputs.firstOrNull { it.id == id }?.let { stateWords(it, session, b, sources[id]).second } ?: "" }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        shownCauses.forEach { c ->
            when (c) {
                is Cause.LowerPay -> CauseLine("Your pay could be ${c.reduction.money()} lower in ${monthList(c.months)}", "a tested case", null, onFigure)
                is Cause.SetAside -> CauseLine("${c.label.ifBlank { "Money set aside" }}: ${c.amount.money()} in ${monthName(c.month)}, still yours but not spendable", c.inputId?.let(tagFor) ?: "", c.inputId, onFigure)
                is Cause.OneOff -> CauseLine("${c.label.ifBlank { "One-off cost" }}: ${c.amount.money()} in ${monthName(c.month)}", c.inputId?.let(tagFor) ?: "", c.inputId, onFigure)
                is Cause.Commitment -> CauseLine("${c.label}: ${c.monthly.money()} every month", "you must keep paying", null, onFigure)
                is Cause.Change -> CauseLine(changeWords(c), c.inputId?.let(tagFor) ?: "", c.inputId, onFigure)
                is Cause.Input -> stateWords(c.input, session, b, sources[c.input.id]).let { (v, t) -> CauseLine("${plain(c.input.questionKey)}: $v", t, c.input.id, onFigure) }
            }
        }
    }
    // Adjustments: offered only on the confirmed plan, never as an instruction.
    val offered = b.offeredRepair
    if (offered != null && !node.hypothetical && !trying) Detour(offered, onTry = { onExplore(Hypothesis.ApplyCut(offered)) },
        onRefuse = { onConfirm(Correction.RejectRepair(offered, RejectionScope.THIS_AMOUNT_OR_MORE), "you said no to spending ${offered.monthly.money()} or more less") })
    if (node.summary.status == ch.ripple.core.NodeStatus.ONLY_UNACCEPTABLE_REPAIRS)
        Text("Routes found no other small change that fits what you said. Other routes may.", style = RtType.Body.copy(color = Rt.Ink2))
    session.constraints.rejections.forEach { r ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("You said no to spending ${r.action.monthly.money()} or more less each month. Routes won't suggest it again.", style = RtType.Body, modifier = Modifier.weight(1f))
            if (!node.hypothetical && node.id == confirmedId) LinkText("Undo") { onConfirm(Correction.RemoveRejection(r.id), "you took back your “not for me”") }
        }
    }
    if (!node.hypothetical && node.id == confirmedId) {
        LinkText("Something you must keep paying every month?") { commitmentForm = !commitmentForm }
        if (commitmentForm) CommitmentForm { label, amount ->
            val id = "commitment-" + (session.constraints.commitments.size + 1)
            onConfirm(Correction.SetCommitment(PersonalCommitment(id, label, amount)), "you added $label")
            commitmentForm = false
        }
    }
}

@Composable
private fun CauseLine(text: String, tag: String, figureId: String?, onFigure: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().then(if (figureId != null) Modifier.focusRing().clickable(role = Role.Button, onClickLabel = "Try other values") { onFigure(figureId) } else Modifier)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = RtType.Body.copy(textDecoration = if (figureId != null) TextDecoration.Underline else null), modifier = Modifier.weight(1f))
        if (tag.isNotEmpty()) Text(tag, style = RtType.Small)
    }
}

@Composable
private fun Detour(r: ExpenseReduction, onTry: () -> Unit, onRefuse: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
private fun HowSure(b: Briefing, key: KeyMoment, s: DecisionSession) {
    val t = tested(b)
    val uncertain = s.inputs.any { s.state(it.id) !is InputState.Exact && it.id !in s.hypotheticalValues && it.low != it.high }
    if (t.cases <= 1 && t.inputCombinations <= 1) return
    Text(
        buildString {
            if (uncertain && t.inputCombinations > 1) append("Routes tried ${t.inputCombinations} combinations of what you don't know yet and kept the hardest. ")
            if (t.cases > 1) append("It tried ${t.cases} versions of the months with up to ${s.stress.maxLowIncomeMonths} months of lower pay; ${t.dipping} dip below the cushion. ")
            append("This counts tested cases; it is not a chance of it happening.")
        },
        style = RtType.Small,
    )
}

private fun wayQuestion(w: Way, s: DecisionSession): String {
    fun name(id: String) = plain(s.inputs.first { it.id == id }.questionKey).replaceFirstChar(Char::lowercase)
    return when (w) {
        is Way.Amount -> when (turn(w.impact)) {
            is Turn.From -> "How much would ${name(w.inputId)} need to be?"
            is Turn.UpTo -> "How high could ${name(w.inputId)} be?"
            Turn.AllKeep -> "Does ${name(w.inputId)} change this?"
            Turn.NoneKeep -> "Would a different ${name(w.inputId)} be enough?"
            Turn.NoSingleBoundary -> "How does ${name(w.inputId)} change this?"
        }
        is Way.Timing -> when {
            w.unit == InputUnit.MONTH_INDEX && turn(w.impact) is Turn.From -> "Would starting later change this?"
            w.unit == InputUnit.MONTH_INDEX -> "How late could it start?"
            turn(w.impact) is Turn.UpTo -> "How long could it last?"
            else -> "How long would it need to last?"
        }
        is Way.Route -> "What about ${plain(w.labelKey).replaceFirstChar(Char::lowercase)}?"
        is Way.Trim -> "Could spending a little less be enough?"
    }
}

private fun routeVerdict(w: Way.Route, horizon: Int) =
    if (w.works) "Yes: it keeps your cushion in every tested case within the $horizon months shown. Its lowest month: ${w.lowest.money()}."
    else "No: it also goes below your cushion. Its lowest month: ${w.lowest.money()}."

private const val CUSHION = "\u0000cushion"

private fun trialWords(t: Trial, s: DecisionSession): String {
    if (t.inputId == null) return "a cushion of ${t.value.money()}"
    val i = s.inputs.first { it.id == t.inputId }
    return when (i.unit) {
        InputUnit.MONTH_INDEX -> "starting in ${valueWords(i, t.value)}"
        InputUnit.MONTH_COUNT -> "lasting ${valueWords(i, t.value)}"
        InputUnit.CHF -> "${plain(i.questionKey).replaceFirstChar(Char::lowercase)} at ${valueWords(i, t.value)}"
    }
}

/** A value in the input's own unit. */
internal fun valueWords(i: DecisionInput, v: Chf): String = when (i.unit) {
    InputUnit.CHF -> v.money()
    InputUnit.MONTH_INDEX -> monthName(v.wholeUnits)
    InputUnit.MONTH_COUNT -> "${v.wholeUnits} month${if (v.wholeUnits == 1) "" else "s"}"
}

private fun stateWords(i: DecisionInput, s: DecisionSession, b: Briefing, source: String? = null): Pair<String, String> {
    s.hypotheticalValues[i.id]?.let { return valueWords(i, it) to "what-if" }
    return when (val st = s.state(i.id)) {
        is InputState.Exact -> valueWords(i, st.value) to "you said"
        is InputState.Range -> if (st.low == st.high) "about ${valueWords(i, st.low)}" to (source?.let { "from $it, not confirmed" } ?: "your estimate")
            else "${valueWords(i, st.low)} – ${valueWords(i, st.high)}" to "tested at ${b.evidence.worstCaseInputs[i.id]?.let { valueWords(i, it) }} (hardest)"
        InputState.Unknown -> "not known" to "tested ${valueWords(i, i.low)} – ${valueWords(i, i.high)}"
    }
}

/* ============================== Editors ============================== */

@Composable
private fun LeverEditor(
    i: DecisionInput, s: DecisionSession, b: Briefing, shown: View, trial: Trial?, editable: Boolean,
    onTrial: (Trial?) -> Unit, onConfirm: (Correction, String) -> Unit, onExplore: (Hypothesis) -> Unit,
    known: Turn? = null,
) {
    val name = plain(i.questionKey)
    // The boundary: from this briefing when the input is uncertain, else asked of the engine on demand.
    val fromBriefing = known ?: b.evidence.unknownImpacts.firstOrNull { it.name == i.id }?.let(::turn)
    var computed by remember(s, i.id) { mutableStateOf<Turn?>(null) }
    LaunchedEffect(s, i.id) {
        if (fromBriefing == null && computed == null) computed = withContext(Dispatchers.Default) { runCatching { s.whereItTurns(i.id)?.let(::turn) }.getOrNull() }
    }
    val t = fromBriefing ?: computed
    val lo = i.low.rappen / 100
    val hi = i.high.rappen / 100
    val step = (i.step.rappen / 100).coerceAtLeast(1)
    val current = (trial?.takeIf { it.inputId == i.id }?.value ?: s.hypotheticalValues[i.id] ?: (s.state(i.id) as? InputState.Exact)?.value
        ?: b.evidence.worstCaseInputs[i.id] ?: i.high).rappen / 100
    val tryingThis = trial?.inputId == i.id
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val boundary = (t as? Turn.UpTo)?.value ?: (t as? Turn.From)?.value
        Text(boundary?.let { v -> linked(turnWords(t, i, s), valueWords(i, v)) { onTrial(Trial(i.id, v)) } } ?: AnnotatedString(turnWords(t, i, s)),
            style = RtType.Body.copy(color = Rt.Ink2))
        BoundarySlider(lo, hi, step, current, t, "Try a value for $name") { v -> onTrial(Trial(i.id, Chf.francs(v))) }
        Row { Text(valueWords(i, i.low), style = RtType.Small, modifier = Modifier.weight(1f)); Text(valueWords(i, i.high), style = RtType.Small) }
        if (tryingThis) {
            val k = shown.key
            Text(
                shown.problem?.let { "Routes can't use that value: $it" }
                    ?: if (shown.trial != trial) "Working it out…"
                    // The finding above already states the month and amount; here only the answer to "does it work?".
                    else "At ${valueWords(i, Chf.francs(current))}: " + (if (k.belowCushion) "it goes below your cushion." else "it keeps your cushion."),
                style = RtType.Body.copy(fontWeight = FontWeight.SemiBold),
            )
        } else Text("Drag to try. The route above redraws; nothing is saved until you choose.", style = RtType.Small)
        if (editable && tryingThis) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            // A start date or duration is a plan you choose, not a fact you confirm.
            val plan = i.unit != InputUnit.CHF
            PrimaryButton(if (plan) "Plan it for ${trial?.value?.let { valueWords(i, it) }}" else "That's the real figure", enabled = shown.problem == null && shown.trial == trial) {
                trial?.value?.let { v -> onConfirm(Correction.AnswerInput(i.id, InputState.Exact(v)),
                    if (plan) "you planned ${name.replaceFirstChar(Char::lowercase)}: ${valueWords(i, v)}" else "you confirmed ${name.replaceFirstChar(Char::lowercase)} is ${valueWords(i, v)}") }
            }
            SecondaryButton("Save as a what-if") { trial?.takeIf { shown.problem == null }?.let { onExplore(Hypothesis.WhatIfInput(i.id, it.value)) } }
        } else if (!editable) Text("You're in a what-if. Go back to your plan to change real figures.", style = RtType.Small)
    }
}

/** The boundary in plain words; only what the engine computed. */
private fun turnWords(t: Turn?, i: DecisionInput, s: DecisionSession): String {
    val others = if (s.inputs.count { it.unit == InputUnit.CHF } > 1) " (other figures at their hardest tested values)" else ""
    val window = if (i.unit != InputUnit.CHF) " within the ${s.profile.horizonMonths} months shown; later months aren't included" else ""
    return when (t) {
        null -> "Working out where this turns…"
        is Turn.UpTo -> when (i.unit) {
            InputUnit.CHF -> "Up to ${t.value.money()}, every month stays at or above your cushion$others."
            InputUnit.MONTH_INDEX -> "Starting by ${monthName(t.value.wholeUnits)} keeps your cushion$window."
            InputUnit.MONTH_COUNT -> "Up to ${valueWords(i, t.value)} keeps every month at or above your cushion$others."
        }
        is Turn.From -> when (i.unit) {
            InputUnit.CHF -> "From ${t.value.money()} up, every month stays at or above your cushion$others."
            InputUnit.MONTH_INDEX -> "Starting in ${monthName(t.value.wholeUnits)} or later keeps your cushion$window."
            InputUnit.MONTH_COUNT -> "${valueWords(i, t.value)} or more keeps every month at or above your cushion$others."
        }
        Turn.AllKeep -> "Anywhere from ${valueWords(i, i.low)} to ${valueWords(i, i.high)}, every month stays at or above your cushion$others."
        Turn.NoneKeep -> "Nowhere from ${valueWords(i, i.low)} to ${valueWords(i, i.high)} does every month stay at or above your cushion$others."
        Turn.NoSingleBoundary -> "Some values keep your cushion and some don't; there's no single turning point."
    }
}

@Composable
private fun CushionEditor(s: DecisionSession, shown: View, trial: Trial?, editable: Boolean, onTrial: (Trial?) -> Unit, onConfirm: (Correction, String) -> Unit) {
    val current = s.effectiveFloor
    val tryingThis = trial != null && trial.inputId == null
    val hi = (maxOf(s.profile.openingCash.rappen, current.rappen * 2, 100_000) / 100 / 50 + 1) * 50
    val v = (trial?.takeIf { it.inputId == null }?.value ?: current).rappen / 100
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Your cushion is your own reference point, not a rule about what's safe.", style = RtType.Body.copy(color = Rt.Ink2))
        BoundarySlider(0, hi, 50, v, null, "Try another cushion") { onTrial(Trial(null, Chf.francs(it))) }
        if (tryingThis && shown.trial == trial) Text(
            "With ${Chf.francs(v).money()}: " + if (shown.key.monthsBelowCushion.isEmpty()) "no month goes below it." else "${monthList(shown.key.monthsBelowCushion)} go below it.",
            style = RtType.Body.copy(fontWeight = FontWeight.SemiBold),
        )
        if (editable && tryingThis) PrimaryButton("Use this cushion") { trial?.value?.let { onConfirm(Correction.SetMinCashBuffer(it), "you set your cushion to ${it.money()}") } }
    }
}

/* ============================== Shared wording ============================== */

private fun cushionWords(key: KeyMoment, s: DecisionSession) =
    if (s.constraints.minCashBuffer == null) "a ${key.cushion.money()} cushion" else "your ${key.cushion.money()} cushion"

/** The finding sentence, assembled from the key moment. Every amount is an engine value. */
private fun finding(key: KeyMoment, s: DecisionSession, b: Briefing, onMonth: () -> Unit = {}, onCushion: () -> Unit = {}) = buildAnnotatedString {
    val mark = SpanStyle(background = Rt.Gold, fontWeight = FontWeight.Normal)
    val month = monthName(key.month)
    val t = tested(b)
    val condition = when {
        key.lowerPayMonths.isNotEmpty() -> "if your pay is ${s.stress.lowIncomeReduction.money()} lower in ${monthList(key.lowerPayMonths)}"
        t.cases <= 1 && t.inputCombinations <= 1 -> "with the figures you gave"
        else -> "in the hardest case Routes tested"
    }
    val n = key.monthsBelowCushion.size
    when {
        key.moneyLeft.isNegative -> {
            val first = key.firstShort ?: key.month
            append("${condition.replaceFirstChar(Char::uppercase)}, ")
            withStyle(mark) { quietLink("month", onMonth, "you'd run out of money in ${monthName(first)}") }
            append(if (first == key.month) ": " else ". By $month you'd be "); quietLink("amount", onMonth, "${(-key.moneyLeft).money()} short"); append(".")
        }
        n == 1 -> {
            append("Most months look fine. But $condition, ")
            withStyle(mark) { quietLink("month", onMonth, "$month could be tight") }
            append(": you'd have "); quietLink("amount", onMonth, "${key.moneyLeft.money()} left"); append(", below "); quietLink("cushion", onCushion, cushionWords(key, s)); append(".")
        }
        n > 1 -> {
            append("$n months could fall below "); quietLink("cushion", onCushion, cushionWords(key, s)); append(" $condition. ")
            withStyle(mark) { quietLink("month", onMonth, "The tightest is $month") }
            append(": "); quietLink("amount", onMonth, "${key.moneyLeft.money()} left"); append(".")
        }
        else -> {
            append("${condition.replaceFirstChar(Char::uppercase)}, ")
            withStyle(mark) { append("every month stays at or above "); quietLink("cushion", onCushion, cushionWords(key, s)) }
            append(". "); quietLink("month", onMonth, "The tightest is $month: ${key.moneyLeft.money()} left"); append(".")
        }
    }
}

private val linkStyle = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))
/** In the headline the links stay quiet: underlined only on hover or keyboard focus. */
private val quietLinkStyle = TextLinkStyles(style = SpanStyle(), focusedStyle = SpanStyle(textDecoration = TextDecoration.Underline), hoveredStyle = SpanStyle(textDecoration = TextDecoration.Underline))

private fun AnnotatedString.Builder.quietLink(tag: String, action: () -> Unit, text: String) =
    withLink(LinkAnnotation.Clickable(tag, quietLinkStyle) { action() }) { append(text) }

/** A number in a sentence that does something when clicked (keyboard-focusable link). */
private fun AnnotatedString.Builder.link(tag: String, action: () -> Unit, text: String) =
    withLink(LinkAnnotation.Clickable(tag, linkStyle) { action() }) { append(text) }

/** [text] with the first occurrence of [part] made clickable. */
private fun linked(text: String, part: String, action: () -> Unit): AnnotatedString = buildAnnotatedString {
    val at = text.indexOf(part)
    if (at < 0) { append(text); return@buildAnnotatedString }
    append(text.substring(0, at)); link(part, action, part); append(text.substring(at + part.length))
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
        ?: s.hypotheticalValues.entries.firstOrNull()?.let { (id, v) -> s.inputs.first { it.id == id }.let { i -> "${plain(i.questionKey).replaceFirstChar(Char::lowercase)} at ${valueWords(i, v)}" } }
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
        is Correction.AnswerInput -> n.session.inputs.first { it.id == k.inputId }.let { i ->
            "You confirmed ${plain(i.questionKey).replaceFirstChar(Char::lowercase)}" + ((k.value as? InputState.Exact)?.let { ": ${valueWords(i, it.value)}" } ?: "")
        }
        is Correction.RejectRepair -> "You said no to spending less"
        is Correction.RemoveRejection -> "You took back a “not for me”"
        is Correction.SetMinCashBuffer -> "You set your cushion to ${k.amount.money()}"
        is Correction.SetCommitment -> "You added ${k.commitment.labelKey}"
        else -> "You updated your plan"
    }
    is GraphChange.Explored -> "A what-if"
    is GraphChange.MarketDataUpdated -> "New reference figures"
}

/* ============================== Small parts ============================== */

@Composable
internal fun AnimatedMoney(value: Chf, style: TextStyle) {
    val reduce = LocalReduceMotion.current
    AnimatedContent(value, transitionSpec = {
        if (reduce) EnterTransition.None togetherWith ExitTransition.None
        else (fadeIn(tween(350, 150)) + slideInVertically(tween(350, 150)) { it / 3 }) togetherWith (fadeOut(tween(180)) + slideOutVertically(tween(180)) { -it / 3 })
    }) { Text(it.money(), style = style) }
}

@Composable
internal fun Reveal(visible: Boolean, content: @Composable () -> Unit) {
    val reduce = LocalReduceMotion.current
    AnimatedVisibility(
        visible,
        enter = if (reduce) EnterTransition.None else fadeIn(tween(250, 80)) + expandVertically(tween(320)),
        exit = if (reduce) ExitTransition.None else fadeOut(tween(150)) + shrinkVertically(tween(260)),
    ) { content() }
}

@Composable
internal fun LinkText(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = RtType.Body.copy(color = Rt.LinkRed, textDecoration = TextDecoration.Underline),
        modifier = Modifier.focusRing().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 2.dp, vertical = 2.dp),
    )
}

@Composable
internal fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick, Modifier.focusRing(), enabled = enabled, shape = Rt.Radius,
        colors = ButtonDefaults.buttonColors(containerColor = Rt.Red, contentColor = Rt.White, disabledContainerColor = Rt.Track, disabledContentColor = Rt.Ink2),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    ) { Text(text, style = RtType.Button) }
}

@Composable
internal fun SecondaryButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick, Modifier.focusRing(), shape = Rt.Radius, border = BorderStroke(1.dp, Rt.Ink),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Rt.Ink),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    ) { Text(text, style = RtType.Button) }
}

private fun changeWords(c: Cause.Change): String {
    val (amount, what) = when {
        c.incomeDelta.isNegative -> -c.incomeDelta to "less pay"
        c.incomeDelta > Chf.ZERO -> c.incomeDelta to "more pay"
        c.expenseDelta.isNegative -> -c.expenseDelta to "less spending"
        else -> c.expenseDelta to "more spending"
    }
    val span = if (c.untilMonth - c.fromMonth == 1) "in ${monthName(c.fromMonth)}" else "${monthName(c.fromMonth)} to ${monthName(c.untilMonth - 1)}"
    return "${plain(c.label)}: ${amount.money()} $what each month, $span"
}
