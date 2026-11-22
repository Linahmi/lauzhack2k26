package ch.ripple.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.ripple.core.Amount
import ch.ripple.core.AiReading
import ch.ripple.core.ExampleDecisions
import ch.ripple.core.GeoScope
import ch.ripple.core.HttpResearchProvider
import ch.ripple.core.OllamaDecisionReader
import ch.ripple.core.ResearchCache
import ch.ripple.core.ResearchMode
import ch.ripple.core.readAiProposal
import ch.ripple.core.rentResearchRequest
import ch.ripple.core.researchArea
import ch.ripple.core.runResearch
import ch.ripple.core.ChangeKind
import ch.ripple.core.ChangeTiming
import ch.ripple.core.Chf
import ch.ripple.core.DecisionDraft
import ch.ripple.core.DecisionSpec
import ch.ripple.core.FinancialChange
import ch.ripple.core.FinancialDomain
import ch.ripple.core.Situation
import ch.ripple.core.StressSet
import ch.ripple.core.problems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/* ============================== Ask ============================== */

@Composable
internal fun AskView(onAsk: (String) -> Unit, onAlex: () -> Unit, onSam: () -> Unit, onNoor: () -> Unit, onImport: () -> Unit) {
    var q by remember { mutableStateOf("") }
    Column(Modifier.padding(top = 70.dp).widthIn(max = 820.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Text("What happens if…?", style = RtType.Title)
        Text("Ask about a decision. Routes turns it into the money that changes, then lets you try it out month by month.", style = RtType.Lead)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                q, { q = it.take(200) }, singleLine = true,
                placeholder = { Text("I study abroad for six months", style = RtType.Body.copy(color = Rt.Ink2)) },
                textStyle = RtType.Body,
                modifier = Modifier.width(560.dp).onPreviewKeyEvent {
                    if (it.key == Key.Enter && it.type == KeyEventType.KeyDown && q.isNotBlank()) { onAsk(q); true } else false
                },
            )
            PrimaryButton("Explore  →", enabled = q.isNotBlank()) { onAsk(q) }
        }
        Spacer(Modifier.height(18.dp))
        Text("Or open a fictional example", style = RtType.Label)
        Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            LinkText("Alex moves out", onAlex)
            LinkText("Sam studies abroad", onSam)
            LinkText("Noor works fewer hours", onNoor)
        }
        Spacer(Modifier.height(10.dp))
        LinkText("Open a decision from your phone", onImport)
    }
}

/* ============================== Describe ============================== */

private enum class Sure(val words: String) { KNOW("I know it"), ABOUT("About"), BETWEEN("Between") }

/** Form state for one amount: nothing is pre-filled unless the person typed it. */
private class AmountForm(sure: Sure = Sure.KNOW, a: String = "", b: String = "") {
    var sure by mutableStateOf(sure)
    var a by mutableStateOf(a)
    var b by mutableStateOf(b)
    fun amount(): Amount? {
        val x = a.toLongOrNull()?.let(Chf::francs) ?: return null
        return when (sure) {
            Sure.KNOW -> Amount.Known(x)
            Sure.ABOUT -> Amount.Estimate(x)
            Sure.BETWEEN -> b.toLongOrNull()?.let(Chf::francs)?.takeIf { it > x }?.let { Amount.Between(x, it) }
        }
    }
    companion object {
        fun of(a: Amount?): AmountForm = when (a) {
            null -> AmountForm()
            is Amount.Known -> AmountForm(Sure.KNOW, a.value.whole())
            is Amount.Estimate -> AmountForm(Sure.ABOUT, a.value.whole())
            is Amount.Between -> AmountForm(Sure.BETWEEN, a.low.whole(), a.high.whole())
        }
    }
}

private fun Chf.whole() = (rappen / 100).toString()

private class ChangeForm(val id: String, label: String, kind: ChangeKind, timing: ChangeTiming, offset: Int, val amount: AmountForm) {
    var label by mutableStateOf(label)
    var kind by mutableStateOf(kind)
    var timing by mutableStateOf(timing)
    var offset by mutableStateOf(offset)
    /** Research behind the amount; dropped as soon as the person types a different figure. */
    var source by mutableStateOf<Triple<String, String, String>?>(null) // value text, source line, ref
    fun change(): FinancialChange? = runCatching {
        val s = source?.takeIf { it.first == amount.a && amount.sure == Sure.ABOUT }
        FinancialChange(id, label.ifBlank { "A change" }, kind, amount.amount(), if (kind.recurring) timing else ChangeTiming.FROM_THEN_ON, offset, s?.second, s?.third)
    }.getOrNull()
}

private val kindWords = mapOf(
    ChangeKind.LESS_INCOME to "Less pay each month",
    ChangeKind.MORE_INCOME to "More pay each month",
    ChangeKind.MORE_SPENDING to "More spending each month",
    ChangeKind.LESS_SPENDING to "Less spending each month",
    ChangeKind.ONE_OFF_COST to "A one-off cost",
    ChangeKind.REFUNDABLE_DEPOSIT to "A deposit you get back",
)

/** One question at a time. */
private sealed interface Step {
    data object Cash : Step
    data object Income : Step
    data object Spending : Step
    data class Change(val form: ChangeForm) : Step
    data object Start : Step
    data object Duration : Step
    data object Cushion : Step
}

private sealed interface AiState {
    data object Reading : AiState
    data object Unavailable : AiState
    data class Done(val reading: AiReading) : AiState
    data class Failed(val why: String) : AiState
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DescribeView(draft: DecisionDraft, from: DecisionSpec?, onBack: () -> Unit, onReady: (DecisionSpec) -> Unit) {
    val s = from?.situation
    var title by remember { mutableStateOf(draft.title) }
    val cash = remember { AmountForm(Sure.KNOW, s?.cash?.whole() ?: "") }
    val income = remember { AmountForm.of(s?.monthlyIncome) }
    val spending = remember { AmountForm.of(s?.monthlySpending) }
    var cannotCut by remember { mutableStateOf(s?.cannotCut?.takeIf { it > Chf.ZERO }?.whole() ?: "") }
    var cannotCutLabel by remember { mutableStateOf(s?.cannotCutLabel.orEmpty()) }
    var cushion by remember { mutableStateOf(from?.cushion?.whole() ?: "") }
    var lowerBy by remember { mutableStateOf(s?.lowerPay?.lowIncomeReduction?.takeIf { it > Chf.ZERO }?.whole() ?: "") }
    var lowerMonths by remember { mutableStateOf(s?.lowerPay?.maxLowIncomeMonths ?: 0) }
    var bills by remember { mutableStateOf(s?.plannedBills.orEmpty()) }
    var horizon by remember { mutableStateOf(from?.horizonMonths ?: 12) }
    var start by remember { mutableStateOf(from?.startMonth ?: draft.startMonth ?: 0) }
    var duration by remember { mutableStateOf(from?.durationMonths ?: draft.durationMonths) }
    var nextId by remember { mutableStateOf(1) }
    var place by remember { mutableStateOf(draft.location) }
    val changes = remember {
        mutableStateListOf<ChangeForm>().apply {
            (from?.changes ?: draft.changes).forEach { c ->
                add(ChangeForm(c.id, c.label, c.kind, c.timing, c.monthOffset, AmountForm.of(c.amount)).also { f ->
                    if (c.source != null && c.amount is Amount.Estimate) f.source = Triple((c.amount as Amount.Estimate).value.whole(), c.source!!, c.sourceRef ?: "")
                })
            }
        }
    }
    var at by remember { mutableStateOf(if (from != null) Int.MAX_VALUE else 0) }
    var editChanges by remember { mutableStateOf(false) }

    // The local AI reads the question in the background; the person never waits for it.
    var ai by remember { mutableStateOf<AiState>(AiState.Reading) }
    val reader = remember { OllamaDecisionReader() }
    LaunchedEffect(draft.intention) {
        if (from != null) { ai = AiState.Unavailable; return@LaunchedEffect }
        ai = withContext(Dispatchers.IO) {
            runCatching {
                if (!reader.available()) AiState.Unavailable
                else AiState.Done(readAiProposal(draft.intention, draft, reader.read(draft.intention)))
            }.getOrElse { AiState.Failed(it.message ?: it.javaClass.simpleName) }
        }
        (ai as? AiState.Done)?.reading?.let { r -> if (place == null) place = r.location }
    }

    val steps = buildList {
        add(Step.Cash); add(Step.Income); add(Step.Spending)
        changes.forEach { add(Step.Change(it)) }
        add(Step.Start)
        if (draft.needsDuration || duration != null || changes.any { it.kind.recurring && it.timing == ChangeTiming.WHILE_IT_LASTS }) add(Step.Duration)
        add(Step.Cushion)
    }
    val current = steps.getOrNull(at)

    val situation = runCatching {
        Situation(
            cash = Chf.francs(cash.a.toLong()),
            monthlyIncome = income.amount()!!,
            monthlySpending = spending.amount()!!,
            cannotCut = cannotCut.toLongOrNull()?.let(Chf::francs) ?: Chf.ZERO,
            cannotCutLabel = cannotCutLabel.trim(),
            plannedBills = bills,
            lowerPay = lowerBy.toLongOrNull()?.takeIf { it > 0 && lowerMonths > 0 }?.let { StressSet(lowerMonths, Chf.francs(it)) } ?: StressSet(0, Chf.ZERO),
        )
    }.getOrNull()
    val built = changes.map { it.change() }
    val spec = situation?.takeIf { built.all { it != null } }?.let {
        DecisionSpec(
            intention = draft.intention, title = title.trim(), situation = it, changes = built.filterNotNull(),
            startMonth = start, durationMonths = duration, horizonMonths = horizon,
            cushion = cushion.toLongOrNull()?.let(Chf::francs), category = draft.category, coverage = draft.coverage,
            personName = from?.personName ?: "You", fictional = from?.fictional ?: false,
        )
    }
    val problems = buildList {
        if (cash.a.toLongOrNull() == null) add("How much money do you have now?")
        if (income.amount() == null) add("What's your monthly pay after deductions?")
        if (spending.amount() == null) add("What do you spend in a usual month?")
        changes.filter { it.amount.amount() == null }.forEach { add("How much is “${it.label.ifBlank { "this change" }}”?") }
        spec?.problems()?.let(::addAll)
    }.distinct()

    fun valid(st: Step): Boolean = when (st) {
        Step.Cash -> cash.a.toLongOrNull() != null
        Step.Income -> income.amount() != null
        Step.Spending -> spending.amount() != null
        is Step.Change -> st.form.amount.amount() != null && st.form.label.isNotBlank()
        Step.Start, Step.Cushion -> true
        Step.Duration -> duration != null
    }
    fun next() { at = (steps.indexOfFirst { !valid(it) }.takeIf { it > at } ?: (at + 1)).coerceAtMost(steps.size) }

    Column(Modifier.padding(top = 18.dp).widthIn(max = 820.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        LinkText("‹ Ask something else", onBack)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("You asked: “${draft.intention}”", style = RtType.Label)
            Text(title, style = RtType.Title)
            Text(
                if (changes.isNotEmpty()) "This usually changes " + changes.joinToString(", ") { it.label.replaceFirstChar(Char::lowercase) } + ". A few figures from you and Routes can show you the months ahead."
                else "Routes didn't recognise this decision. Tell it what would change.",
                style = RtType.Lead,
            )
            AiLine(ai, changes.map { it.kind }.toSet()) { c ->
                changes.add(ChangeForm(c.id, c.label, c.kind, c.timing, 0, AmountForm()))
                at = 3 + changes.size - 1
            }
            draft.coverage.unsupportedDomains.takeIf { it.isNotEmpty() }?.let { missing ->
                Text("Your question touches ${missing.joinToString(" and ") { domainWords(it) }}, which Routes can't model. It shows the cash part only.", style = RtType.Body.copy(color = Rt.Bronze))
            }
        }

        // Answered questions stay visible as short lines; clicking one reopens it.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            steps.take(at.coerceAtMost(steps.size)).forEachIndexed { i, st ->
                Answered(question(st), answer(st, cash, income, spending, start, duration, cushion, horizon)) { at = i }
            }
        }

        if (current != null) Column(
            Modifier.fillMaxWidth().border(1.5.dp, Rt.Ink, Rt.CardRadius).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(question(current), style = RtType.Finding)
            when (current) {
                Step.Cash -> {
                    MoneyField(cash.a, "Amount", onEnter = { if (valid(current)) next() }) { cash.a = it }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Text("Or start from fictional figures:", style = RtType.Small)
                        listOf("Sam's" to ExampleDecisions.samStudyAbroad, "Noor's" to ExampleDecisions.noorFewerHours).forEach { (who, ex) ->
                            LinkText("$who situation") {
                                val sit = ex.situation
                                cash.a = sit.cash.whole(); income.sure = Sure.KNOW; income.a = sit.monthlyIncome.largest.whole()
                                spending.sure = Sure.KNOW; spending.a = sit.monthlySpending.largest.whole()
                                cannotCut = sit.cannotCut.takeIf { it > Chf.ZERO }?.whole() ?: ""; cannotCutLabel = sit.cannotCutLabel
                                bills = sit.plannedBills; cushion = ex.cushion?.whole() ?: ""
                                at = 3
                            }
                        }
                    }
                }
                Step.Income -> { Hint("After deductions, as it reaches your account."); AmountInputs(income, onEnter = { if (valid(current)) next() }) }
                Step.Spending -> { Hint("Everything that usually goes out in a month."); AmountInputs(spending, onEnter = { if (valid(current)) next() }) }
                is Step.Change -> {
                    val f = current.form
                    Hint(kindWords.getValue(f.kind) + if (f.kind.recurring && f.timing == ChangeTiming.WHILE_IT_LASTS) ", only while it lasts" else "")
                    AmountInputs(f.amount, onEnter = { if (valid(current)) next() })
                    f.source?.takeIf { it.first == f.amount.a && f.amount.sure == Sure.ABOUT }?.let { Text("From ${it.second}. Not your own figure until you confirm it later.", style = RtType.Small.copy(color = Rt.Ink)) }
                    if (f.kind == ChangeKind.MORE_SPENDING && f.id == "rent") RentResearch(place) { value, line, ref ->
                        f.amount.sure = Sure.ABOUT; f.amount.a = value.whole(); f.source = Triple(value.whole(), line, ref)
                    } else if (f.kind == ChangeKind.MORE_SPENDING && place != null && researchArea(place) == null)
                        Text("Routes can only look up figures from official Swiss statistics (Lausanne, Vaud, Switzerland). For $place, enter your own estimate or a range.", style = RtType.Small)
                    LinkText(if (editChanges) "Done changing what's included" else "Change what's included") { editChanges = !editChanges }
                }
                Step.Start -> Stepper(monthName(start), onLess = { start = (start - 1).coerceAtLeast(0) }, onMore = { start = (start + 1).coerceAtMost(horizon - 1) })
                Step.Duration -> Stepper(duration?.let { "$it month${if (it == 1) "" else "s"}" } ?: "choose",
                    onLess = { duration = duration?.let { if (it <= 1) null else it - 1 } }, onMore = { duration = ((duration ?: 0) + 1).coerceAtMost(horizon) })
                Step.Cushion -> {
                    Hint("Your own reference point, not a rule. Without one, Routes checks you never go below CHF 0.")
                    MoneyField(cushion, "Optional", onEnter = { next() }) { cushion = it }
                    var more by remember { mutableStateOf(cannotCut.isNotBlank() || lowerBy.isNotBlank()) }
                    LinkText(if (more) "Fewer details" else "More about your situation (optional)") { more = !more }
                    Reveal(more) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Spending you can't cut (rent, family support…)", style = RtType.Body, modifier = Modifier.width(320.dp))
                                MoneyField(cannotCut, "Optional") { cannotCut = it }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Some months my pay can be lower by", style = RtType.Body, modifier = Modifier.width(320.dp))
                                MoneyField(lowerBy, "Optional") { lowerBy = it }
                                Text("in up to", style = RtType.Body)
                                Stepper("$lowerMonths month${if (lowerMonths == 1) "" else "s"}", onLess = { lowerMonths = (lowerMonths - 1).coerceAtLeast(0) }, onMore = { lowerMonths = (lowerMonths + 1).coerceAtMost(3) })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Look ahead", style = RtType.Body, modifier = Modifier.width(320.dp))
                                Stepper("$horizon months", onLess = { horizon = (horizon - 6).coerceAtLeast(6); start = start.coerceAtMost(horizon - 1) }, onMore = { horizon = (horizon + 6).coerceAtMost(36) })
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton("Next  →", enabled = valid(current)) { next() }
                if (current == Step.Cushion) LinkText("Skip") { cushion = ""; next() }
            }
        }
        Reveal(editChanges) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                changes.forEachIndexed { i, c -> ChangeEditor(c, onRemove = { changes.removeAt(i); at = at.coerceAtMost(3 + changes.size) }) }
                if (changes.size < 8) LinkText("+ Add a change") {
                    changes.add(ChangeForm("change_${nextId}", "", ChangeKind.ONE_OFF_COST, ChangeTiming.FROM_THEN_ON, 0, AmountForm())); nextId++
                }
            }
        }

        if (current == null) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (problems.isNotEmpty()) problems.forEach { Text("· $it", style = RtType.Body) }
            PrimaryButton("Show my routes  →", enabled = problems.isEmpty() && spec != null) { spec?.let(onReady) }
            Text("Routes follows cash month by month. It doesn't include tax, pension, investments or debt.", style = RtType.Small)
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Hint(text: String) = Text(text, style = RtType.Body.copy(color = Rt.Ink2))

private fun question(st: Step): String = when (st) {
    Step.Cash -> "How much money do you have now?"
    Step.Income -> "What's your monthly pay?"
    Step.Spending -> "What do you spend in a usual month?"
    is Step.Change -> "How much is “${st.form.label.ifBlank { "this change" }}”?"
    Step.Start -> "When would it start?"
    Step.Duration -> "How long would it last?"
    Step.Cushion -> "Is there an amount you'd like to always keep?"
}

private fun answer(st: Step, cash: AmountForm, income: AmountForm, spending: AmountForm, start: Int, duration: Int?, cushion: String, horizon: Int): String {
    fun Amount?.words() = when (this) {
        null -> "not given"
        is Amount.Known -> value.money()
        is Amount.Estimate -> "about ${value.money()}"
        is Amount.Between -> "${low.money()} – ${high.money()}"
    }
    return when (st) {
        Step.Cash -> cash.a.toLongOrNull()?.let { Chf.francs(it).money() } ?: "not given"
        Step.Income -> income.amount().words()
        Step.Spending -> spending.amount().words()
        is Step.Change -> st.form.amount.amount().words() + (st.form.change()?.source?.let { " · from $it" } ?: "")
        Step.Start -> "${monthName(start)} (looking $horizon months ahead)"
        Step.Duration -> duration?.let { "$it month${if (it == 1) "" else "s"}" } ?: "not given"
        Step.Cushion -> cushion.toLongOrNull()?.let { Chf.francs(it).money() } ?: "none"
    }
}

@Composable
private fun Answered(q: String, a: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().focusRing().clickable(role = Role.Button, onClickLabel = "Change this answer", onClick = onClick).padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(q, style = RtType.Body.copy(color = Rt.Ink2), modifier = Modifier.weight(1f))
        Text(a, style = RtType.Body.copy(fontWeight = FontWeight.SemiBold))
    }
}

/** One quiet line about the local AI; its suggestions are offered, never applied silently. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiLine(state: AiState, have: Set<ChangeKind>, onAdd: (FinancialChange) -> Unit) {
    val style = RtType.Small
    when (state) {
        AiState.Reading -> Text("The local AI on this computer (Ollama) is also reading your question. It can take a minute; carry on meanwhile.", style = style)
        AiState.Unavailable -> Text("The local AI isn't running, so Routes used its built-in reading.", style = style)
        is AiState.Failed -> Text("The local AI couldn't read it (${state.why}); Routes used its built-in reading.", style = style)
        is AiState.Done -> {
            val extra = state.reading.extraChanges.filter { it.kind !in have }
            if (extra.isEmpty()) Text("The local AI read your question too and found nothing to add.", style = style)
            else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text("The local AI suggests also asking about:", style = style)
                extra.forEach { c -> Chip("+ ${c.label}", false) { onAdd(c) } }
            }
        }
    }
}

private sealed interface Look {
    data object Idle : Look
    data object Looking : Look
    data class Found(val value: Chf, val words: String, val line: String, val ref: String) : Look
    data class NotFound(val why: String) : Look
}

/** Offers official-statistics rents only where Routes can verify them; the person decides. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RentResearch(place: String?, onUse: (Chf, String, String) -> Unit) {
    val area = researchArea(place)
    if (area == null) {
        Text(if (place == null) "Routes can look up typical rents for Lausanne, Vaud or Switzerland from official statistics; say where in your question, or enter your own figure."
             else "Routes can only look up figures from official Swiss statistics. For $place, enter your own estimate or a range.", style = RtType.Small)
        return
    }
    var rooms by remember { mutableStateOf<Int?>(null) }
    var look by remember { mutableStateOf<Look>(Look.Idle) }
    LaunchedEffect(rooms) {
        val r = rooms ?: return@LaunchedEffect
        look = Look.Looking
        look = withContext(Dispatchers.IO) {
            val req = rentResearchRequest(place, r, java.time.Year.now().value) ?: return@withContext Look.NotFound("area not covered")
            val out = runResearch(req, HttpResearchProvider(timeoutMs = 60_000), ResearchCache.snapshot)
            val e = out.verified.firstOrNull()?.estimate ?: return@withContext Look.NotFound("no figure passed every check")
            val where = when (e.scope.code) { GeoScope.CH.code -> "Switzerland"; GeoScope.VD.code -> "the canton of Vaud"; else -> "Lausanne" }
            val ci = if (e.value.ciLow != null && e.value.ciHigh != null) " (published range of the average: ${e.value.ciLow!!.money()} – ${e.value.ciHigh!!.money()})" else ""
            val how = if (out.mode == ResearchMode.LIVE) "looked up just now" else "saved copy checked ${e.retrievedOn}; the live research service isn't running"
            val line = "${e.source.publisher}, ${e.observed.startYear}–${e.observed.endYear}"
            Look.Found(e.value.point, "Average net rent (without charges) for $r-room homes in $where: ${e.value.point.money()} a month$ci. Source: ${e.source.publisher}, “${e.source.title}”, ${e.observed.startYear}–${e.observed.endYear}; $how.", line, "market:${e.id}")
        }
    }
    Column(Modifier.fillMaxWidth().background(Rt.Chalk, Rt.Radius).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("I can look up typical rents in $place from official statistics, or you can enter your own number.", style = RtType.Body)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text("Rooms (kitchen and bathroom not counted):", style = RtType.Small)
            (1..4).forEach { n -> Chip("$n", rooms == n) { rooms = n } }
        }
        when (val l = look) {
            Look.Idle -> {}
            Look.Looking -> Text("Looking it up…", style = RtType.Small)
            is Look.NotFound -> Text("Routes couldn't verify a figure (${l.why}). Please enter your own.", style = RtType.Small)
            is Look.Found -> {
                Text(l.words, style = RtType.Body)
                Text("An average for others, not your rent. It's labelled as researched until you confirm your own figure.", style = RtType.Small)
                SecondaryButton("Use ${l.value.money()} as a starting estimate") { onUse(l.value, l.line, l.ref) }
            }
        }
    }
}

private fun domainWords(d: FinancialDomain) = when (d) {
    FinancialDomain.PENSION -> "pension"
    FinancialDomain.TAX -> "tax"
    FinancialDomain.INVESTMENT -> "investments"
    FinancialDomain.DEBT -> "loans or debt"
    FinancialDomain.CASH_FLOW -> "cash"
}



@Composable
private fun AmountInputs(f: AmountForm, allowRange: Boolean = true, onEnter: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MoneyField(f.a, if (f.sure == Sure.BETWEEN) "From" else "Amount", onEnter) { f.a = it }
        if (f.sure == Sure.BETWEEN) MoneyField(f.b, "To", onEnter) { f.b = it }
        if (allowRange) Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Sure.entries.forEach { s -> Chip(s.words, f.sure == s) { f.sure = s } }
        }
    }
}

@Composable
private fun MoneyField(value: String, label: String, onEnter: (() -> Unit)? = null, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { onChange(it.filter(Char::isDigit).take(8)) }, singleLine = true, prefix = { Text("CHF ") },
        label = { Text(label) }, textStyle = RtType.Body,
        modifier = Modifier.width(170.dp).onPreviewKeyEvent {
            if (onEnter != null && it.key == Key.Enter && it.type == KeyEventType.KeyDown) { onEnter(); true } else false
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChangeEditor(c: ChangeForm, onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Rt.Chalk, Rt.Radius).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(c.label, { c.label = it.take(50) }, singleLine = true, label = { Text("What is it?") }, textStyle = RtType.Body, modifier = Modifier.width(330.dp))
            AmountInputs(c.amount)
            Spacer(Modifier.weight(1f))
            LinkText("Remove", onRemove)
        }
        var pickKind by remember { mutableStateOf(false) }
        if (pickKind) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            kindWords.forEach { (k, w) -> Chip(w, c.kind == k) { c.kind = k; pickKind = false } }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!pickKind) {
                Text(kindWords.getValue(c.kind), style = RtType.Small.copy(fontWeight = FontWeight.SemiBold, color = Rt.Ink))
                LinkText("change") { pickKind = true }
                Spacer(Modifier.width(12.dp))
            }
            if (c.kind.recurring) {
                Chip("Only while it lasts", c.timing == ChangeTiming.WHILE_IT_LASTS) { c.timing = ChangeTiming.WHILE_IT_LASTS }
                Chip("From then on", c.timing == ChangeTiming.FROM_THEN_ON) { c.timing = ChangeTiming.FROM_THEN_ON }
                Spacer(Modifier.width(12.dp))
            }
            Text("Starts", style = RtType.Small)
            Stepper(if (c.offset == 0) "when it starts" else "${c.offset} month${if (c.offset == 1) "" else "s"} after",
                onLess = { c.offset = (c.offset - 1).coerceAtLeast(0) }, onMore = { c.offset = (c.offset + 1).coerceAtMost(24) })
        }
    }
}

@Composable
internal fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.focusRing().clickable(role = Role.RadioButton, onClick = onClick)
            .background(if (on) Rt.Ink else Rt.White, Rt.Radius).border(1.dp, if (on) Rt.Ink else Rt.Line, Rt.Radius)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) { Text(text, style = RtType.Small.copy(color = if (on) Rt.White else Rt.Ink, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)) }
}

@Composable
internal fun Stepper(text: String, onLess: () -> Unit, onMore: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.border(1.dp, Rt.Line, Rt.Radius)) {
        Box(Modifier.focusRing().clickable(role = Role.Button, onClickLabel = "Less", onClick = onLess).padding(horizontal = 12.dp, vertical = 6.dp)) { Text("‹", style = RtType.Body) }
        Text(text, style = RtType.Body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.widthIn(min = 120.dp).padding(horizontal = 6.dp))
        Box(Modifier.focusRing().clickable(role = Role.Button, onClickLabel = "More", onClick = onMore).padding(horizontal = 12.dp, vertical = 6.dp)) { Text("›", style = RtType.Body) }
    }
}

/** A spec back into an editable draft (used by "Change the plan"). */
internal fun draftOf(spec: DecisionSpec) = DecisionDraft(
    intention = spec.intention, title = spec.title, category = spec.category, changes = spec.changes,
    startMonth = spec.startMonth, durationMonths = spec.durationMonths,
    needsDuration = spec.changes.any { it.timing == ChangeTiming.WHILE_IT_LASTS }, coverage = spec.coverage, recognized = true,
)


/* ============================== From the phone ============================== */

/**
 * Opens a decision copied on the phone (Routes decision format, shared code in phone/SessionCodec).
 * The file holds only what the person said and did; the engine replays every step here, so the
 * laptop shows the same versions without trusting any stored result. Nothing syncs.
 */
@Composable
internal fun ImportView(onBack: () -> Unit, onOpen: (ch.ripple.app.phone.RoutesDecision) -> Unit) {
    var text by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    fun open(raw: String) {
        problem = null
        runCatching { ch.ripple.app.phone.importDecision(raw.trim()) }
            .onSuccess(onOpen)
            .onFailure { problem = "This isn't a Routes decision Routes can open: ${it.message ?: it.javaClass.simpleName}." }
    }
    Column(Modifier.padding(top = 18.dp).widthIn(max = 820.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LinkText("‹ Ask something else", onBack)
        Text("Open a decision from your phone", style = RtType.Title)
        Text("On the phone, open the decision in My Routes and choose “Copy this decision's data”. Send it to yourself however you like, then paste it here or open the saved file.", style = RtType.Lead)
        OutlinedTextField(
            text, { text = it; problem = null }, label = { Text("Paste the decision data") }, textStyle = RtType.Small,
            modifier = Modifier.fillMaxWidth().height(160.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton("Open it  →", enabled = text.isNotBlank()) { open(text) }
            SecondaryButton("Open a file…") {
                val d = java.awt.FileDialog(null as java.awt.Frame?, "Open a Routes decision", java.awt.FileDialog.LOAD)
                d.isVisible = true
                d.file?.let { name -> runCatching { java.io.File(d.directory, name).readText() }.onSuccess { text = it; open(it) }.onFailure { problem = "Routes couldn't read that file." } }
            }
        }
        problem?.let { Text(it, style = RtType.Body.copy(color = Rt.Bronze)) }
        Text("Only the decision's structure and its versions travel: no audio, no conversation. Every number is recomputed on this laptop. Nothing is sent anywhere and nothing syncs back.", style = RtType.Small)
    }
}
