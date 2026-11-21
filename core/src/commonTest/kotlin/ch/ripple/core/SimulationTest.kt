package ch.ripple.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun chf(francs: Long) = Chf.francs(francs)

class SimulationTest {
    private val twoMonths = FinancialProfile("Test", chf(8_000), horizonMonths = 2)
    private val deficit = Scenario(
        "Deficit",
        listOf(Phase(0, 2, chf(1_200), chf(1_800))),
        listOf(OneOffCost(0, chf(500))),
    )

    @Test fun cashReconcilesEveryMonth() {
        for (s in listOf(SampleData.startWorking, SampleData.mastersDegree, SampleData.moveAbroad)) {
            val r = simulate(SampleData.alexia, s)
            assertEquals(SampleData.alexia.openingCash, r.months.first().openingCash)
            r.months.zipWithNext { a, b -> assertEquals(a.closingCash, b.openingCash) }
            r.months.forEach { assertEquals(it.openingCash + it.netFlow, it.closingCash) }
            // Opening + sum of all flows == closing.
            assertEquals(r.openingCash + r.months.fold(Chf.ZERO) { acc, m -> acc + m.netFlow }, r.closingCash)
        }
    }

    @Test fun smallDeficitScenarioHandComputed() {
        // 8000 + 1200 - 1800 - 500 = 6900; 6900 + 1200 - 1800 = 6300 (UBS starter-kit unit-test case)
        val r = simulate(twoMonths, deficit)
        assertEquals(listOf(chf(6_900), chf(6_300)), r.cashByMonth)
        assertEquals(chf(6_300), r.minimumCash)
        assertNull(r.firstNegativeMonth)
    }

    @Test fun technicalValidationExample() {
        // technical-validation.md: 8000 + 1200 - 1800 = 7400
        val r = simulate(FinancialProfile("T", chf(8_000), 1), Scenario("T", listOf(Phase(0, 1, chf(1_200), chf(1_800)))))
        assertEquals(chf(7_400), r.closingCash)
    }

    @Test fun negativeCashIsKeptNotClamped() {
        val r = simulate(twoMonths.copy(openingCash = Chf.ZERO), deficit)
        assertEquals(listOf(chf(-1_100), chf(-1_700)), r.cashByMonth)
        assertEquals(chf(-1_700), r.minimumCash)
        assertEquals(0, r.firstNegativeMonth)
    }

    @Test fun zeroIncome() {
        val r = simulate(twoMonths, Scenario("No income", listOf(Phase(0, 2, Chf.ZERO, chf(5_000)))))
        assertEquals(listOf(chf(3_000), chf(-2_000)), r.cashByMonth)
        assertEquals(1, r.firstNegativeMonth)
    }

    @Test fun zeroExpenses() {
        val r = simulate(twoMonths, Scenario("No expenses", listOf(Phase(0, 2, chf(1_000), Chf.ZERO))))
        assertEquals(chf(10_000), r.closingCash)
        assertEquals(chf(8_000), r.minimumCash) // opening balance counts toward the minimum
    }

    @Test fun reproducible() {
        assertEquals(simulate(SampleData.alexia, SampleData.mastersDegree), simulate(SampleData.alexia, SampleData.mastersDegree))
    }

    @Test fun higherOneOffCostNeverIncreasesCash() {
        val more = deficit.copy(oneOffCosts = listOf(OneOffCost(0, chf(900))))
        assertTrue(simulate(twoMonths, more).closingCash < simulate(twoMonths, deficit).closingCash)
    }

    @Test fun compareIsAlternativeMinusBaseline() {
        val c = compare(SampleData.alexia, SampleData.startWorking, SampleData.mastersDegree)
        assertEquals(chf(144_600) - chf(152_000), c.closingCashDifference)
        assertEquals(chf(-18_600) - chf(8_000), c.minimumCashDifference)
    }

    @Test fun invalidInputsRejected() {
        val bad = listOf(
            deficit.copy(phases = listOf(Phase(0, 1, chf(1), chf(1)))), // gap
            deficit.copy(phases = deficit.phases + Phase(1, 2, Chf.ZERO, Chf.ZERO)), // overlap
            deficit.copy(phases = listOf(Phase(0, 3, chf(1), chf(1)))), // beyond horizon
            deficit.copy(phases = emptyList()),
            deficit.copy(oneOffCosts = listOf(OneOffCost(0, chf(-1)))),
            deficit.copy(oneOffCosts = listOf(OneOffCost(2, chf(1)))),
            deficit.copy(phases = listOf(Phase(0, 2, chf(-1), chf(1)))),
            deficit.copy(phases = listOf(Phase(0, 2, chf(1), chf(-1)))),
        )
        bad.forEach { assertFailsWith<IllegalArgumentException> { simulate(twoMonths, it) } }
        assertFailsWith<IllegalArgumentException> { simulate(twoMonths.copy(horizonMonths = 0), deficit) }
    }

    @Test fun formatting() {
        assertEquals("CHF 152'000.00", chf(152_000).format())
        assertEquals("CHF -18'600.00", chf(-18_600).format())
        assertEquals("CHF 0.05", Chf(5).format())
        assertEquals("CHF 999.00", chf(999).format())
    }
}

/**
 * Golden values: independently reproduced by running the UBS starter kit
 * (starter-kit/run_example.py, HEAD fbaf35d) on 2026-11-21 and by hand calculation.
 * firstNegativeMonth is zero-indexed (run_example.py prints it 1-based as "Month 7").
 */
class UbsGoldenTest {
    @Test fun startWorking() = check(SampleData.startWorking, 152_000, 8_000, null)
    @Test fun mastersDegree() = check(SampleData.mastersDegree, 144_600, -18_600, 6)
    @Test fun moveAbroad() = check(SampleData.moveAbroad, 97_500, 2_300, null)

    private fun check(s: Scenario, closing: Long, min: Long, firstNegative: Int?) {
        val r = simulate(SampleData.alexia, s)
        assertEquals(120, r.months.size)
        assertEquals(Chf.francs(closing), r.closingCash)
        assertEquals(Chf.francs(min), r.minimumCash)
        assertEquals(firstNegative, r.firstNegativeMonth)
    }
}
