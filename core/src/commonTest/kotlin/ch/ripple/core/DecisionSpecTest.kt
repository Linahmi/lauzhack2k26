package ch.ripple.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun f(x: Long) = Chf.francs(x)

/** Figures below are derived by hand from the fictional specs in ExampleDecisions. */
class DecisionSpecTest {
    private val sam = ExampleDecisions.samStudyAbroad.toSession()
    private val noor = ExampleDecisions.noorFewerHours.toSession()

    @Test fun studyAbroadRunsThroughTheGenericPrimitives() {
        val b = brief(sam)
        // Hardest tested value of the uncertain living costs is the top of the range.
        assertEquals(f(700), b.evidence.worstCaseInputs["living_abroad"])
        // +500/month, then from March: 1,200 - 2,600 = -1,400/month for 6 months, travel 900 and deposit 1,000 in March.
        val cash = b.evidence.worstStressTest.worst.result.cashByMonth
        assertEquals(listOf(9_500L, 10_000, 6_700, 5_300, 3_900, 2_500, 1_100, -300, 200, 700, 1_200, 1_700).map(::f), cash)
        // The deposit lowers cash but is still owned: net worth is cash + 1,000.
        assertEquals(f(2_700), b.scenarios.getValue("decision-worst").closingNetWorth)
        // Baseline (no exchange): 9,000 + 12 × 500.
        assertEquals(f(15_000), b.scenarios.getValue("baseline").closingCash)
        // Where the living-cost answer turns: 3,900 - 6x >= 1,000  ->  x <= 483.
        assertEquals(f(483), b.evidence.unknownImpacts.single { it.name == "living_abroad" }.passesUpTo)
        assertEquals(401, b.evidence.valueCombinationsChecked)
    }

    @Test fun fewerHoursIsADifferentDecisionWithTheSameEngine() {
        val b = brief(noor)
        assertEquals(f(200), b.evidence.worstCaseInputs["childcare"])
        val cash = b.evidence.worstStressTest.worst.result.cashByMonth
        assertEquals(listOf(2_900L, 3_300, 3_700, 3_300, 2_900, 2_500, 600, 200, -200, -600, -1_000, -1_400).map(::f), cash)
        assertEquals(8, b.evidence.worstStressTest.worst.result.firstNegativeMonth)
        // More childcare saved is better, so the boundary is a lower bound: 9x - 3,200 >= 1,500  ->  x >= 523.
        val childcare = b.evidence.unknownImpacts.single { it.name == "childcare" }
        assertNull(childcare.passesUpTo)
        assertEquals(f(523), childcare.passesFrom)
        // What the person can't cut is protected in every month.
        assertTrue(b.evidence.worstStressTest.worst.result.months.all { it.commitments == f(2_400) })
        assertNotEquals(brief(sam).evidence.worstStressTest.worstMinimumCash, b.evidence.worstStressTest.worstMinimumCash)
    }

    @Test fun timingIsAnInputWithAComputedBoundary() {
        // Starting later: 2,500 + 400s - 400(12 - s) - 1,500 >= 1,500  ->  s >= 7 (August), within the 12 months looked at.
        val start = noor.whereItTurns(DecisionSpec.START)!!
        assertEquals(f(7), start.passesFrom)
        // Studying abroad: 8,100 - 1,400d >= 1,000  ->  at most 5 months.
        assertEquals(f(5), sam.whereItTurns(DecisionSpec.DURATION)!!.passesUpTo)
        assertEquals(InputUnit.MONTH_INDEX, noor.inputs.single { it.id == DecisionSpec.START }.unit)
    }

    @Test fun alternativesComeFromTheSpecNotFromScreens() {
        val t = tradeOffs(sam)
        assertEquals(listOf(null, OPTION_STAY, OPTION_LATER), t.map { it.optionId })
        assertFalse(t[0].bufferKeptWithoutChanges)
        // Keeping things as they are: never below 9,000.
        assertEquals(f(9_000), t[1].worstMinimumCash)
        // The same exchange 3 months later (June–November): lowest is November, 1,200, above the 1,000 cushion.
        assertTrue(t[2].bufferKeptWithoutChanges)
        assertEquals(f(1_200), t[2].worstMinimumCash)
    }

    @Test fun whatIfAndCorrectionStaySeparateAndKeepHistory() {
        val g = startGraph(sam)
        val tried = g.explore(Hypothesis.WhatIfInput(DecisionSpec.START, f(5)))
        assertTrue(tried.node.hypothetical)
        assertEquals(InputState.Exact(f(2)), tried.node.session.state(DecisionSpec.START))
        val fixed = g.correct(Correction.AnswerInput("living_abroad", InputState.Exact(f(400))))
        // 3,900 - 6 × 400 = 1,500 in August: now above the cushion.
        assertEquals(f(1_500), fixed.graph.briefing(fixed.node.id).evidence.worstStressTest.worstMinimumCash)
        assertEquals(f(-300), fixed.graph.briefing("n0").evidence.worstStressTest.worstMinimumCash)
        assertEquals(2, fixed.graph.path(fixed.node.id).size)
    }

    @Test fun correctingTheSituationAlsoMovesTheBaseline() {
        val g = startGraph(noor).correct(Correction.AnswerInput(DecisionSpec.INCOME, InputState.Exact(f(5_200))))
        // Baseline now gains 600 a month: 2,500 + 12 × 600 - 1,500.
        assertEquals(f(8_200), g.graph.briefing(g.node.id).scenarios.getValue("baseline").closingCash)
    }

    @Test fun personalLimitsApplyToCustomDecisions() {
        val g = startGraph(noor)
        val offered = g.briefing().offeredRepair!!
        val rejected = g.correct(Correction.RejectRepair(offered, RejectionScope.THIS_AMOUNT_OR_MORE, "I support my parents"))
        val after = rejected.graph.briefing(rejected.node.id)
        assertTrue(after.offeredRepair == null || after.offeredRepair!!.monthly < offered.monthly)
        assertEquals("I support my parents", rejected.node.session.constraints.rejections.single().reason)
    }

    @Test fun incompleteOrImpossibleSpecsAreRefusedNotGuessed() {
        val draft = interpretDecision("What happens if I study abroad for six months?")
        assertTrue(draft.recognized)
        assertEquals("Studying abroad", draft.title)
        assertEquals(6, draft.durationMonths)
        assertTrue(draft.changes.all { it.amount == null })
        val spec = draft.toSpec(Situation(f(5_000), Amount.Known(f(3_000)), Amount.Known(f(2_000))))
        assertEquals(4, spec.problems().size)
        assertFailsWith<IllegalArgumentException> { spec.toSession() }

        val tooMuch = ExampleDecisions.noorFewerHours.copy(changes = listOf(
            FinancialChange("lost_pay", "Pay", ChangeKind.LESS_INCOME, Amount.Known(f(6_000))),
        ))
        assertTrue(tooMuch.problems().any { "more than your pay" in it })
    }

    @Test fun interpreterOnlyTakesFiguresThePersonTyped() {
        val car = interpretDecision("What if I buy a car in 3 months for CHF 15,000?")
        assertEquals("Buying a car", car.title)
        assertEquals(3, car.startMonth)
        assertEquals(Amount.Known(f(15_000)), car.changes.first { it.kind == ChangeKind.ONE_OFF_COST }.amount)
        assertNull(car.changes.first { it.kind == ChangeKind.MORE_SPENDING }.amount)

        val hours = interpretDecision("What happens if I reduce my working hours to 80%?")
        assertEquals("Working fewer hours", hours.title)
        assertTrue(hours.changes.all { it.amount == null })

        val childcare = interpretDecision("What if I pay for childcare?")
        assertNotEquals("Buying a car", childcare.title)

        val pension = interpretDecision("Should I pay more into my pension?")
        assertFalse(pension.recognized)
        assertTrue(FinancialDomain.PENSION in pension.coverage.unsupportedDomains)
    }
}
