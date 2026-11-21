package ch.ripple.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun evolvingChf(francs: Long) = Chf.francs(francs)
private fun evolvingCut(startMonth: Int, francs: Long) = ExpenseReduction(startMonth, evolvingChf(francs))

class EvolvingTwinTest {
    @Test fun lifeDecisionsAreExplicitAndUseTheSameSimulationEngine() {
        val graph = startGraph(SampleData.alexEvolvingSession)
        assertEquals(DecisionKind.MOVE_OUT, graph.active.summary.decision.kind)

        val delayed = graph.explore(Hypothesis.TryOption("delay"))
        assertEquals(DecisionKind.DELAY_DECISION, delayed.after.decision.kind)
        assertEquals(listOf("assumption.delay_move_two_months"), delayed.after.decision.assumptionKeys)
        assertEquals(NodeStatus.PASSES, delayed.after.status)
        assertEquals(evolvingChf(1_000), delayed.after.worstMinimumCash)
        assertEquals(UpdateReason.LIFE_DECISION_EXPLORED, delayed.explanation.reason)
        assertTrue(delayed.explanation.hypothetical)

        val comparison = delayed.graph.compareBranches("n0", delayed.node.id)
        assertEquals(evolvingChf(700), comparison.worstCase.minimumCashDifference)
        assertTrue("life_decision" in comparison.differences)
    }

    @Test fun personalCommitmentsAreAddedToEveryPathAndNeverCut() {
        val familySupport = PersonalCommitment(
            "family_support", "commitment.family_support", evolvingChf(50),
        )
        val update = startGraph(SampleData.alexSession).correct(Correction.SetCommitment(familySupport))

        assertEquals(NodeStatus.REPAIRABLE, update.after.status)
        assertEquals(evolvingChf(100), update.after.worstMinimumCash)
        assertEquals(evolvingCut(0, 100), update.after.offeredRepair)
        assertEquals(listOf("family_support"), update.after.commitmentIds)
        assertEquals(
            listOf(familySupport.copy(evidenceRef = "commitment:family_support")),
            update.explanation.activeConstraints.commitments,
        )
        val repaired = update.briefing.scenarios.getValue("decision-repaired")
        assertTrue(repaired.months.all { it.commitments == evolvingChf(50) })
        assertTrue(repaired.months.all { it.expenses >= it.commitments })

        val restored = update.graph.correct(Correction.RemoveCommitment("family_support"))
        assertEquals(startGraph(SampleData.alexSession).active.summary, restored.after)

        val profile = FinancialProfile("Protected", evolvingChf(500), 1)
        val protected = Scenario(
            "Protected",
            listOf(Phase(0, 1, evolvingChf(800), evolvingChf(900), evolvingChf(850))),
        )
        val search = findMinimalMonthlyRepair(
            profile, protected, StressSet(0, Chf.ZERO), evolvingChf(500), 0, evolvingChf(100),
        )
        assertFalse(search.feasible) // only CHF 50 is adjustable; the plan needs CHF 100
        assertFailsWith<IllegalArgumentException> {
            protected.withExpenseReduction(1, evolvingCut(0, 51))
        }
        assertFailsWith<IllegalArgumentException> {
            startGraph(SampleData.alexSession).correct(Correction.SetCommitment(
                PersonalCommitment("late", "commitment.late", evolvingChf(1), startMonth = 6),
            ))
        }
    }

    @Test fun confirmedInformationRecalculatesTheTwinAndExplainsWhy() {
        val initial = startGraph(SampleData.alexEvolvingSession)
        val rejected = initial.correct(Correction.RejectRepair(
            evolvingCut(0, 50),
            RejectionScope.THIS_AMOUNT_OR_MORE,
            "I support my family and cannot cut that much",
        ))
        assertEquals(NodeStatus.ONLY_UNACCEPTABLE_REPAIRS, rejected.after.status)

        val confirmed = rejected.graph.correct(Correction.AnswerInput(
            "deposit", InputState.Exact(evolvingChf(1_450)),
        ))
        assertFalse(confirmed.node.hypothetical)
        assertEquals(NodeStatus.PASSES, confirmed.after.status)
        assertEquals(evolvingChf(550), confirmed.after.worstMinimumCash)
        assertNull(confirmed.after.financialRepair)

        val explanation = confirmed.explanation
        assertEquals(UpdateReason.CONFIRMED_FACT_REPLACED_UNCERTAINTY, explanation.reason)
        val fact = explanation.stateChanges.single()
        assertEquals(FactValue.MoneyRange(evolvingChf(1_300), evolvingChf(1_700)), fact.before.value)
        assertEquals(Provenance.UNKNOWN, fact.before.provenance)
        assertEquals(FactValue.Money(evolvingChf(1_450)), fact.after.value)
        assertEquals(Provenance.USER_STATED, fact.after.provenance)
        assertEquals("input:deposit", fact.replacedAssumptionRef)
        assertEquals(evolvingChf(250), explanation.outcome.worstMinimumCashDelta)
        assertFalse(explanation.outcome.repairStillNeeded)
        assertEquals(evolvingCut(0, 50), explanation.outcome.financialRepairBefore)
        assertNull(explanation.outcome.financialRepairAfter)
        assertEquals(rejected.node.session.constraints, explanation.activeConstraints)
        assertTrue(explanation.evidenceRefs.isNotEmpty())
        assertTrue(explanation.disclosureKeys.isNotEmpty())
        assertTrue(explanation.limitationKeys.isNotEmpty())

        // Earlier versions keep their original inputs and conclusions.
        assertEquals(listOf("n0", "n1", "n2"), confirmed.graph.nodes.map { it.id })
        assertEquals(initial.active.summary, confirmed.graph.node("n0").summary)
        assertEquals(rejected.after, confirmed.graph.node("n1").summary)
        assertEquals(InputState.Unknown, confirmed.graph.node("n1").session.state("deposit"))

        // The updated confirmed state can still open and compare a separate what-if path.
        val stayed = confirmed.graph.explore(Hypothesis.TryOption("stay"))
        assertTrue(stayed.node.hypothetical)
        assertEquals(DecisionKind.STAY_CURRENT, stayed.after.decision.kind)
        assertTrue("life_decision" in stayed.graph.compareBranches("n2", stayed.node.id).differences)
    }

    @Test fun aConfirmedFactReplacesButDoesNotMasqueradeAsAWhatIf() {
        val root = startGraph(SampleData.alexSession)
        val whatIf = root.explore(Hypothesis.WhatIfInput("deposit", evolvingChf(1_300)))
        assertTrue(whatIf.explanation.hypothetical)
        assertEquals(UpdateReason.WHAT_IF_EXPLORED, whatIf.explanation.reason)
        assertEquals(InputState.Unknown, root.node("n0").session.state("deposit"))

        val confirmedInsideBranch = whatIf.graph.correct(Correction.AnswerInput(
            "deposit", InputState.Exact(evolvingChf(1_450)),
        ))
        assertEquals(InputState.Exact(evolvingChf(1_450)), confirmedInsideBranch.node.session.state("deposit"))
        assertFalse("deposit" in confirmedInsideBranch.node.session.hypotheticalValues)
        assertEquals(evolvingChf(550), confirmedInsideBranch.after.worstMinimumCash)
        assertEquals(UpdateReason.CONFIRMED_FACT_REPLACED_ASSUMPTION, confirmedInsideBranch.explanation.reason)
        // The node remains on a hypothetical branch even though the corrected input itself is confirmed.
        assertTrue(confirmedInsideBranch.node.hypothetical)
    }
}
