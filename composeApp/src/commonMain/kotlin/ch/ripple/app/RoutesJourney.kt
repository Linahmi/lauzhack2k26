package ch.ripple.app

import ch.ripple.core.Briefing
import ch.ripple.core.Chf
import ch.ripple.core.DecisionInput
import ch.ripple.core.DecisionSession
import ch.ripple.core.Hypothesis
import ch.ripple.core.InputState
import ch.ripple.core.MonthRecord
import ch.ripple.core.UnknownImpact
import ch.ripple.core.apply
import ch.ripple.core.brief

/*
 * Presentation helpers shared by every Routes screen. They only SELECT from engine results
 * (brief(), stress-test witnesses, the engine's own scenario); they never compute money.
 */

/** The month a finding is about: the lowest month-end cash in the hardest case the engine tested. */
data class KeyMoment(
    val month: Int,
    /** Month records of that hardest tested case. */
    val months: List<MonthRecord>,
    val moneyLeft: Chf,
    val cushion: Chf,
    /** Months with lower pay in that hardest case. */
    val lowerPayMonths: List<Int>,
    val monthsBelowCushion: List<Int>,
) {
    val belowCushion: Boolean get() = moneyLeft < cushion
    /** First month that ends below zero in the hardest tested case (null: never). */
    val firstShort: Int? get() = months.firstOrNull { it.closingCash.isNegative }?.month
    /** The route drawn on screen: month-end cash in the hardest tested case. */
    val route: List<Chf> get() = months.map { it.closingCash }
}

fun keyMoment(b: Briefing): KeyMoment {
    val test = b.evidence.worstStressTest
    val months = test.worst.result.months
    val m = months.indices.minBy { months[it].closingCash } // first lowest month
    return KeyMoment(
        month = m,
        months = months,
        moneyLeft = months[m].closingCash,
        cushion = test.floor,
        lowerPayMonths = test.worst.lowIncomeMonths,
        monthsBelowCushion = months.filter { it.closingCash < test.floor }.map { it.month },
    )
}

/** What led to a month's result, taken from the engine's scenario and hardest tested case. */
sealed interface Cause {
    data class LowerPay(val months: List<Int>, val reduction: Chf) : Cause
    /** [inputId]: the decision input behind the amount, when the scenario says so (provenance ref). */
    data class SetAside(val month: Int, val amount: Chf, val label: String, val inputId: String? = null) : Cause
    data class OneOff(val month: Int, val amount: Chf, val label: String, val inputId: String? = null) : Cause
    data class Commitment(val label: String, val monthly: Chf) : Cause
    /** A recurring change the decision brings (e.g. extra living costs), as built by the engine scenario. */
    data class Change(val label: String, val incomeDelta: Chf, val expenseDelta: Chf, val fromMonth: Int, val untilMonth: Int, val inputId: String? = null) : Cause
    /** An input of the decision: what the person said, what was tested, and where the answer turns. */
    data class Input(
        val input: DecisionInput,
        val state: InputState,
        /** A what-if value in this branch, if any (never a confirmed fact). */
        val whatIf: Chf?,
        /** The value used in the hardest tested case. */
        val testedValue: Chf?,
        /** Largest value for which the cushion holds in every tested case (null: no single threshold). */
        val holdsUpTo: Chf?,
        /** Smallest value from which the cushion holds in every tested case (null: no single threshold). */
        val holdsFrom: Chf? = null,
    ) : Cause
}

fun causes(session: DecisionSession, b: Briefing, upToMonth: Int): List<Cause> {
    val worst = b.evidence.worstStressTest.worst
    val scenario = session.scenarioFor(b.evidence.worstCaseInputs)
    return buildList {
        worst.lowIncomeMonths.filter { it <= upToMonth }.takeIf { it.isNotEmpty() }
            ?.let { add(Cause.LowerPay(it, session.stress.lowIncomeReduction)) }
        scenario.recurringChanges.filter { it.startMonth <= upToMonth }
            .forEach { add(Cause.Change(it.labelKey, it.monthlyIncomeDelta, it.monthlyExpenseDelta, it.startMonth, it.endMonth, inputOf(it.evidenceRef))) }
        scenario.transfers.filter { it.month <= upToMonth }.forEach { add(Cause.SetAside(it.month, it.amount, it.label, inputOf(it.evidenceRef))) }
        scenario.oneOffCosts.filter { it.month <= upToMonth }.forEach { add(Cause.OneOff(it.month, it.amount, it.label, inputOf(it.evidenceRef))) }
        scenario.protectedCommitments.filter { c -> (0..upToMonth).any(c::appliesIn) }
            .forEach { add(Cause.Commitment(it.labelKey, it.monthly)) }
        session.inputs.forEach { i ->
            add(Cause.Input(
                input = i,
                state = session.state(i.id),
                whatIf = session.hypotheticalValues[i.id],
                testedValue = b.evidence.worstCaseInputs[i.id],
                holdsUpTo = b.evidence.unknownImpacts.firstOrNull { it.name == i.id }?.passesUpTo,
                holdsFrom = b.evidence.unknownImpacts.firstOrNull { it.name == i.id }?.passesFrom,
            ))
        }
    }
}

private fun inputOf(ref: String?): String? = ref?.takeIf { it.startsWith("input:") }?.removePrefix("input:")

/** "Just trying" a value: a what-if on a copy of the session. Nothing is stored in the graph. */
fun preview(session: DecisionSession, inputId: String, value: Chf): KeyMoment =
    keyMoment(brief(session.apply(Hypothesis.WhatIfInput(inputId, value))))

/** How many versions of the months were tested for the hardest input values, and how many dip. */
data class Tested(val cases: Int, val dipping: Int, val inputCombinations: Int)

fun tested(b: Briefing): Tested {
    val t = b.evidence.worstStressTest
    return Tested(t.cases.size, t.cases.count { it.result.minimumCash < t.floor }, b.evidence.valueCombinationsChecked)
}

/** Where an input's answer turns, as computed by the engine (never inferred by the UI). */
sealed interface Turn {
    data class UpTo(val value: Chf) : Turn
    data class From(val value: Chf) : Turn
    data object AllKeep : Turn
    data object NoneKeep : Turn
    /** Passing and failing values alternate: no single boundary to show. */
    data object NoSingleBoundary : Turn
}

fun turn(u: UnknownImpact): Turn = when {
    u.passingValues.size == u.worstMinimumCashByValue.size -> Turn.AllKeep
    u.passingValues.isEmpty() -> Turn.NoneKeep
    u.passesUpTo != null -> Turn.UpTo(u.passesUpTo!!)
    u.passesFrom != null -> Turn.From(u.passesFrom!!)
    else -> Turn.NoSingleBoundary
}

/** The engine's briefing for another route under the same facts, what-ifs and limits (for comparison). */
fun briefFor(session: DecisionSession, optionId: String?): Briefing = brief(session.copy(activeOptionId = optionId, plannedCut = null))
