package ch.ripple.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * A life decision as plain data: the person's current situation plus the financial changes the
 * decision brings, with timing. toSession() turns it into an ordinary DecisionSession, so every
 * decision type (moving, studying abroad, fewer hours, a car…) runs through the SAME engine:
 * simulate(), the stress search, unknown ranges, repairs, corrections and the decision graph.
 * Nothing here computes money; it only arranges the user's figures into engine primitives.
 */

/** How sure the person is about one amount. Routes never fills one in. */
sealed interface Amount {
    /** The person states it as a fact. */
    data class Known(val value: Chf) : Amount
    /** The person's own rough figure (labelled an estimate, tested as given). */
    data class Estimate(val value: Chf) : Amount
    /** Somewhere in this range: every value is tested. */
    data class Between(val low: Chf, val high: Chf) : Amount

    val largest: Chf get() = when (this) { is Known -> value; is Estimate -> value; is Between -> high }
    val smallest: Chf get() = when (this) { is Known -> value; is Estimate -> value; is Between -> low }
}

enum class ChangeKind(val recurring: Boolean) {
    LESS_INCOME(true), MORE_INCOME(true), MORE_SPENDING(true), LESS_SPENDING(true),
    ONE_OFF_COST(false),
    /** Money held but still yours (e.g. a rental deposit): lowers cash, not net worth. */
    REFUNDABLE_DEPOSIT(false),
}

enum class ChangeTiming {
    /** Only while the decision lasts (needs a duration). */
    WHILE_IT_LASTS,
    /** From the start to the end of the period looked at. */
    FROM_THEN_ON,
}

/** One financial consequence of a decision. [amount] null = still to be asked; never guessed. */
data class FinancialChange(
    val id: String,
    val label: String,
    val kind: ChangeKind,
    val amount: Amount?,
    val timing: ChangeTiming = ChangeTiming.FROM_THEN_ON,
    /** Months after the decision starts (one-offs and recurring changes alike). */
    val monthOffset: Int = 0,
    /** Set when the amount was taken from verified research: shown with it, never as the person's own. */
    val source: String? = null,
    val sourceRef: String? = null,
) {
    init {
        require(id.isNotBlank() && id !in RESERVED_INPUT_IDS) { "change id '$id' is blank or reserved" }
        require(label.isNotBlank()) { "change label cannot be blank" }
        require(monthOffset >= 0) { "monthOffset cannot be negative" }
    }
}

/** What the person has now. Only what the decision needs. */
data class Situation(
    val cash: Chf,
    val monthlyIncome: Amount,
    val monthlySpending: Amount,
    /** Part of monthly spending that can't be cut (rent, family support…). Repairs never touch it. */
    val cannotCut: Chf = Chf.ZERO,
    val cannotCutLabel: String = "",
    /** Bills already planned (yearly insurance…): month index + amount. */
    val plannedBills: List<OneOffCost> = emptyList(),
    /** "Some months my pay can be lower": tested as stress cases; zero = not tested. */
    val lowerPay: StressSet = StressSet(0, Chf.ZERO),
) {
    init { require(!cash.isNegative && !cannotCut.isNegative) { "cash and cannotCut cannot be negative" } }
}

data class DecisionSpec(
    /** The person's own question. */
    val intention: String,
    /** Short plain name, e.g. "Studying abroad". */
    val title: String,
    val situation: Situation,
    val changes: List<FinancialChange>,
    /** Month index the decision starts. */
    val startMonth: Int = 0,
    /** null = the change is lasting. */
    val durationMonths: Int? = null,
    val horizonMonths: Int = 12,
    /** The person's own cushion; null = Routes only checks money never goes below zero (assumed). */
    val cushion: Chf? = null,
    /** "The same, N months later" alternative; 0 = none. */
    val laterBy: Int = 3,
    val category: String? = null,
    val coverage: ModelCoverage = ModelCoverage(),
    val personName: String = "You",
    /** Demo profiles are fictional; a person's own entries stay on this device. */
    val fictional: Boolean = false,
) {
    companion object {
        /** Input ids reserved for the situation and timing. */
        const val INCOME = "income"
        const val SPENDING = "spending"
        const val START = "start"
        const val DURATION = "duration"
    }
}

internal val RESERVED_INPUT_IDS = setOf(DecisionSpec.INCOME, DecisionSpec.SPENDING, DecisionSpec.START, DecisionSpec.DURATION)

/** Why this spec can't be simulated yet, in plain words. Empty = ready. */
fun DecisionSpec.problems(): List<String> = buildList {
    if (title.isBlank()) add("The decision needs a name.")
    if (horizonMonths !in 1..60) add("Look between 1 and 60 months ahead.")
    if (startMonth !in 0 until horizonMonths) add("The start has to be inside the $horizonMonths months looked at.")
    durationMonths?.let { if (it < 1) add("The duration has to be at least one month.") }
    if (changes.map { it.id }.distinct().size != changes.size) add("Two changes have the same id.")
    changes.filter { it.amount == null }.forEach { add("How much is “${it.label}”?") }
    changes.filter { it.timing == ChangeTiming.WHILE_IT_LASTS && durationMonths == null }
        .forEach { add("“${it.label}” only lasts while the decision does: how long is that?") }
    situation.plannedBills.filter { it.month !in 0 until horizonMonths }.forEach { add("“${it.label}” is outside the period looked at.") }
    val income = situation.monthlyIncome.smallest
    val lost = changes.filter { it.kind == ChangeKind.LESS_INCOME }.mapNotNull { it.amount?.largest }.fold(Chf.ZERO, Chf::plus)
    if (lost > income) add("The pay you'd lose (${lost.format()}) is more than your pay (${income.format()}).")
    val cuttable = situation.monthlySpending.smallest - situation.cannotCut
    if (cuttable.isNegative) add("What you can't cut is more than your monthly spending.")
    val saved = changes.filter { it.kind == ChangeKind.LESS_SPENDING }.mapNotNull { it.amount?.largest }.fold(Chf.ZERO, Chf::plus)
    if (saved > cuttable.coerceAtLeast(Chf.ZERO)) add("You'd save more than the spending you can cut.")
    if (changes.size > 8) add("Keep it to 8 changes or fewer.")
}

/** Builds the engine session. Throws if [problems] is not empty. */
fun DecisionSpec.toSession(): DecisionSession {
    problems().let { require(it.isEmpty()) { it.joinToString(" ") } }
    val amountInputs = buildList {
        add(DecisionSpec.INCOME to situation.monthlyIncome)
        add(DecisionSpec.SPENDING to situation.monthlySpending)
        changes.forEach { add(it.id to it.amount!!) }
    }
    val ranges = amountInputs.count { it.second is Amount.Between }
    val labels = mapOf(DecisionSpec.INCOME to "Your monthly pay", DecisionSpec.SPENDING to "Your monthly spending") + changes.associate { it.id to it.label }
    val inputs = amountInputs.map { (id, a) -> moneyInput(id, labels.getValue(id), a, ranges, if (id == DecisionSpec.SPENDING) situation.cannotCut else Chf.ZERO) } +
        listOfNotNull(
            DecisionInput(DecisionSpec.START, "When it starts", Chf.ZERO, Chf.francs(horizonMonths - 1L), Chf.francs(1), unit = InputUnit.MONTH_INDEX),
            durationMonths?.let { DecisionInput(DecisionSpec.DURATION, "How long it lasts", Chf.francs(1), Chf.francs(horizonMonths.toLong()), Chf.francs(1), unit = InputUnit.MONTH_COUNT) },
        )
    val values = amountInputs.associate { (id, a) -> id to a.state() } +
        listOfNotNull(
            DecisionSpec.START to InputState.Exact(Chf.francs(startMonth.toLong())),
            durationMonths?.let { DecisionSpec.DURATION to InputState.Exact(Chf.francs(it.toLong())) },
        )
    val stayLabel = "Keeping things as they are"
    val options = buildList {
        add(DecisionOption(OPTION_STAY, stayLabel, DecisionKind.STAY_CURRENT, listOf("assumption.cash_only"),
            intention = "What if I keep things as they are?", category = category) { v -> current(v) })
        if (laterBy > 0 && startMonth + laterBy < horizonMonths) add(DecisionOption(
            OPTION_LATER, "The same, $laterBy months later", DecisionKind.DELAY_DECISION, listOf("assumption.cash_only"),
            intention = "What if I did it $laterBy months later?", category = category,
        ) { v -> decisionScenario(v, shift = laterBy) })
    }
    val cuttable = situation.monthlySpending.smallest - situation.cannotCut
    val initial = values.mapValues { (_, s) -> when (s) { is InputState.Exact -> s.value; is InputState.Range -> s.low; InputState.Unknown -> Chf.ZERO } }
    return DecisionSession(
        profile = FinancialProfile(personName, situation.cash, horizonMonths, fictional),
        baseline = current(initial),
        inputs = inputs,
        values = values,
        askedInputIds = values.filterValues { it is InputState.Exact }.keys,
        stress = situation.lowerPay,
        floor = cushion ?: Chf.ZERO,
        constraints = PersonalConstraints(minCashBuffer = cushion),
        // Routes may suggest trimming flexible spending, never below what the person can't cut.
        repairCap = cuttable,
        options = options,
        decisionLabelKey = title,
        decisionAssumptionKeys = listOf("assumption.cash_only"),
        decisionIntention = intention.ifBlank { title },
        decisionCategory = category,
        decisionCoverage = coverage,
        baselineFor = { v -> current(v) },
    ) { v -> decisionScenario(v) }
}

/** The path without the decision, for given input values. */
fun DecisionSpec.current(v: Map<String, Chf>): Scenario = Scenario(
    "Keeping things as they are",
    listOf(Phase(0, horizonMonths, v.getValue(DecisionSpec.INCOME), v.getValue(DecisionSpec.SPENDING), situation.cannotCut)),
    oneOffCosts = situation.plannedBills,
)

/** Changes that fall after the period looked at for these values (shown to the person, never dropped silently). */
fun DecisionSpec.outsideHorizon(v: Map<String, Chf>, shift: Int = 0): List<FinancialChange> {
    val start = v.getValue(DecisionSpec.START).wholeUnits + shift
    return changes.filter { start + it.monthOffset >= horizonMonths }
}

fun DecisionSpec.decisionScenario(v: Map<String, Chf>, shift: Int = 0): Scenario {
    val start = v.getValue(DecisionSpec.START).wholeUnits + shift
    val end = durationMonths?.let { minOf(start + v.getValue(DecisionSpec.DURATION).wholeUnits, horizonMonths) } ?: horizonMonths
    val inHorizon = changes.filter { start + it.monthOffset < horizonMonths }
    fun provenance(c: FinancialChange) = when {
        c.source != null -> Provenance.EXTERNAL_SOURCE
        c.amount is Amount.Known -> Provenance.USER_STATED
        else -> Provenance.USER_ESTIMATE_RANGE
    }
    val recurring = inHorizon.filter { it.kind.recurring }.mapNotNull { c ->
        val from = start + c.monthOffset
        val to = if (c.timing == ChangeTiming.WHILE_IT_LASTS) end else horizonMonths
        val a = v.getValue(c.id)
        if (from >= to || a == Chf.ZERO) null else RecurringCashFlowChange(
            c.label, from, to,
            monthlyIncomeDelta = when (c.kind) { ChangeKind.LESS_INCOME -> -a; ChangeKind.MORE_INCOME -> a; else -> Chf.ZERO },
            monthlyExpenseDelta = when (c.kind) { ChangeKind.MORE_SPENDING -> a; ChangeKind.LESS_SPENDING -> -a; else -> Chf.ZERO },
            provenance = provenance(c), evidenceRef = c.sourceRef ?: "input:${c.id}",
        )
    }
    return current(v).withConsequences(horizonMonths, FinancialConsequences(
        recurring = recurring,
        oneOffCosts = inHorizon.filter { it.kind == ChangeKind.ONE_OFF_COST }
            .map { OneOffCost(start + it.monthOffset, v.getValue(it.id), it.label, provenance(it), "input:${it.id}") },
        restrictedAssetTransfers = inHorizon.filter { it.kind == ChangeKind.REFUNDABLE_DEPOSIT }
            .map { AssetTransfer(start + it.monthOffset, v.getValue(it.id), it.label, provenance(it), "input:${it.id}") },
    )).copy(name = title)
}

private fun Amount.state(): InputState = when (this) {
    is Amount.Known -> InputState.Exact(value)
    is Amount.Estimate -> InputState.Range(value, value)
    is Amount.Between -> InputState.Range(low, high)
}

/**
 * Bounds for one money input. A range keeps its own bounds (searched exhaustively, step chosen
 * so all ranges together stay under [MAX_VALUE_COMBINATIONS]). A single figure gets an
 * exploration span 0..max(2×value, value+500) for what-ifs and "where does it turn?"; it is never
 * searched unless the person asks where it turns.
 */
private fun moneyInput(id: String, label: String, a: Amount, ranges: Int, floorAt: Chf): DecisionInput = when (a) {
    is Amount.Between -> {
        val valuesPerInput = generateSequence(2) { it + 1 }.takeWhile { pow(it, ranges) <= MAX_VALUE_COMBINATIONS }.lastOrNull() ?: 2
        DecisionInput(id, label, a.low, a.high, stepFor(a.high - a.low, minOf(valuesPerInput - 1, 400)))
    }
    else -> {
        val v = a.largest
        val step = Chf.francs(if (v < Chf.francs(2_000)) 10 else if (v < Chf.francs(10_000)) 50 else 100)
        val low = Chf(ceilTo(floorAt.rappen, step.rappen))
        val high = Chf(ceilTo(maxOf(v.rappen * 2, v.rappen + 50_000, low.rappen + step.rappen), step.rappen))
        DecisionInput(id, label, low, high, step)
    }
}

/** Smallest whole-franc step that splits [span] into at most [maxSteps] equal parts (one step if none). */
private fun stepFor(span: Chf, maxSteps: Int): Chf {
    if (span == Chf.ZERO) return Chf.francs(1)
    val n = (minOf(maxSteps.toLong(), span.rappen / 100) downTo 1L).firstOrNull { span.rappen % it == 0L && (span.rappen / it) % 100 == 0L }
    return n?.let { Chf(span.rappen / it) } ?: span
}

private fun ceilTo(x: Long, step: Long) = (x + step - 1) / step * step
private fun pow(b: Int, e: Int): Long = (1..e).fold(1L) { acc, _ -> acc * b }

/**
 * Where an input's answer turns, computed by the engine as if that input were unknown within its
 * bounds, with every other input held at its hardest tested value ([others], default: this
 * session's worst case) so the search stays one-dimensional.
 */
fun DecisionSession.whereItTurns(inputId: String, others: Map<String, Chf>? = null): UnknownImpact? {
    val input = inputs.first { it.id == inputId }
    if (input.low == input.high) return null
    val held = (others ?: brief(this).evidence.worstCaseInputs) - inputId
    val probe = copy(values = values - inputId, hypotheticalValues = hypotheticalValues - inputId + held.filterKeys { it !in marketAssumptions })
    return brief(probe).evidence.unknownImpacts.firstOrNull { it.name == inputId }
}

const val OPTION_STAY = "stay"
const val OPTION_LATER = "later"

/* ============================== Interpreting a question ============================== */

/**
 * A proposed structure for the person's question: which kinds of change it usually brings, with
 * NO amounts (except a figure the person typed). The person confirms or edits every field before
 * anything is simulated. An LLM may produce the same structure; it must pass [DecisionSpec.problems].
 */
data class DecisionDraft(
    val intention: String,
    val title: String,
    val category: String?,
    val changes: List<FinancialChange>,
    val startMonth: Int?,
    val durationMonths: Int?,
    val needsDuration: Boolean,
    val coverage: ModelCoverage,
    /** True when a known pattern matched; false = the person adds the changes themselves. */
    val recognized: Boolean,
    /** A place the person named ("in Amsterdam"); only used to say what research can cover. */
    val location: String? = null,
)

/** Deterministic, offline pattern matcher (English). Never invents amounts. */
fun interpretDecision(question: String): DecisionDraft {
    val t = question.lowercase().replace('’', '\'')
    // A figure the person typed (largest number, ignoring percentages and small counts like "6 months").
    val stated = Regex("""(\d{1,3}(?:[,']\d{3})+|\d+)(?![\d,'])(?!\s*%)""").findAll(t)
        .map { it.groupValues[1].filter(Char::isDigit).toLong() }.filter { it >= 50 }.maxOrNull()?.let(Chf::francs)
    fun has(vararg w: String) = w.any { Regex("""(^|\W)""" + Regex.escape(it)).containsMatchIn(t) }
    fun change(id: String, label: String, kind: ChangeKind, timing: ChangeTiming = ChangeTiming.FROM_THEN_ON, amount: Amount? = null) =
        FinancialChange(id, label, kind, amount, timing)
    val w = ChangeTiming.WHILE_IT_LASTS
    val template: Triple<String, String, List<FinancialChange>>? = when {
        has("study abroad", "studying abroad", "studied abroad", "study in", "studying in", "studied in", "exchange", "erasmus", "semester abroad") -> Triple("Studying abroad", "education", listOf(
            change("lost_pay", "Pay you'd give up while away", ChangeKind.LESS_INCOME, w),
            change("living_abroad", "Extra living costs abroad", ChangeKind.MORE_SPENDING, w),
            change("travel", "Travel and visa", ChangeKind.ONE_OFF_COST),
            change("deposit_abroad", "Housing deposit abroad", ChangeKind.REFUNDABLE_DEPOSIT),
        ))
        has("fewer hours", "reduce my hours", "reducing my hours", "reduce working hours", "reduce hours", "part-time", "part time", "80%", "60%", "work less") ->
            Triple("Working fewer hours", "work", listOf(
                change("lost_pay", "Pay you'd give up", ChangeKind.LESS_INCOME),
                change("savings", "Costs you'd save (childcare, commuting…)", ChangeKind.LESS_SPENDING),
            ))
        has("career break", "sabbatical", "time off", "break from work", "stop working") -> Triple("Taking a career break", "work", listOf(
            change("lost_pay", "Pay you'd give up", ChangeKind.LESS_INCOME, w),
        ))
        has("new job", "change job", "changing job", "switch job", "job offer") -> Triple("Changing jobs", "work", listOf(
            change("pay_change", "Change in monthly pay", ChangeKind.MORE_INCOME),
        ))
        Regex("""\bcar\b""").containsMatchIn(t) -> Triple("Buying a car", "transport", listOf(
            change("price", "Purchase price", ChangeKind.ONE_OFF_COST, amount = stated?.let(Amount::Known)),
            change("running", "Running costs (insurance, fuel, parking)", ChangeKind.MORE_SPENDING),
        ))
        has("support my", "help my", "my parents", "my mum", "my mom", "my dad", "my family", "my sister", "my brother") ->
            Triple("Supporting family", "family", listOf(change("support", "Monthly support", ChangeKind.MORE_SPENDING, amount = stated?.let(Amount::Known))))
        has("move out", "moving out", "own flat", "own apartment", "move to", "moving to", "relocat", "new flat", "new apartment") ->
            Triple("Moving", "housing", listOf(
                change("rent", "Rent at the new place (more than you pay now)", ChangeKind.MORE_SPENDING),
                change("moving", "Moving costs", ChangeKind.ONE_OFF_COST),
                change("deposit", "Rental deposit", ChangeKind.REFUNDABLE_DEPOSIT),
            ))
        has("wedding", "holiday", "trip", "laptop", "buy a", "pay for", "expense", "renovat") ->
            Triple("A big expense", "expense", listOf(change("cost", "The cost", ChangeKind.ONE_OFF_COST, amount = stated?.let(Amount::Known))))
        else -> null
    }
    val duration = durationIn(t)
    val needsDuration = template?.third?.any { it.timing == w } == true
    val domains = buildSet {
        add(FinancialDomain.CASH_FLOW)
        if (has("pension", "retire", "ahv", "avs", "3a")) add(FinancialDomain.PENSION)
        if (has("tax")) add(FinancialDomain.TAX)
        if (has("invest", "stock", "shares", "etf", "crypto")) add(FinancialDomain.INVESTMENT)
        if (has("loan", "mortgage", "debt", "credit", "leasing", "lease a")) add(FinancialDomain.DEBT)
    }
    return DecisionDraft(
        intention = question.trim(),
        title = template?.first ?: question.trim().removePrefix("What happens if").removePrefix("what happens if").trim().trimEnd('?').replaceFirstChar(Char::uppercase).ifBlank { "My decision" },
        category = template?.second,
        changes = template?.third.orEmpty(),
        startMonth = startIn(t),
        durationMonths = duration,
        needsDuration = needsDuration,
        coverage = ModelCoverage(domains),
        recognized = template != null,
        location = placeIn(question),
    )
}

private val MONTHS = setOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")

/** "in Amsterdam", "to Lausanne": a capitalised place the person typed (never inferred). */
internal fun placeIn(question: String): String? =
    Regex("""\b(?:in|to)\s+([A-Z][\p{L}-]+(?:\s[A-Z][\p{L}-]+)?)""").findAll(question)
        .map { it.groupValues[1] }.firstOrNull { it.lowercase() !in MONTHS && it != "I" }

private val NUMBER_WORDS = mapOf("one" to 1, "a" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
    "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12)

private fun count(word: String): Int? = word.toIntOrNull() ?: NUMBER_WORDS[word]

/** "for six months", "for 2 years", "for a year". A semester is not assumed to be any number of months. */
private fun durationIn(t: String): Int? {
    val m = Regex("""for (\w+) (month|year)s?""").find(t) ?: return null
    val n = count(m.groupValues[1]) ?: return null
    return if (m.groupValues[2] == "year") n * 12 else n
}

/** "in three months", "in 2 months" → month index; "now"/nothing → null (asked). */
private fun startIn(t: String): Int? {
    val m = Regex("""in (\w+) months?""").find(t) ?: return null
    return count(m.groupValues[1])
}

/** Turns a confirmed draft into a spec. The person supplies every amount. */
fun DecisionDraft.toSpec(situation: Situation, changes: List<FinancialChange> = this.changes, startMonth: Int = this.startMonth ?: 0,
                         durationMonths: Int? = this.durationMonths, cushion: Chf? = null, horizonMonths: Int = 12) = DecisionSpec(
    intention = intention, title = title, situation = situation, changes = changes, startMonth = startMonth,
    durationMonths = durationMonths, horizonMonths = horizonMonths, cushion = cushion, category = category, coverage = coverage,
)

/* ============================== Fictional examples ============================== */

/** Our own fictional examples (not UBS data), built only from the generic primitives. */
object ExampleDecisions {
    /** Sam, a student with a side job, considers a six-month exchange starting in March. */
    val samStudyAbroad = DecisionSpec(
        intention = "What happens if I study abroad for six months?",
        title = "Studying abroad",
        personName = "Sam",
        fictional = true,
        situation = Situation(
            cash = Chf.francs(9_000),
            monthlyIncome = Amount.Known(Chf.francs(2_400)),
            monthlySpending = Amount.Known(Chf.francs(1_900)),
        ),
        changes = listOf(
            FinancialChange("lost_pay", "Side-job pay you'd give up while away", ChangeKind.LESS_INCOME, Amount.Known(Chf.francs(1_200)), ChangeTiming.WHILE_IT_LASTS),
            FinancialChange("living_abroad", "Extra living costs abroad", ChangeKind.MORE_SPENDING, Amount.Between(Chf.francs(300), Chf.francs(700)), ChangeTiming.WHILE_IT_LASTS),
            FinancialChange("travel", "Flights and visa", ChangeKind.ONE_OFF_COST, Amount.Estimate(Chf.francs(900))),
            FinancialChange("deposit_abroad", "Housing deposit abroad", ChangeKind.REFUNDABLE_DEPOSIT, Amount.Known(Chf.francs(1_000))),
        ),
        startMonth = 2,
        durationMonths = 6,
        cushion = Chf.francs(1_000),
        category = "education",
    )

    /** Noor considers dropping to 80% from April; childcare savings are uncertain. */
    val noorFewerHours = DecisionSpec(
        intention = "What happens if I reduce my working hours to 80%?",
        title = "Working fewer hours",
        personName = "Noor",
        fictional = true,
        situation = Situation(
            cash = Chf.francs(2_500),
            monthlyIncome = Amount.Known(Chf.francs(5_000)),
            monthlySpending = Amount.Known(Chf.francs(4_600)),
            cannotCut = Chf.francs(2_400),
            cannotCutLabel = "Rent and support for my parents",
            plannedBills = listOf(OneOffCost(6, Chf.francs(1_500), "Yearly insurance", Provenance.USER_STATED)),
        ),
        changes = listOf(
            FinancialChange("lost_pay", "Pay you'd give up", ChangeKind.LESS_INCOME, Amount.Known(Chf.francs(1_000))),
            FinancialChange("childcare", "Childcare you'd save", ChangeKind.LESS_SPENDING, Amount.Between(Chf.francs(200), Chf.francs(600))),
        ),
        startMonth = 3,
        cushion = Chf.francs(1_500),
        category = "work",
    )
}


/* ============================== Local AI reading (validated) ============================== */

/**
 * Instructions for a local model (Ollama). It may only propose STRUCTURE: a title, a place the
 * person named and the kinds of money change. Any amount or date it returns is ignored.
 */
const val DECISION_READER_PROMPT = """You turn a person's question about a life decision into JSON describing which kinds of money would change.
Return exactly: {"title": string (2-5 words), "location": string or null (only a place named in the question), "changes": [{"label": string (plain words, max 8 words), "kind": one of "LESS_INCOME","MORE_INCOME","MORE_SPENDING","LESS_SPENDING","ONE_OFF_COST","REFUNDABLE_DEPOSIT", "while_it_lasts": boolean}]}.
List at most 5 changes that this decision usually brings. Never include amounts, prices, dates, advice or sources."""

/** What a local model's reading adds after validation. Nothing here is a number. */
data class AiReading(
    val title: String?,
    val location: String?,
    /** Changes of a kind the built-in reading did not already list (amount always null). */
    val extraChanges: List<FinancialChange>,
    /** Proposed items dropped by validation (bad kind, blank or long label, duplicate kind, place not in the question). */
    val dropped: Int,
)

/** Validates a model's JSON against the question and the built-in reading. Never throws. */
fun readAiProposal(question: String, builtIn: DecisionDraft, json: String): AiReading {
    val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return AiReading(null, null, emptyList(), 1)
    fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    var dropped = 0
    val title = str(root, "title")?.takeIf { it.length <= 40 && !it.any(Char::isDigit) }
    val place = str(root, "location")?.let { l -> if (question.contains(l, ignoreCase = true)) l else { dropped++; null } }
    val seen = builtIn.changes.map { it.kind }.toMutableSet()
    val extra = mutableListOf<FinancialChange>()
    (root["changes"] as? JsonArray).orEmpty().forEach { e ->
        val o = e as? JsonObject
        val kind = o?.let { str(it, "kind") }?.let { k -> ChangeKind.entries.firstOrNull { it.name == k } }
        val label = o?.let { str(it, "label") }?.takeIf { it.length in 3..60 && !it.any(Char::isDigit) }
        if (kind == null || label == null || kind in seen || extra.size >= 3) { dropped++; return@forEach }
        seen += kind
        val whileItLasts = (o["while_it_lasts"] as? JsonPrimitive)?.contentOrNull == "true" && kind.recurring
        extra += FinancialChange("ai_${extra.size + 1}", label.replaceFirstChar(Char::uppercase), kind, null,
            if (whileItLasts) ChangeTiming.WHILE_IT_LASTS else ChangeTiming.FROM_THEN_ON)
    }
    return AiReading(title, place, extra, dropped)
}

/* ============================== Research Routes can verify ============================== */

/** Areas the research validator recognises (its gazetteer); anything else is never guessed. */
fun researchArea(place: String?): GeoScope? = when (place?.trim()?.lowercase()) {
    "lausanne" -> GeoScope.LAUSANNE
    "vaud", "canton of vaud" -> GeoScope.VD
    "switzerland", "suisse", "schweiz" -> GeoScope.CH
    else -> null
}

/** Net rent (without charges) for a new lease in a covered area; null = Routes can't research it. */
fun rentResearchRequest(place: String?, rooms: Int, asOfYear: Int): ResearchRequest? {
    val scope = researchArea(place) ?: return null
    val q = MarketQuery(MarketCategory.HOUSING_NET_RENT, MarketUnit.CHF_PER_MONTH, scope,
        HousingCharacteristics(rooms, tenancy = TenancyDuration.UP_TO_ONE_YEAR, includesCharges = false), asOfYear)
    return ResearchRequest("rent@${scope.code}:$rooms", DecisionKind.MOVE_OUT, q, null, PriorityBasis.HEURISTIC, null, emptyList())
}
