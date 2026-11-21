package ch.ripple.core

import ch.ripple.core.SampleData.alex
import ch.ripple.core.SampleData.alexFloor
import ch.ripple.core.SampleData.alexMove
import ch.ripple.core.SampleData.alexRepairCap
import ch.ripple.core.SampleData.alexStress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun chf(vararg francs: Long) = francs.map { Chf.francs(it) }

/**
 * Expected values were derived by hand (docs/RESEARCH_TO_IMPLEMENTATION.md §2) and
 * independently brute-forced in a separate Python script before writing this engine.
 * Month indexes are zero-based (fixture month 1 = index 0).
 */
class BreakMyPlanTest {
    private val move = alexMove(Chf.francs(1_700)) // upfront cash outflow F = 500 fee + 1700 deposit = 2200

    private fun witness(test: StressTest, lowMonths: List<Int>) =
        test.cases.first { it.lowIncomeMonths == lowMonths }.result

    @Test fun stressSetHas22CasesInDeterministicOrder() {
        val cases = alexStress.cases(6)
        assertEquals(22, cases.size)
        assertEquals(listOf(emptyList(), listOf(0), listOf(1)), cases.take(3))
        assertEquals(listOf(0, 1), cases[7])
        assertEquals(listOf(4, 5), cases.last())
        assertEquals(1, StressSet(0, Chf.ZERO).cases(6).size)
        assertEquals(7, StressSet(1, Chf.ZERO).cases(6).size)
        assertEquals(42, StressSet(3, Chf.ZERO).cases(6).size)
    }

    @Test fun nominalTraceLooksComfortable() {
        val nominal = simulate(alex, move)
        assertEquals(chf(2050, 2300, 2550, 1900, 2150, 2400), nominal.cashByMonth)
        assertTrue(nominal.minimumCash >= alexFloor)
    }

    @Test fun guardianFindsCounterexample() {
        val t = stressTest(alex, move, alexStress, alexFloor)
        assertEquals(22, t.cases.size)
        assertFalse(t.passes)
        assertEquals(Chf.francs(300), t.worstMinimumCash)
        assertEquals(listOf(0, 1), t.counterexample!!.lowIncomeMonths) // first worst case in enumeration order
        assertEquals(chf(1250, 700, 950, 300, 550, 800), t.worst.result.cashByMonth)
        assertEquals(3, t.firstViolationMonth) // month 4
        assertEquals(Chf.francs(200), t.floorShortfall)
    }

    @Test fun everyStressCaseReconciles() {
        for (c in stressTest(alex, move, alexStress, alexFloor).cases) {
            val r = c.result
            r.months.zipWithNext { a, b -> assertEquals(a.closingCash, b.openingCash) }
            r.months.forEach { assertEquals(it.openingCash + it.netFlow, it.closingCash) }
            c.lowIncomeMonths.forEach { assertEquals(Chf.francs(1_800), r.months[it].income) }
        }
    }

    @Test fun depositIsATransferNotAnExpense() {
        val nominal = simulate(alex, move)
        assertEquals(Chf.francs(1_700), nominal.restrictedAssets)
        assertEquals(Chf.ZERO, nominal.months[0].oneOffCosts - Chf.francs(500)) // only the fee is a cost
        // Net worth = 4000 + 6*250 - 500 fee - 900 obligation, whatever the deposit.
        assertEquals(Chf.francs(4_100), nominal.closingNetWorth)
        assertEquals(Chf.francs(4_100), simulate(alex, alexMove(Chf.francs(1_300))).closingNetWorth)
    }

    @Test fun stressBudgetChangesTheVerdict() {
        fun worst(k: Int) = stressTest(alex, move, StressSet(k, Chf.francs(800)), alexFloor).worstMinimumCash
        assertEquals(Chf.francs(1_900), worst(0))
        assertEquals(Chf.francs(1_100), worst(1))
        assertEquals(Chf.francs(300), worst(2))
        assertEquals(Chf.francs(-500), worst(3)) // negative kept, never clamped
    }

    @Test fun minimalRepairFromMonth1Is50() {
        val s = findMinimalMonthlyRepair(alex, move, alexStress, alexFloor, startMonth = 0, cap = alexRepairCap)
        assertEquals(Chf.francs(50), s.minimalMonthlyReduction)
        assertEquals(51, s.candidatesChecked) // 0..50, every smaller value tried and failed
        assertTrue(s.retest!!.passes)
        assertEquals(chf(1300, 800, 1100, 500, 800, 1100), witness(s.retest!!, listOf(0, 1)).cashByMonth)
        // One franc less is not enough: worst month-4 cash 496.
        val r49 = move.withExpenseReduction(6, ExpenseReduction(0, Chf.francs(49)))
        assertEquals(Chf.francs(496), stressTest(alex, r49, alexStress, alexFloor).worstMinimumCash)
    }

    @Test fun latestFeasibleStartIsMonth3() {
        val w = findLatestFeasibleStart(alex, move, alexStress, alexFloor, cap = alexRepairCap)
        assertEquals(
            listOf(Chf.francs(50), Chf.francs(67), Chf.francs(100), null, null, null),
            w.byStartMonth.map { it.minimalMonthlyReduction },
        )
        assertEquals(2, w.latestFeasibleStart) // month 3
        assertEquals(101, w.byStartMonth[3].candidatesChecked)
        assertFalse(w.byStartMonth[3].feasible)
    }

    @Test fun repairTraces() {
        fun trace(start: Int, r: Long) = witness(
            stressTest(alex, move.withExpenseReduction(6, ExpenseReduction(start, Chf.francs(r))), alexStress, alexFloor),
            listOf(0, 1),
        ).cashByMonth
        assertEquals(chf(1250, 700, 1050, 500, 850, 1200), trace(2, 100))
        assertEquals(chf(1250, 700, 950, 400, 750, 1100), trace(3, 100))
        assertEquals(chf(1250, 700, 950, 300, 550, 800), trace(0, 0))
        assertEquals(Chf.francs(498), stressTest(alex, move.withExpenseReduction(6, ExpenseReduction(1, Chf.francs(66))), alexStress, alexFloor).worstMinimumCash)
    }

    @Test fun alreadyFeasiblePlanNeedsZeroRepair() {
        val s = findMinimalMonthlyRepair(alex, alexMove(Chf.francs(1_300)), alexStress, alexFloor, 0, alexRepairCap)
        assertEquals(Chf.ZERO, s.minimalMonthlyReduction)
        assertEquals(1, s.candidatesChecked)
    }

    @Test fun skepticFindsDepositThreshold() {
        val d = analyzeUnknown(alex, SampleData.alexDepositUnknown, alexStress, alexFloor)
        assertEquals(401, d.worstMinimumCashByValue.size)
        val byValue = d.worstMinimumCashByValue.toMap()
        assertEquals(Chf.francs(700), byValue[Chf.francs(1_300)])
        assertEquals(Chf.francs(600), byValue[Chf.francs(1_400)])
        assertEquals(Chf.francs(500), byValue[Chf.francs(1_500)]) // exactly on the floor counts as passing
        assertEquals(Chf.francs(499), byValue[Chf.francs(1_501)])
        assertEquals(Chf.francs(300), byValue[Chf.francs(1_700)])
        assertTrue(d.changesDecision)
        assertEquals(Chf.francs(1_500), d.passesUpTo)
        assertEquals(Chf.francs(400), d.swing)
    }

    @Test fun skepticRanksDecisionChangingUnknownFirst() {
        val lateSmallExpense = UnknownInput("Small expense in month 6", Chf.ZERO, Chf.francs(50), Chf.francs(10)) { v ->
            move.copy(oneOffCosts = move.oneOffCosts + OneOffCost(5, v))
        }
        val ranked = rankUnknowns(alex, listOf(lateSmallExpense, SampleData.alexDepositUnknown), alexStress, alexFloor)
        assertEquals("Refundable rental deposit", ranked.first().name)
        assertFalse(ranked.last().changesDecision)
        assertNull(ranked.last().passesUpTo)
    }

    @Test fun obligationTimingOffByOne() {
        // Moving the CHF 900 obligation from month 4 to month 6 removes the breach.
        val later = move.copy(oneOffCosts = listOf(OneOffCost(0, Chf.francs(500)), OneOffCost(5, Chf.francs(900))))
        val t = stressTest(alex, later, alexStress, alexFloor)
        assertEquals(Chf.francs(700), t.worstMinimumCash)
        assertEquals(listOf(0, 1), t.worst.lowIncomeMonths)
        assertEquals(1, t.worst.result.months.minBy { it.closingCash }.month)
        assertTrue(t.passes)
        assertNull(t.counterexample)
        assertNull(t.firstViolationMonth)
    }

    @Test fun zeroIncome() {
        val noIncome = move.copy(phases = listOf(Phase(0, 6, Chf.ZERO, Chf.francs(2_350))))
        val t = stressTest(alex, noIncome, StressSet(0, Chf.francs(800)), alexFloor)
        assertEquals(0, t.firstViolationMonth)
        assertEquals(Chf.francs(4_000 - 2_200 - 6 * 2_350 - 900), t.worstMinimumCash)
        // A stress cut larger than the income is rejected, not clamped to zero income.
        assertFailsWith<IllegalArgumentException> { stressTest(alex, noIncome, alexStress, alexFloor) }
    }

    @Test fun repeatable() {
        assertEquals(stressTest(alex, move, alexStress, alexFloor), stressTest(alex, move, alexStress, alexFloor))
        assertEquals(
            findLatestFeasibleStart(alex, move, alexStress, alexFloor, alexRepairCap).byStartMonth.map { it.minimalMonthlyReduction },
            findLatestFeasibleStart(alex, move, alexStress, alexFloor, alexRepairCap).byStartMonth.map { it.minimalMonthlyReduction },
        )
    }

    @Test fun invalidInputsRejected() {
        assertFailsWith<IllegalArgumentException> { StressSet(-1, Chf.ZERO) }
        assertFailsWith<IllegalArgumentException> { StressSet(1, Chf.francs(-1)) }
        assertFailsWith<IllegalArgumentException> { StressSet(7, Chf.ZERO).cases(6) }
        assertFailsWith<IllegalArgumentException> { findMinimalMonthlyRepair(alex, move, alexStress, alexFloor, 6, alexRepairCap) }
        assertFailsWith<IllegalArgumentException> { findMinimalMonthlyRepair(alex, move, alexStress, alexFloor, -1, alexRepairCap) }
        assertFailsWith<IllegalArgumentException> { findMinimalMonthlyRepair(alex, move, alexStress, alexFloor, 0, Chf.francs(-1)) }
        assertFailsWith<IllegalArgumentException> { findMinimalMonthlyRepair(alex, move, alexStress, alexFloor, 0, alexRepairCap, Chf.ZERO) }
        assertFailsWith<IllegalArgumentException> { UnknownInput("x", Chf.francs(2), Chf.francs(1), Chf.francs(1)) { move } }
        assertFailsWith<IllegalArgumentException> { UnknownInput("x", Chf.ZERO, Chf.francs(5), Chf.francs(2)) { move } }
        assertFailsWith<IllegalArgumentException> { simulate(alex, move.copy(transfers = listOf(AssetTransfer(6, Chf.francs(1))))) }
        assertFailsWith<IllegalArgumentException> { simulate(alex, move.copy(transfers = listOf(AssetTransfer(0, Chf.francs(-1))))) }
        // Overlapping phases are caught before the month-by-month stress rewrite could hide them.
        val overlap = move.copy(phases = move.phases + Phase(2, 3, Chf.ZERO, Chf.ZERO))
        assertFailsWith<IllegalArgumentException> { stressTest(alex, overlap, alexStress, alexFloor) }
    }
}
