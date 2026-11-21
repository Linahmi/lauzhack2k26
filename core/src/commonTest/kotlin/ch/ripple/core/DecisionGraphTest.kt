package ch.ripple.core

import ch.ripple.core.SampleData.alexSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun chf(f: Long) = Chf.francs(f)
private fun cut(start: Int, f: Long) = ExpenseReduction(start, chf(f))
private fun Briefing.insight(type: InsightType) = (listOf(primary) + moreInsights).firstOrNull { it.type == type }
private fun FinancialInsight.fact(key: String) = facts.first { it.key == key }.value

/**
 * Expected numbers: Alex move, deposit unknown 1,300–1,700, 22 stress cases, buffer CHF 500,
 * action space 0..100 CHF/month. Minimal cuts per start month (verified in BreakMyPlanTest and
 * an independent brute force): 50, 67, 100, none, none, none.
 */
class DecisionGraphTest {
    private val g0 = startGraph(alexSession)
    private val cantCut50 = Correction.RejectRepair(cut(0, 50), RejectionScope.THIS_AMOUNT_OR_MORE, "I can't reduce my spending by that much")
    private val rejected = g0.correct(cantCut50)

    /** The milestone story: find, reject, recompute, report honestly, keep history. */
    @Test fun correctionLoopChangesTheFinancialReasoning() {
        // 1-3: Guardian finds the month-4 breach, Explorer offers CHF 50/month from month 1.
        val s0 = g0.active.summary
        assertEquals(NodeStatus.REPAIRABLE, s0.status)
        assertEquals(chf(300), s0.worstMinimumCash)
        assertEquals(cut(0, 50), s0.offeredRepair)
        assertEquals(2, s0.latestAcceptableStart)

        // 4-6: the user rejects it; the constraint is stored and the repair search reruns.
        val s1 = rejected.after
        assertEquals("n1", rejected.node.id)
        assertEquals("n0", rejected.node.parentId)
        assertEquals(cantCut50.reason, rejected.node.session.constraints.rejections.single().reason)
        // 7: honest result — CHF 50/67/100 all violate "this amount or more", nothing else works.
        assertEquals(NodeStatus.ONLY_UNACCEPTABLE_REPAIRS, s1.status)
        assertNull(s1.offeredRepair)
        assertEquals(cut(0, 50), s1.financialRepair) // still financially true, just not acceptable
        assertEquals(listOf(rejected.node.session.constraints.rejections.single().id), s1.blockedBy)
        assertEquals(listOf("status", "offered_repair", "latest_acceptable_start", "blocked_by"), rejected.changed)

        val b = rejected.briefing
        assertEquals(InsightType.FRAGILE_PLAN, b.primary.type) // the risk itself did not go away
        assertNull(b.insight(InsightType.FEASIBLE_REPAIR))
        assertNull(b.insight(InsightType.ACTION_DEADLINE))
        assertEquals(FactValue.Money(chf(50)), b.insight(InsightType.NO_ACCEPTABLE_REPAIR)!!.fact("financial_min_reduction"))
        assertFalse("decision-repaired" in b.scenarios)
        assertFalse(b.actions.any { it.id == "explore:repair" })
        assertTrue(b.actions.any { it.id == "edit:constraints" })
        assertNull(b.offeredRepair)

        // 8: both states stay inspectable.
        assertEquals(listOf("n0", "n1"), rejected.graph.nodes.map { it.id })
        assertEquals(g0.briefing("n0"), rejected.graph.briefing("n0"))
    }

    @Test fun maxReductionShiftsTheDeadline() {
        val u = g0.correct(Correction.SetMaxMonthlyReduction(chf(70)))
        assertEquals(cut(0, 50), u.after.offeredRepair)
        assertEquals(1, u.after.latestAcceptableStart) // month 2 at CHF 67; month 3 needs 100 > 70
        assertEquals(listOf("latest_acceptable_start"), u.changed)
        val r = u.briefing.evidence.repairs[2]
        assertNull(r.minimalMonthlyReduction)
        assertEquals(chf(100), r.financialMinimum)
        assertEquals(listOf(Keys.CONSTRAINT_MAX_REDUCTION), r.blockedBy)
        assertEquals(FactValue.Month(1), u.briefing.insight(InsightType.ACTION_DEADLINE)!!.fact("latest_start_month"))
    }

    @Test fun rejectedExactActionIsNeverOffered() {
        val u = g0.correct(Correction.RejectRepair(cut(0, 50), RejectionScope.EXACT_ACTION))
        assertEquals(cut(0, 51), u.after.offeredRepair) // next acceptable value, verified for every deposit
        val repair = u.briefing.insight(InsightType.FEASIBLE_REPAIR)!!
        assertEquals(FactValue.Money(chf(51)), repair.fact("monthly_reduction"))
        assertEquals(FactValue.Money(chf(50)), repair.fact("financial_min_reduction"))

        val noMonth1 = g0.correct(Correction.RejectRepair(cut(0, 50), RejectionScope.THIS_START_MONTH))
        assertEquals(cut(1, 67), noMonth1.after.offeredRepair)
    }

    @Test fun impossibleConstraintsAreReportedHonestly() {
        val noCuts = g0.correct(Correction.RejectRepair(cut(0, 50), RejectionScope.ANY_SPENDING_REDUCTION))
        assertEquals(NodeStatus.ONLY_UNACCEPTABLE_REPAIRS, noCuts.after.status)

        // A buffer above the opening cash cannot be met by any cut in the action space.
        val huge = g0.correct(Correction.SetMinCashBuffer(chf(4_500)))
        assertEquals(NodeStatus.NO_REPAIR_IN_SCOPE, huge.after.status)
        assertNull(huge.after.financialRepair)
        assertEquals(InsightType.LIQUIDITY_BREACH, huge.briefing.primary.type)
        assertTrue(huge.briefing.insight(InsightType.NO_REPAIR_IN_SCOPE) != null)
    }

    @Test fun correctionUpdatesTheNextBriefing() {
        val u = g0.correct(Correction.SetMinCashBuffer(chf(300)))
        assertEquals(NodeStatus.PASSES, u.after.status)
        assertEquals(InsightType.ROBUST_CONCLUSION, u.briefing.primary.type)
        val floor = u.briefing.primary.facts.first { it.key == "floor" }
        assertEquals(FactValue.Money(chf(300)), floor.value)
        assertEquals(Provenance.USER_STATED, floor.provenance)
        assertEquals(Provenance.ASSUMED, g0.briefing().primary.facts.first { it.key == "floor" }.provenance)

        val answered = g0.correct(Correction.AnswerInput("deposit", InputState.Exact(chf(1_300))))
        assertEquals(NodeStatus.PASSES, answered.after.status)
        assertNull(answered.after.openQuestionInputId)
    }

    @Test fun constraintsCanBeRemoved() {
        val id = rejected.node.session.constraints.rejections.single().id
        val back = rejected.graph.correct(Correction.RemoveRejection(id))
        assertEquals(g0.active.summary, back.after)
        assertEquals(PersonalConstraints(), back.node.session.constraints)
        val cleared = g0.correct(Correction.SetMaxMonthlyReduction(chf(70))).graph.correct(Correction.ClearMaxMonthlyReduction)
        assertEquals(g0.active.summary, cleared.after)
        // Rejecting the same thing twice does not stack.
        assertEquals(1, rejected.graph.correct(cantCut50).node.session.constraints.rejections.size)
    }

    @Test fun hypothesesNeverOverwriteConfirmedData() {
        val whatIf = g0.explore(Hypothesis.WhatIfInput("deposit", chf(1_300)))
        assertTrue(whatIf.node.hypothetical)
        assertEquals(NodeStatus.PASSES, whatIf.after.status)
        assertEquals(InputState.Unknown, whatIf.node.session.state("deposit")) // confirmed state untouched
        val f = whatIf.briefing.primary.facts.first { it.key == "input:deposit" }
        assertEquals(Provenance.ASSUMED, f.provenance)
        assertTrue(Keys.DISCLOSURE_WHAT_IF in whatIf.briefing.disclosureKeys)
        assertFalse(Keys.DISCLOSURE_WHAT_IF in g0.briefing().disclosureKeys)
        // The root is unchanged and corrections on the confirmed line stay non-hypothetical.
        assertEquals(g0.nodes.single(), whatIf.graph.node("n0"))
        assertFalse(whatIf.graph.correct(cantCut50, fromId = "n0").node.hypothetical)
        // Corrections made inside a what-if branch remain hypothetical.
        assertTrue(whatIf.graph.correct(cantCut50).node.hypothetical)
    }

    @Test fun branchesKeepParentsAndSwitchingRestoresState() {
        val stay = rejected.graph.explore(Hypothesis.TryOption("stay"), fromId = "n0")
        val g = stay.graph
        assertEquals("n2", stay.node.id)
        assertEquals("n0", stay.node.parentId)
        assertEquals(listOf("n1", "n2"), g.children("n0").map { it.id })
        assertEquals(listOf("n0", "n1"), g.path("n1").map { it.id })
        assertEquals(1, stay.node.revision)
        assertEquals(NodeStatus.PASSES, stay.after.status)

        val back = g.select("n1")
        assertEquals("n1", back.active.id)
        assertEquals(NodeStatus.ONLY_UNACCEPTABLE_REPAIRS, back.active.summary.status)
        assertEquals(rejected.briefing, back.briefing())
        assertEquals(g0.briefing(), back.select("n0").briefing())
        // Branching from an earlier node after switching back.
        val fork = back.select("n0").correct(Correction.SetMaxMonthlyReduction(chf(70)))
        assertEquals("n0", fork.node.parentId)
        assertEquals(listOf("n1", "n2", "n3"), fork.graph.children("n0").map { it.id })
    }

    @Test fun branchesCompareDeterministically() {
        val g = rejected.graph.explore(Hypothesis.TryOption("stay"), fromId = "n0").graph
        val c = g.compareBranches("n1", "n2") // move (cut rejected) vs. stay
        // Nominal closing cash: stay 4000 + 6*600 - 900 = 6700; move 4000 + 6*250 - 500 - 900 - 1700 = 2400.
        assertEquals(chf(4_300), c.nominal.closingCashDifference)
        // Worst case minimum: stay 3600 (low months 1-2, month 2) vs. move 300.
        assertEquals(chf(3_300), c.worstCase.minimumCashDifference)
        assertTrue("status" in c.differences)
        assertEquals(c, g.compareBranches("n1", "n2"))
    }

    @Test fun reproducible() {
        assertEquals(g0, startGraph(alexSession))
        assertEquals(rejected, g0.correct(cantCut50))
    }

    @Test fun invalidChangesRejected() {
        assertFailsWith<IllegalArgumentException> { g0.correct(Correction.RemoveRejection("nope")) }
        assertFailsWith<IllegalArgumentException> { g0.correct(Correction.SetMaxMonthlyReduction(chf(-1))) }
        assertFailsWith<IllegalArgumentException> { g0.correct(Correction.RejectRepair(cut(6, 1), RejectionScope.EXACT_ACTION)) }
        assertFailsWith<IllegalArgumentException> { g0.explore(Hypothesis.TryOption("fly")) }
        assertFailsWith<IllegalArgumentException> { g0.explore(Hypothesis.WhatIfInput("rent", chf(1))) }
        assertFailsWith<IllegalArgumentException> { g0.select("n9") }
    }
}
