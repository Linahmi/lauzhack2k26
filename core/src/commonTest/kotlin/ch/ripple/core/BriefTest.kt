package ch.ripple.core

import ch.ripple.core.SampleData.alexSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun chf(f: Long) = Chf.francs(f)
private fun FinancialInsight.fact(key: String) = facts.first { it.key == key }.value
private val baseDisclosures = listOf(
    Keys.DISCLOSURE_FICTIONAL, Keys.DISCLOSURE_HYPOTHETICAL, Keys.DISCLOSURE_TESTED_CASES, Keys.DISCLOSURE_CASH_ONLY,
)

/** Expected numbers: hand-derived in docs/RESEARCH_TO_IMPLEMENTATION.md and brute-forced independently. */
class BriefTest {
    private val initial = brief(alexSession) // deposit Unknown in [1300, 1700]

    @Test fun unknownDepositGivesQualifiedWarningAndOneQuestion() {
        val p = initial.primary
        assertEquals(InsightType.FRAGILE_PLAN, p.type) // nominal plan fine, stress breaks it
        assertEquals("insight.fragile_plan.depends_on_input", p.headlineKey) // "could", not "will"
        assertEquals(InsightSeverity.WARNING, p.severity)
        assertEquals(FactValue.Money(chf(300)), p.fact("worst_min_cash"))
        assertEquals(FactValue.Month(3), p.fact("first_violation_month")) // month 4
        assertEquals(FactValue.Money(chf(200)), p.fact("floor_shortfall"))
        assertEquals(FactValue.Months(listOf(0, 1)), p.fact("low_income_months"))
        assertEquals(FactValue.Money(chf(1_900)), p.fact("nominal_min_cash"))
        assertEquals(FactValue.Count(401 * 22), p.fact("cases_checked"))
        assertTrue(Keys.LIMIT_DEPENDS_ON_INPUT in p.limitationKeys)
        assertTrue(RankReason.COULD_CHANGE_WITH_MISSING_ANSWER in p.rankReasons)
        // The unknown input is reported as unknown with its bounds — no invented value.
        val input = p.facts.first { it.key == "input:deposit" }
        assertEquals(Provenance.UNKNOWN, input.provenance)
        assertEquals(FactValue.MoneyRange(chf(1_300), chf(1_700)), input.value)

        val q = initial.followUp!!
        assertEquals("deposit", q.inputId)
        assertTrue(q.allowsUnsure)
        assertEquals(AnswerSpec.MoneyRange(chf(1_300), chf(1_700), chf(1)), q.answer)
        assertEquals(FactValue.Money(chf(1_500)), q.whyFacts.first { it.key == "passes_up_to" }.value)
        assertEquals("answer:deposit", initial.actions.first().id)
    }

    @Test fun repairAndDeadlineHoldForEveryTestedDeposit() {
        assertEquals(
            listOf(chf(50), chf(67), chf(100), null, null, null),
            initial.evidence.repairs.map { it.minimalMonthlyReduction },
        )
        val types = initial.moreInsights.map { it.type }
        assertEquals(listOf(InsightType.DECISION_CHANGING_UNKNOWN, InsightType.FEASIBLE_REPAIR, InsightType.ACTION_DEADLINE), types)
        val repair = initial.moreInsights.first { it.type == InsightType.FEASIBLE_REPAIR }
        assertEquals(FactValue.Money(chf(50)), repair.fact("monthly_reduction"))
        assertEquals(FactValue.Month(0), repair.fact("start_month"))
        assertTrue(Keys.LIMIT_ACTION_SET in repair.limitationKeys && Keys.LIMIT_NOT_ADVICE in repair.limitationKeys)
        val deadline = initial.moreInsights.first { it.type == InsightType.ACTION_DEADLINE }
        assertEquals(FactValue.Month(2), deadline.fact("latest_start_month")) // month 3
        assertEquals(FactValue.Money(chf(100)), deadline.fact("monthly_reduction_at_latest"))
    }

    @Test fun scenariosAndEvidenceComeFromTheEngine() {
        assertEquals(mapOf("deposit" to chf(1_700)), initial.evidence.worstCaseInputs)
        assertEquals(401, initial.evidence.valueCombinationsChecked)
        assertEquals(22, initial.evidence.stressCasesPerCombination)
        assertFalse(initial.evidence.passesForAllTestedValues)
        assertFalse(initial.evidence.failsForAllTestedValues)
        val s = initial.scenarios
        assertEquals(listOf(2050, 2300, 2550, 1900, 2150, 2400).map(::chfInt), s.getValue("decision-nominal").cashByMonth)
        assertEquals(listOf(1250, 700, 950, 300, 550, 800).map(::chfInt), s.getValue("decision-worst").cashByMonth)
        assertEquals(listOf(1300, 800, 1100, 500, 800, 1100).map(::chfInt), s.getValue("decision-repaired").cashByMonth)
        assertEquals(simulate(SampleData.alex, SampleData.alexStay), s.getValue("baseline"))
        for (id in initial.primary.scenarioIds + initial.moreInsights.flatMap { it.scenarioIds }) assertTrue(id in s, id)
    }

    @Test fun exactLowDepositIsRobust() {
        val b = brief(alexSession.answer("deposit", InputState.Exact(chf(1_300))))
        assertEquals(InsightType.ROBUST_CONCLUSION, b.primary.type)
        assertEquals(InsightSeverity.REASSURING, b.primary.severity)
        assertEquals(FactValue.Money(chf(700)), b.primary.fact("worst_min_cash"))
        assertEquals(FactValue.Money(chf(200)), b.primary.fact("margin"))
        assertEquals(Provenance.USER_STATED, b.primary.facts.first { it.key == "input:deposit" }.provenance)
        assertNull(b.followUp)
        assertTrue(b.evidence.repairs.isEmpty())
        assertFalse("decision-repaired" in b.scenarios)
        assertFalse(Keys.DISCLOSURE_UNKNOWN_BOUNDED in b.disclosureKeys)
    }

    @Test fun exactHighDepositIsAnUnqualifiedWarning() {
        val b = brief(alexSession.answer("deposit", InputState.Exact(chf(1_700))))
        assertEquals("insight.fragile_plan", b.primary.headlineKey)
        assertTrue(RankReason.SUPPORTED_BY_ALL_TESTED_CASES in b.primary.rankReasons)
        assertNull(b.followUp)
        assertEquals(chf(50), b.evidence.repairs[0].minimalMonthlyReduction)
    }

    @Test fun rangesAreSearchedNotGuessed() {
        val within = brief(alexSession.answer("deposit", InputState.Range(chf(1_300), chf(1_500))))
        assertEquals(InsightType.ROBUST_CONCLUSION, within.primary.type) // exactly on the floor at 1,500
        assertEquals(FactValue.Money(chf(0)), within.primary.fact("margin"))
        assertEquals(201, within.evidence.valueCombinationsChecked)
        assertEquals(Provenance.USER_ESTIMATE_RANGE, within.primary.facts.first { it.key == "input:deposit" }.provenance)

        val above = brief(alexSession.answer("deposit", InputState.Range(chf(1_600), chf(1_700))))
        assertEquals("insight.fragile_plan", above.primary.headlineKey) // fails for every value in the range
        assertTrue(above.evidence.failsForAllTestedValues)
        assertNull(above.followUp) // the range no longer changes the verdict
        assertEquals(chf(300), above.evidence.worstStressTest.worstMinimumCash)
    }

    @Test fun notSureIsAcceptedAndNeverReAsked() {
        val b = brief(alexSession.answer("deposit", InputState.Unknown))
        assertNull(b.followUp)
        // Same honest "depends" warning and facts (no invented value); only the answer action is gone.
        assertEquals(initial.primary.copy(actionIds = emptyList()), b.primary.copy(actionIds = emptyList()))
        assertFalse("answer:deposit" in b.primary.actionIds)
        val u = b.moreInsights.first { it.type == InsightType.DECISION_CHANGING_UNKNOWN }
        assertFalse("answer:deposit" in u.actionIds)
        assertTrue("correct:deposit" in u.actionIds)
        assertFalse(b.actions.any { it.kind == ActionKind.ANSWER_QUESTION })
    }

    @Test fun mostDecisionRelevantQuestionFirstAndIrrelevantOnesSkipped() {
        val session = alexSession.copy(
            inputs = listOf(
                DecisionInput("deposit", "question.deposit", chf(1_300), chf(1_700), chf(100)),
                DecisionInput("late_expense", "question.late_expense", chf(0), chf(50), chf(10)),
            ),
            decision = { v ->
                val move = SampleData.alexMove(v.getValue("deposit"))
                move.copy(oneOffCosts = move.oneOffCosts + OneOffCost(5, v.getValue("late_expense")))
            },
        )
        val b = brief(session)
        assertEquals(5 * 6, b.evidence.valueCombinationsChecked)
        assertEquals(listOf("deposit", "late_expense"), b.evidence.unknownImpacts.map { it.name })
        assertEquals("deposit", b.followUp!!.inputId)
        assertFalse(b.evidence.unknownImpacts.last().changesDecision)
        // After "I'm not sure" on the deposit, the late expense does not change the verdict: no question.
        assertNull(brief(session.answer("deposit", InputState.Unknown)).followUp)
    }

    @Test fun breachWhenNominalAlreadyFailsAndNoRepairInScope() {
        val tight = brief(alexSession.copy(floor = chf(2_000)))
        assertEquals(InsightType.LIQUIDITY_BREACH, tight.primary.type)

        val severe = brief(alexSession.copy(stress = StressSet(3, chf(800))))
        assertEquals(InsightSeverity.CRITICAL, severe.primary.severity) // worst cash -500: unfunded, not clamped
        assertEquals(FactValue.Money(chf(-500)), severe.primary.fact("worst_min_cash"))
        assertTrue(severe.moreInsights.any { it.type == InsightType.NO_REPAIR_IN_SCOPE })
        assertFalse(severe.moreInsights.any { it.type == InsightType.FEASIBLE_REPAIR })
    }

    @Test fun seenInsightsNeverDemoteTheSafetyFinding() {
        val b = brief(alexSession.markSeen(listOf("breach", "repair")))
        assertEquals("breach", b.primary.id)
        assertTrue(RankReason.ALREADY_SEEN in b.primary.rankReasons)
        assertTrue(RankReason.ALREADY_SEEN in b.moreInsights.first { it.id == "repair" }.rankReasons)
        assertTrue(RankReason.NOT_SEEN_YET in b.moreInsights.first { it.id == "deadline" }.rankReasons)
    }

    @Test fun disclosuresAlwaysPresent() {
        val sessions = listOf(
            alexSession,
            alexSession.answer("deposit", InputState.Exact(chf(1_300))),
            alexSession.answer("deposit", InputState.Exact(chf(1_700))),
            alexSession.answer("deposit", InputState.Unknown),
            alexSession.copy(stress = StressSet(3, chf(800))),
        )
        for (s in sessions) assertTrue(brief(s).disclosureKeys.containsAll(baseDisclosures))
        assertTrue(Keys.DISCLOSURE_UNKNOWN_BOUNDED in initial.disclosureKeys)
    }

    @Test fun reproducible() {
        assertEquals(initial, brief(alexSession))
        val answered = alexSession.answer("deposit", InputState.Exact(chf(1_450)))
        assertEquals(brief(answered), brief(answered))
    }

    @Test fun invalidInputsRejected() {
        assertFailsWith<IllegalArgumentException> { alexSession.answer("rent", InputState.Unknown) }
        assertFailsWith<IllegalArgumentException> { InputState.Exact(chf(-1)) }
        assertFailsWith<IllegalArgumentException> { InputState.Range(chf(2), chf(1)) }
        val coarse = alexSession.copy(inputs = listOf(DecisionInput("deposit", "q", chf(1_300), chf(1_700), chf(100))))
        assertFailsWith<IllegalArgumentException> { coarse.answer("deposit", InputState.Range(chf(1_300), chf(1_350))) }
        assertFailsWith<IllegalArgumentException> { DecisionInput("x", "q", chf(0), chf(5), chf(2)) }
        // Exhaustive search guard: 401 x 51 combinations is too many.
        val huge = alexSession.copy(inputs = alexSession.inputs + DecisionInput("y", "q", chf(0), chf(50), chf(1)))
        assertFailsWith<IllegalArgumentException> { brief(huge) }
    }
}

private fun chfInt(f: Int) = Chf.francs(f.toLong())
