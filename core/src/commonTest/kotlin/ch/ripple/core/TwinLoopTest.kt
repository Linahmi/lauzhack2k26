package ch.ripple.core

import ch.ripple.core.SampleData.alexSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun chf(f: Long) = Chf.francs(f)
private fun cut(start: Int, f: Long) = ExpenseReduction(start, chf(f))

/**
 * Interactive twin loop. Expected numbers are independent of this code: Alex move, deposit
 * unknown 1,300–1,700, 22 stress cases, buffer CHF 500; minimal cuts per start month 50/67/100
 * (BreakMyPlanTest + Python brute force); stay vs. move values derived by hand in comments.
 */
class TwinLoopTest {
    private val g0 = startGraph(alexSession)
    private val rejectThatMuch = TwinCommand.Correct(
        Correction.RejectRepair(cut(0, 50), RejectionScope.THIS_AMOUNT_OR_MORE, "I can't reduce my spending by that much"))

    /** Break → Repair → Reject → Recompute → Compare → Explain, driven by the demo script text. */
    @Test fun endToEndDemoScript() {
        val script = SampleData.alexDemoScript
        // Break + Repair (1): the move breaks the buffer; CHF 50/month from month 1 fixes it.
        assertEquals(NodeStatus.REPAIRABLE, g0.active.summary.status)
        assertEquals(cut(0, 50), g0.active.summary.offeredRepair)

        // Explain: "Why does this plan fail?"
        val why = g0.dispatch(g0.parseCommand(script[0])!!)
        assertIs<CommandResult.Explanation>(why)
        assertEquals(listOf(0, 1), why.worstCase.lowIncomeMonths) // two low-income months
        assertEquals(3, why.firstViolationMonth)                    // month 4
        assertEquals(chf(300), why.worstCase.result.minimumCash)
        assertTrue(why.disclosureKeys.contains(Keys.DISCLOSURE_HYPOTHETICAL))

        // Reject: interpreted, then CONFIRMED, before anything changes.
        val reject = g0.parseCommand(script[1])
        assertEquals(rejectThatMuch, reject)
        assertIs<CommandResult.NeedsConfirmation>(g0.dispatch(reject!!))
        val rejected = (g0.dispatch(reject, confirmedByUser = true) as CommandResult.Updated).update

        // Recompute: real search results changed, the rejected repair is gone, honest status.
        assertEquals(NodeStatus.ONLY_UNACCEPTABLE_REPAIRS, rejected.after.status)
        assertEquals(InvalidatedOffer(cut(0, 50), rejected.after.blockedBy), rejected.invalidatedOffer)
        assertNull(rejected.briefing.offeredRepair)
        assertFalse(rejected.briefing.actions.any { it.id == "explore:repair" })
        val g1 = rejected.graph

        // Show the alternative: objective outcomes vs. the user's limits, per option.
        val options = (g1.dispatch(g1.parseCommand(script[2])!!) as CommandResult.TradeOffs).options
        val move = options.first { it.optionId == null }
        val stay = options.first { it.optionId == "stay" }
        assertEquals("option.move", move.labelKey)
        assertEquals(cut(0, 50), move.financialCut)       // still financially true
        assertNull(move.acceptableCut)                     // but not within the user's limits
        assertEquals(rejected.after.blockedBy, move.conflictingConstraints)
        assertTrue(stay.bufferKeptWithoutChanges)
        assertNull(stay.financialCut)

        // Compare: "What if I stayed where I am?" opens a what-if branch from the same state.
        val stayed = (g1.dispatch(g1.parseCommand(script[3])!!) as CommandResult.Updated).update
        assertTrue(stayed.node.hypothetical)
        assertEquals(NodeStatus.PASSES, stayed.after.status)
        val c = stayed.graph.compareBranches(rejected.node.id, stayed.node.id)
        assertEquals(chf(4_300), c.nominal.closingCashDifference)   // 6,700 − 2,400
        assertEquals(chf(3_300), c.worstCase.minimumCashDifference) // 3,600 − 300

        // Back to the confirmed line; "My deposit is actually CHF 1,500" (confirmed) → recompute.
        val g2 = stayed.graph.select(rejected.node.id)
        val answer = g2.parseCommand(script[4])
        assertEquals(TwinCommand.Correct(Correction.AnswerInput("deposit", InputState.Exact(chf(1_500)))), answer)
        val known = (g2.dispatch(answer!!, confirmedByUser = true) as CommandResult.Updated).update
        assertEquals(NodeStatus.PASSES, known.after.status)        // worst cash exactly CHF 500
        assertEquals(InsightType.ROBUST_CONCLUSION, known.briefing.primary.type)
        assertNull(known.briefing.followUp)                        // no question about a confirmed value
        assertFalse(known.node.hypothetical)
        assertEquals(listOf("n0", "n1", "n3"), known.graph.path(known.node.id).map { it.id })
    }

    @Test fun repairIsFinanciallyFeasibleThenRejectionChangesTheActionSpace() {
        val repairs0 = g0.briefing().evidence.repairs
        assertTrue(repairs0[0].feasible && repairs0[0].financiallyFeasible)
        val g1 = (g0.dispatch(rejectThatMuch, true) as CommandResult.Updated).update
        val repairs1 = g1.briefing.evidence.repairs
        assertEquals(repairs0.map { it.financialMinimum }, repairs1.map { it.financialMinimum }) // finance unchanged
        assertTrue(repairs1.none { it.feasible })                                               // action space changed
    }

    @Test fun earlierOptionsShowTheirNewStatus() {
        // Trying the CHF 50 cut as a branch: objectively fine, no conflict yet.
        val tried = g0.explore(Hypothesis.ApplyCut(cut(0, 50)))
        assertEquals(NodeStatus.PASSES, tried.after.status)
        assertEquals(chf(500), tried.after.worstMinimumCash)
        assertTrue(tried.after.plannedCutConflicts.isEmpty())
        assertEquals(InputState.Unknown, tried.node.session.state("deposit")) // confirmed facts untouched

        // After the user rejects it, the same branch still passes objectively but conflicts.
        val rejectedThere = tried.graph.correct(rejectThatMuch.correction)
        assertEquals(NodeStatus.PASSES, rejectedThere.after.status)
        assertEquals(rejectedThere.node.session.constraints.rejections.map { it.id }, rejectedThere.after.plannedCutConflicts)
        assertTrue("planned_cut_conflicts" in rejectedThere.changed)

        // Re-evaluating the original state under the latest constraints recomputes it.
        val g = g0.correct(rejectThatMuch.correction).graph
        val n0Now = g.reevaluate("n0")
        assertEquals(NodeStatus.ONLY_UNACCEPTABLE_REPAIRS, n0Now.status)
        assertNull(n0Now.offeredRepair)
        assertEquals(NodeStatus.REPAIRABLE, g.node("n0").summary.status) // history itself unchanged
    }

    @Test fun interventionTimingIsExplorable() {
        // CHF 100/month starting in month 4 is too late: month-4 cash only reaches CHF 400.
        val late = g0.explore(Hypothesis.ApplyCut(cut(3, 100)))
        assertEquals(chf(400), late.after.worstMinimumCash)
        assertFalse(late.after.status == NodeStatus.PASSES)
        val inTime = g0.explore(Hypothesis.ApplyCut(cut(2, 100)))
        assertEquals(NodeStatus.PASSES, inTime.after.status)
    }

    @Test fun selectingABranchChangesTheUnderlyingScenario() {
        val g = g0.explore(Hypothesis.TryOption("stay")).graph
        assertEquals("Stay", g.briefing("n1").scenarios.getValue("decision-nominal").scenarioName)
        assertTrue(g.briefing("n0").scenarios.getValue("decision-nominal").scenarioName.startsWith("Move"))
        assertEquals(g0.briefing(), g.select("n0").briefing()) // switching back restores the state
    }

    @Test fun graphNodesReferenceRealCalculations() {
        // Same number straight from the engine, without brief(): worst case at deposit 1,700.
        val direct = stressTest(SampleData.alex, SampleData.alexMove(chf(1_700)), SampleData.alexStress, SampleData.alexFloor)
        assertEquals(direct.worstMinimumCash, g0.active.summary.worstMinimumCash)
        assertEquals(direct.worst.result, g0.briefing().scenarios.getValue("decision-worst"))
    }

    @Test fun tradeOffsBeforeRejection() {
        val (move, stay) = g0.tradeOffs()
        assertFalse(move.bufferKeptWithoutChanges)
        assertEquals(chf(300), move.worstMinimumCash)
        assertEquals(cut(0, 50), move.acceptableCut)
        assertEquals(2, move.latestAcceptableStart)
        assertEquals(chf(2_400), move.nominalClosingCash)       // 4000 + 6*250 − 500 − 900 − 1700
        assertEquals(chf(4_100), move.nominalClosingNetWorth)   // deposit kept as a restricted asset
        assertEquals(chf(3_600), stay.worstMinimumCash)         // 4000 + 2*(1800 − 2000)
        assertEquals(chf(6_700), stay.nominalClosingCash)       // 4000 + 6*600 − 900
        assertTrue(move.limitationKeys.containsAll(listOf(Keys.LIMIT_ACTION_SET, Keys.LIMIT_NOT_ADVICE)))
        assertEquals(g0.tradeOffs(), g0.tradeOffs())            // reproducible
    }

    @Test fun commandsAreParsedConservatively() {
        assertEquals(TwinCommand.Explain, g0.parseCommand("Why does this plan fail?"))
        assertEquals(TwinCommand.ShowTradeOffs, g0.parseCommand("Show me the alternative."))
        assertEquals(TwinCommand.Explore(Hypothesis.TryOption("stay")), g0.parseCommand("What if I stayed where I am?"))
        assertEquals(TwinCommand.Explore(Hypothesis.WhatIfInput("deposit", chf(1_300))), g0.parseCommand("What if the deposit were 1'300?"))
        assertEquals(
            TwinCommand.Correct(Correction.RejectRepair(cut(0, 0), RejectionScope.ANY_SPENDING_REDUCTION, "I can't reduce my spending.")),
            g0.parseCommand("I can't reduce my spending."),
        )
        assertEquals(TwinCommand.Correct(Correction.SetMinCashBuffer(chf(800))), g0.parseCommand("Keep a buffer of CHF 800"))
        assertEquals(TwinCommand.Correct(Correction.SetMaxMonthlyReduction(chf(30))), g0.parseCommand("I can cut at most 30 a month"))
        assertNull(g0.parseCommand("Tell me a joke"))
        assertNull(g0.parseCommand("My deposit is high")) // no number given → nothing invented
        assertEquals(Chf(150_050), parseAmount("chf 1,500.50"))
    }

    @Test fun unconfirmedCorrectionsChangeNothing() {
        val r = g0.dispatch(TwinCommand.Correct(Correction.SetMinCashBuffer(chf(300))))
        assertIs<CommandResult.NeedsConfirmation>(r)
        assertEquals(1, g0.nodes.size)
    }
}
