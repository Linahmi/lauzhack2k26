package ch.ripple.app.phone

import ch.ripple.app.Cause
import ch.ripple.app.KeyMoment
import ch.ripple.app.causes
import ch.ripple.app.keyMoment
import ch.ripple.core.*

/*
 * Words in, typed commands out; engine results in, plain sentences out. Deterministic and
 * offline. It never fills in a number the person didn't say and never computes money: every
 * figure in a sentence is read from an engine result.
 */

/** Same as the desktop's formatting (kept separate: the desktop's helpers are jvm-only). */
fun Chf.money(): String {
    val abs = kotlin.math.abs(rappen)
    val francs = (abs / 100).toString().reversed().chunked(3).joinToString("'").reversed()
    val cents = if (abs % 100 == 0L) "" else "." + (abs % 100).toString().padStart(2, '0')
    return (if (rappen < 0) "−" else "") + "CHF " + francs + cents
}

/** Decisions have no calendar dates; month 1 is shown as January (said in the math sheet). */
fun monthName(i: Int): String {
    val n = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")[i % 12]
    return if (i < 12) n else "$n, year ${i / 12 + 1}"
}

fun RoutesDecision.inputLabel(id: String): String = when (id) {
    DecisionSpec.INCOME -> "Monthly pay"
    DecisionSpec.SPENDING -> "Monthly spending"
    DecisionSpec.START -> "When it starts"
    DecisionSpec.DURATION -> "How long it lasts"
    else -> spec.changes.firstOrNull { it.id == id }?.label ?: id
}

fun DecisionInput.show(v: Chf): String = when (unit) {
    InputUnit.MONTH_INDEX -> monthName(v.wholeUnits)
    InputUnit.MONTH_COUNT -> "${v.wholeUnits} months"
    InputUnit.CHF -> v.money()
}

fun InputState.show(input: DecisionInput): String = when (this) {
    is InputState.Exact -> input.show(value)
    is InputState.Range -> if (low == high) "about ${input.show(low)}" else "${input.show(low)} to ${input.show(high)}"
    InputState.Unknown -> "not known (${input.show(input.low)} to ${input.show(input.high)} tested)"
}

fun Amount.show(): String = when (this) {
    is Amount.Known -> value.money()
    is Amount.Estimate -> "about ${value.money()}"
    is Amount.Between -> "${low.money()} to ${high.money()}"
}

fun RoutesDecision.optionLabel(id: String?): String = when (id) {
    null -> spec.title
    else -> graph.active.session.options.firstOrNull { it.id == id }?.labelKey ?: id
}

fun RoutesDecision.hasCushion(session: DecisionSession = graph.active.session) = session.effectiveFloor > Chf.ZERO

/** The one finding for a version: its lowest month in the hardest case the engine tested. */
fun findingText(d: RoutesDecision, k: KeyMoment, session: DecisionSession = d.graph.active.session): String {
    val month = monthName(k.month)
    return when {
        k.moneyLeft.isNegative -> "In the hardest case tested, money runs out: $month ends at ${k.moneyLeft.money()}."
        d.hasCushion(session) && k.belowCushion -> "$month could be tight: ${k.moneyLeft.money()} left in the hardest case tested, below your ${k.cushion.money()} cushion."
        d.hasCushion(session) -> "Even in the hardest case tested, the lowest point is $month with ${k.moneyLeft.money()}, above your ${k.cushion.money()} cushion."
        else -> "In the hardest case tested, the lowest point is $month with ${k.moneyLeft.money()} left."
    }
}

fun changeText(before: KeyMoment, after: KeyMoment): String =
    if (before.month == after.month) "Lowest point, ${monthName(after.month)}: ${before.moneyLeft.money()} → ${after.moneyLeft.money()}."
    else "Lowest point moves from ${monthName(before.month)} (${before.moneyLeft.money()}) to ${monthName(after.month)} (${after.moneyLeft.money()})."

fun describe(d: RoutesDecision, h: Hypothesis): String = when (h) {
    is Hypothesis.WhatIfInput -> {
        val input = d.graph.active.session.inputs.first { it.id == h.inputId }
        "${d.inputLabel(h.inputId)} ${input.show(h.value)}"
    }
    is Hypothesis.TryOption -> d.optionLabel(h.optionId)
    is Hypothesis.ApplyCut -> "spending ${h.cut.monthly.money()} less per month from ${monthName(h.cut.startMonth)}"
    is Hypothesis.UseMarketEstimate -> "${d.inputLabel(h.inputId)} at a market estimate"
    is Hypothesis.UseResearchedEstimate -> "${d.inputLabel(h.inputId)} at a researched estimate"
}

fun describe(d: RoutesDecision, c: Correction): String = when (c) {
    is Correction.AnswerInput -> {
        val input = d.graph.active.session.inputs.first { it.id == c.inputId }
        "${d.inputLabel(c.inputId)} is ${c.value.show(input)}"
    }
    is Correction.SetMinCashBuffer -> "keep at least ${c.amount.money()}"
    is Correction.ClearMinCashBuffer -> "no personal cushion"
    is Correction.SetMaxMonthlyReduction -> "cut at most ${c.amount.money()} a month"
    is Correction.ClearMaxMonthlyReduction -> "no limit on cuts"
    is Correction.RejectRepair -> when (c.scope) {
        RejectionScope.ANY_SPENDING_REDUCTION -> "no spending cuts"
        else -> "not cutting ${c.action.monthly.money()} a month or more"
    }
    is Correction.RemoveRejection -> "spending cuts allowed again"
    is Correction.SetCommitment -> "${c.commitment.labelKey}: ${c.commitment.monthly.money()} a month"
    is Correction.RemoveCommitment -> "commitment removed"
    is Correction.RejectMarketEstimate -> "market estimate rejected"
}

fun causeText(c: Cause, d: RoutesDecision): String = when (c) {
    is Cause.LowerPay -> "Pay ${c.reduction.money()} lower in ${c.months.joinToString { monthName(it) }} (tested assumption)"
    is Cause.SetAside -> "${c.label}: ${c.amount.money()} set aside in ${monthName(c.month)} (still yours, not spendable)"
    is Cause.OneOff -> "${c.label}: ${c.amount.money()} in ${monthName(c.month)}"
    is Cause.Commitment -> "${c.label}: ${c.monthly.money()} a month"
    is Cause.Change -> "${c.label}: " + (if (c.expenseDelta.rappen != 0L) "${c.expenseDelta.money()} more spending" else "${c.incomeDelta.money()} pay") + " a month from ${monthName(c.fromMonth)}"
    is Cause.Input -> "${d.inputLabel(c.input.id)}: " + (c.whatIf?.let { "${c.input.show(it)} (what-if)" } ?: c.state.show(c.input)) +
        (c.testedValue?.takeIf { c.state !is InputState.Exact && c.whatIf == null }?.let { " · hardest value ${c.input.show(it)}" } ?: "")
}

fun explainText(d: RoutesDecision): String {
    val b = d.graph.briefing()
    val k = keyMoment(b)
    // Recurring changes are listed separately below, so they are left out here (no duplicates).
    val why = causes(d.graph.active.session, b, k.month).filterNot { it is Cause.Change || (it is Cause.Input && it.input.unit != InputUnit.CHF) }
        .filter { it !is Cause.Input || it.state !is InputState.Exact || it.whatIf != null }
        .joinToString("; ") { causeText(it, d) }
    val s = d.graph.active.session
    val recurring = s.scenarioFor(b.evidence.worstCaseInputs).recurringChanges.filter { it.startMonth <= k.month }.joinToString("; ") {
        "${it.labelKey}: " + (if (it.monthlyExpenseDelta.rappen != 0L) "${it.monthlyExpenseDelta.money()} more spending" else "${it.monthlyIncomeDelta.money()} pay") + " a month from ${monthName(it.startMonth)}"
    }
    val all = listOf(recurring, why).filter { it.isNotEmpty() }.joinToString("; ")
    val prefix = if (d.graph.active.hypothetical) "In the what-if you're looking at (not your confirmed situation): " else ""
    return prefix + findingText(d, k) + if (all.isNotEmpty()) " What drives it: $all." else ""
}

/** Each alternative, computed now by the engine on a copy (no version is added). */
fun alternatives(d: RoutesDecision): List<Pair<String, KeyMoment>> {
    val s = d.graph.active.session
    val current = (s.activeOptionId?.let { d.optionLabel(it) } ?: d.spec.title) to keyMoment(d.graph.briefing())
    return listOf(current) + s.options.filter { it.id != s.activeOptionId }.map { o ->
        o.labelKey to keyMoment(brief(s.apply(Hypothesis.TryOption(o.id))))
    }
}

fun compareText(d: RoutesDecision): String =
    alternatives(d).joinToString(" · ") { (label, k) -> "$label: lowest ${k.moneyLeft.money()} in ${monthName(k.month)}" } +
        ". Open My Routes to explore one."

private val STOP = setOf("what", "with", "your", "you'd", "while", "costs", "cost", "change", "monthly", "month", "away", "that", "this", "from", "more", "less", "higher", "lower", "extra")

/** Which input of [d] the person is talking about, by id or a distinctive word of its label. */
fun RoutesDecision.findInput(t: String): DecisionInput? {
    val inputs = graph.active.session.inputs.filter { it.unit == InputUnit.CHF }
    val words = Regex("""[a-z]+""").findAll(t).map { it.value }.toSet()
    fun keys(i: DecisionInput) = (listOf(i.id) + i.id.split('_') +
        Regex("""[a-z]+""").findAll(inputLabel(i.id).lowercase()).map { it.value }).filter { it.length >= 4 && it !in STOP } +
        when (i.id) {
            DecisionSpec.INCOME -> listOf("pay", "salary", "income", "wage")
            DecisionSpec.SPENDING -> listOf("spending", "spend", "expenses")
            else -> emptyList()
        }
    return inputs.firstOrNull { i -> keys(i).any { it in words } }
}

/**
 * The typed command for one utterance about decision [d], or null when it isn't about [d].
 * Changes to facts come back as Correct (the app asks before saving); what-ifs as Explore.
 */
fun interpretFor(d: RoutesDecision, raw: String): TwinCommand? {
    val t = raw.lowercase().replace('’', '\'')
    val s = d.graph.active.session
    val whatIf = Regex("""\b(what if|what about|try|suppose|imagine)\b""").containsMatchIn(t)
    // Timing: "what if I waited two months", "start in March" -> the decision's own start input.
    if (Regex("""\b(wait|waited|waiting|delay|delayed|postpone|later)\b""").containsMatchIn(t)) {
        val n = Regex("""(\d+|\w+)\s+months?""").find(t)?.groupValues?.get(1)?.let { numbersIn(it, words = true).firstOrNull()?.wholeUnits }
        val start = s.inputs.firstOrNull { it.id == DecisionSpec.START }
        if (n != null && start != null) {
            val current = (s.hypotheticalValues[start.id] ?: (s.state(start.id) as? InputState.Exact)?.value ?: start.low).wholeUnits
            val target = current + n
            if (target <= start.high.wholeUnits) return TwinCommand.Explore(Hypothesis.WhatIfInput(start.id, Chf.francs(target.toLong())))
        }
        s.options.firstOrNull { it.kind == DecisionKind.DELAY_DECISION }?.let { return TwinCommand.Explore(Hypothesis.TryOption(it.id)) }
    }
    if (t.startsWith("why") || " why " in " $t ") return TwinCommand.Explain
    if (Regex("""\b(compare|alternatives?|options|other (paths|routes))\b""").containsMatchIn(t)) return TwinCommand.ShowTradeOffs
    if (Regex("""\b(can't|cannot|can not|won't|unable to)\s+(reduce|cut|lower)""").containsMatchIn(t)) return d.graph.parseCommand(raw)
    val nums = numbersIn(t)
    val input = d.findInput(t)
    if (input != null && nums.isNotEmpty()) {
        if (whatIf) return TwinCommand.Explore(Hypothesis.WhatIfInput(input.id, nums[0]))
        val state = if (nums.size >= 2) InputState.Range(minOf(nums[0], nums[1]), maxOf(nums[0], nums[1])) else InputState.Exact(nums[0])
        return TwinCommand.Correct(Correction.AnswerInput(input.id, state))
    }
    if (input != null && Regex("""\b(not sure|don't know|no idea)\b""").containsMatchIn(t)) return TwinCommand.Correct(Correction.AnswerInput(input.id, InputState.Unknown))
    if (Regex("""\b(cushion|buffer|keep at least|always keep)\b""").containsMatchIn(t) && nums.isNotEmpty()) return TwinCommand.Correct(Correction.SetMinCashBuffer(nums[0]))
    if (whatIf) {
        if (Regex("""\b(stay|stayed|keep things|didn't|did not|don't|do not)\b""").containsMatchIn(t))
            s.options.firstOrNull { it.kind == DecisionKind.STAY_CURRENT }?.let { return TwinCommand.Explore(Hypothesis.TryOption(it.id)) }
    }
    return null
}
