package ch.ripple.app.phone

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.ripple.core.*

/** One inspectable line of the twin: a profile fact or one decision input. */
private sealed interface FactRef {
    data class Profile(val field: ProfileField) : FactRef
    data class Input(val decisionId: String, val inputId: String) : FactRef
}

fun ProfileField.label() = when (this) {
    ProfileField.CASH -> "Money available today"
    ProfileField.INCOME -> "Pay coming in each month (after tax)"
    ProfileField.SPENDING -> "Usual monthly spending"
    ProfileField.CUSHION -> "Amount you always like to keep"
}

private fun Via.said() = when (this) { Via.VOICE -> "said by voice"; Via.TEXT -> "typed"; Via.TAP -> "set by you on screen"; Via.EXAMPLE -> "example data (fictional)" }

private fun Fact.tag() = when {
    via == Via.EXAMPLE -> Tag.EXAMPLE
    amount is Amount.Known -> Tag.CONFIRMED
    amount is Amount.Estimate -> Tag.ESTIMATE
    else -> Tag.RANGE
}

private fun InputState.tag() = when (this) {
    is InputState.Exact -> Tag.CONFIRMED
    is InputState.Range -> if (low == high) Tag.ESTIMATE else Tag.RANGE
    InputState.Unknown -> Tag.UNKNOWN
}

@Composable
fun TwinScreen(session: RoutesSession, nav: PhoneNav, platform: PhonePlatform) {
    var open by remember { mutableStateOf<String?>(null) }
    var fact by remember { mutableStateOf<FactRef?>(null) }
    var confirmForget by remember { mutableStateOf(false) }
    platform.backHandler(fact != null) { fact = null }
    val p = session.profile
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("My Twin", style = Ty.Title, modifier = Modifier.weight(1f))
                p?.let { Text(if (it.fictional) "${it.name} · fictional" else it.name, style = Ty.Small) }
            }
            Column(Modifier.fillMaxWidth().border(1.dp, Tk.Line, Tk.Card).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("What Routes remembers", style = Ty.Strong)
                Text("Saved on this phone only: the facts below, your decisions and every change you made to them. This app does not save voice recordings or a conversation log. Your session is not synced. When you use Live voice, audio and the app's replies are sent to Google for transcription and speech.", style = Ty.Small)
                Text("Facts you said or typed are recorded as you gave them, and you can see where each came from, correct it or forget it here.", style = Ty.Small)
                if (confirmForget) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Primary("Forget everything", Modifier.weight(1f)) { session.forgetEverything(); confirmForget = false }
                    Secondary("Keep", Modifier.weight(1f)) { confirmForget = false }
                } else LinkText("Forget everything…") { confirmForget = true }
            }
            if (p == null && session.decisions.isEmpty()) {
                Text("Nothing recorded yet. Routes only records what comes up while you explore a decision.", style = Ty.Body.copy(color = Tk.Ink2))
                Secondary("Go to Explore") { nav.space = Space.EXPLORE }
            }
            if (p != null) Group("About you", "${p.facts.size} facts", open == "you", { open = if (open == "you") null else "you" }) {
                ProfileField.entries.forEach { f ->
                    val v = p.facts[f]
                    FactRow(f.label(), v?.amount?.show() ?: "not recorded", v?.tag()) { fact = FactRef.Profile(f) }
                }
            }
            session.decisions.forEach { d ->
                val s = d.confirmedHead.session
                val inputs = s.inputs.filter { it.unit == InputUnit.CHF && it.id !in setOf(DecisionSpec.INCOME, DecisionSpec.SPENDING) }
                val whatIfs = d.graph.nodes.count { it.hypothetical }
                Group(d.title, "${inputs.size} amounts · $whatIfs what-ifs", open == d.id, { open = if (open == d.id) null else d.id }) {
                    inputs.forEach { i -> FactRow(d.inputLabel(i.id), s.state(i.id).show(i), s.state(i.id).tag()) { fact = FactRef.Input(d.id, i.id) } }
                    if (whatIfs > 0) Text("$whatIfs what-if versions exist. They are explorations, not facts: see Versions in My Routes.", style = Ty.Small)
                    LinkText("Open in My Routes") { session.open(d.id); nav.openDecision = d.id; nav.space = Space.ROUTES }
                }
            }
            val assumptions = buildList {
                p?.lowerPay?.takeIf { it.maxLowIncomeMonths > 0 }?.let { add("Pay may be ${it.lowIncomeReduction.money()} lower in up to ${it.maxLowIncomeMonths} months: every combination is tested") }
                p?.plannedBills?.forEach { add("${it.label}: ${it.amount.money()} in ${monthName(it.month)}" + if (p.fictional) " (example data)" else "") }
                if (session.decisions.isNotEmpty()) {
                    add("Cash only: tax, pension and investments are not modelled")
                    add("Looks ${session.decisions.first().spec.horizonMonths} months ahead; month 1 is shown as January")
                    add("Amounts you weren't sure of are tested at every value in your range, not guessed")
                }
            }
            if (assumptions.isNotEmpty()) Group("Assumptions Routes makes", "${assumptions.size}", open == "assume", { open = if (open == "assume") null else "assume" }) {
                assumptions.forEach { Row { TagChip(Tag.ASSUMED); Spacer(Modifier.width(8.dp)); Text(it, style = Ty.Body) } }
            }
            val limits = session.decisions.flatMap { d -> d.confirmedHead.session.constraints.let { c ->
                c.rejections.map { r -> Triple(d, "Not cutting ${r.action.monthly.money()} a month or more" + (r.reason?.let { " (“$it”)" } ?: ""), Correction.RemoveRejection(r.id) as Correction) } +
                    c.commitments.map { m -> Triple(d, "${m.labelKey}: ${m.monthly.money()} a month", Correction.RemoveCommitment(m.id) as Correction) }
            } }
            Group("Your limits", if (limits.isEmpty()) "none yet" else "${limits.size}", open == "limits", { open = if (open == "limits") null else "limits" }) {
                p?.facts?.get(ProfileField.CUSHION)?.let { Text("Keep at least ${it.amount.smallest.money()} (edit it under About you)", style = Ty.Body) }
                if (limits.isEmpty()) Text("Limits appear when you say no to a suggestion, e.g. “Not for me” in Compare routes.", style = Ty.Small)
                limits.forEach { (d, text, undo) ->
                    Text("$text · ${d.title}", style = Ty.Body)
                    LinkText("Remove this limit") { session.confirm(d.id, undo, Via.TAP) }
                }
            }
            Group("Sources", "none used", open == "src", { open = if (open == "src") null else "src" }) {
                Text("No external data or sources are used in these decisions. Every figure is yours, an example, or computed by the engine from them.", style = Ty.Body)
            }
            val history = remember(session.profile, session.decisions) { historyLines(session) }
            Group("History", "${history.size} changes", open == "hist", { open = if (open == "hist") null else "hist" }) {
                history.forEach { (at, text) -> Text("${platform.timeText(at)}  $text", style = Ty.Body) }
            }
            Legal()
        }
        fact?.let { f -> Sheet(factTitle(session, f), { fact = null }) { FactDetail(session, f, platform) { fact = null } } }
    }
}

private fun factTitle(session: RoutesSession, f: FactRef) = when (f) {
    is FactRef.Profile -> f.field.label()
    is FactRef.Input -> session.decision(f.decisionId).inputLabel(f.inputId)
}

@Composable
private fun Group(title: String, detail: String, expanded: Boolean, toggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Divider()
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = toggle).padding(vertical = 12.dp)
            .semantics { contentDescription = "$title, $detail, ${if (expanded) "expanded" else "collapsed"}" }, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Ty.Strong, modifier = Modifier.weight(1f))
            Text("$detail ${if (expanded) "⌃" else "›"}", style = Ty.Small)
        }
        if (expanded) Column(Modifier.padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun FactRow(label: String, value: String, tag: Tag?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Ty.Body, modifier = Modifier.weight(1f))
            tag?.let { TagChip(it) }
        }
        Text(value, style = Ty.Strong)
    }
}

@Composable
private fun ColumnScope.FactDetail(session: RoutesSession, f: FactRef, platform: PhonePlatform, close: () -> Unit) {
    var text by remember(f) { mutableStateOf("") }
    var problem by remember(f) { mutableStateOf<String?>(null) }
    when (f) {
        is FactRef.Profile -> {
            val v = session.profile?.facts?.get(f.field)
            Text(v?.amount?.show() ?: "Not recorded", style = Ty.Big)
            v?.let { Row { TagChip(it.tag()); Spacer(Modifier.width(8.dp)); Text("${it.via.said()} · ${platform.timeText(it.at)}", style = Ty.Small) } }
            val used = session.decisions.map { it.title }
            Text(if (used.isEmpty()) "Not used by any decision yet." else "Used by: ${used.joinToString()}. Changing it adds a new version to each; earlier versions stay.", style = Ty.Body)
            session.profile?.edits?.filter { it.field == f.field }?.takeIf { it.isNotEmpty() }?.let { e ->
                Text("Earlier: " + e.joinToString(" → ") { it.after?.show() ?: "forgotten" }, style = Ty.Small)
            }
        }
        is FactRef.Input -> {
            val d = session.decision(f.decisionId)
            val s = d.confirmedHead.session
            val i = s.inputs.first { it.id == f.inputId }
            Text(s.state(i.id).show(i), style = Ty.Big)
            val step = d.steps.lastOrNull { it is Step.Correct && (it.correction as? Correction.AnswerInput)?.inputId == i.id }
            Row { TagChip(s.state(i.id).tag()); Spacer(Modifier.width(8.dp))
                Text(step?.let { "${it.via.said()} · ${platform.timeText(it.at)}" } ?: "${d.via.said()} when “${d.title}” was set up", style = Ty.Small) }
            val whatIfs = d.graph.nodes.count { n -> n.hypothetical && i.id in n.session.hypotheticalValues }
            Text("Used by: ${d.title}" + if (whatIfs > 0) ", and tried in $whatIfs what-if versions (those keep their tried value)." else ".", style = Ty.Body)
        }
    }
    Divider()
    Text("Correct it", style = Ty.Label)
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).border(1.5.dp, Tk.Ink, Tk.R).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
        if (text.isEmpty()) Text("An amount, “about 900” or “800 to 1,000”", style = Ty.Body.copy(color = Tk.Ink2))
        BasicTextField(text, { text = it }, textStyle = Ty.Body, singleLine = true, cursorBrush = SolidColor(Tk.Ink), modifier = Modifier.fillMaxWidth())
    }
    problem?.let { Text(it, style = Ty.Small.copy(color = Tk.Bronze)) }
    Primary("Save as confirmed") {
        val a = (parseAnswer(text) as? Answer.Value)?.amount
        if (a == null) { problem = "Type an amount, like 1450, “about 900” or “800 to 1,000”."; return@Primary }
        when (f) {
            is FactRef.Profile -> session.setFact(f.field, a, Via.TAP)
            is FactRef.Input -> session.confirm(f.decisionId, Correction.AnswerInput(f.inputId, a.asState()), Via.TAP)
        }
        close()
    }
    when (f) {
        is FactRef.Profile -> {
            Text("Forgetting removes it from your twin. Decisions you already explored keep the figure they were calculated with; Routes asks again next time.", style = Ty.Small)
            LinkText("Forget this fact") { session.setFact(f.field, null, Via.TAP); close() }
        }
        is FactRef.Input -> {
            Text("Marking it not known makes Routes test the whole range instead of one figure. The earlier version stays in Versions.", style = Ty.Small)
            LinkText("Mark as not known") { session.confirm(f.decisionId, Correction.AnswerInput(f.inputId, InputState.Unknown), Via.TAP); close() }
        }
    }
}

/** Newest first: every recorded change to the twin and to each decision. */
private fun historyLines(session: RoutesSession): List<Pair<Long, String>> {
    val profile = session.profile?.edits.orEmpty().map { e ->
        e.at to "${e.field.label()}: ${e.before?.show() ?: "—"} → ${e.after?.show() ?: "forgotten"} (${e.via.said()})"
    }
    val decisions = session.decisions.flatMap { d ->
        listOf(d.createdAt to "“${d.title}” created from your question (${d.via.said()})") + d.steps.mapNotNull { s ->
            when (s) {
                is Step.Correct -> s.at to "${d.title}: saved ${describe(d, s.correction)} (${s.via.said()})"
                is Step.Explore -> s.at to "${d.title}: what-if ${describe(d, s.hypothesis)} (${s.via.said()})"
                is Step.Select -> null
            }
        }
    }
    return (profile + decisions).sortedByDescending { it.first }
}
