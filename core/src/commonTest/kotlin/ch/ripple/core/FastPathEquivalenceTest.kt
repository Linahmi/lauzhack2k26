package ch.ripple.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The closed-form fast path used by brief() must give exactly the same answers as the original
 * brute force (simulate every stress case, try every cut on the grid). Checked across buffers,
 * stress budgets, options, planned cuts and a personal limit.
 */
class FastPathEquivalenceTest {
    private fun chf(f: Long) = Chf.francs(f)

    @Test fun worstCaseMatchesFullStressTest() {
        val scenarios = listOf(
            SampleData.alexia to SampleData.mastersDegree, // 120 months, phases, one-off costs
            SampleData.alexia to SampleData.moveAbroad,
            SampleData.alex to SampleData.alexMove(chf(1_700)),
            SampleData.alex to SampleData.alexStay,
            SampleData.alex to SampleData.alexMove().copy(phases = listOf(Phase(0, 6, chf(800), chf(2_350)))),
        )
        for ((profile, scenario) in scenarios) for (k in 0..2) for (cut in listOf(0L, 800L)) {
            val stress = StressSet(k, chf(cut))
            assertEquals(
                stressTest(profile, scenario, stress, Chf.ZERO).worstMinimumCash,
                stressProfile(profile, scenario, stress).worstMinimum,
                "${scenario.name} k=$k cut=$cut",
            )
        }
    }

    @Test fun briefMatchesBruteForce() {
        val base = SampleData.alexSession.copy(
            inputs = listOf(DecisionInput("deposit", "question.deposit", chf(1_300), chf(1_700), chf(100))),
            repairStep = chf(10),
        )
        var configs = 0
        for (floor in listOf(300L, 500L, 700L, 4_500L)) for (k in 0..3) for (option in listOf(null, "stay"))
            for (planned in listOf(null, ExpenseReduction(1, chf(30)))) for (limit in listOf(null, chf(60))) {
                if (option == "stay" && planned != null) continue
                val s = base.copy(
                    floor = chf(floor), stress = StressSet(k, chf(800)), activeOptionId = option, plannedCut = planned,
                    constraints = PersonalConstraints(maxMonthlyReduction = limit),
                )
                val b = brief(s)
                val combos = (13..17).map { s.scenarioFor(mapOf("deposit" to chf(it * 100L))) }
                val tests = combos.map { stressTest(s.profile, it, s.stress, s.effectiveFloor) }
                val label = "floor=$floor k=$k option=$option planned=$planned limit=$limit"

                assertEquals(tests.minOf { it.worstMinimumCash }, b.evidence.worstStressTest.worstMinimumCash, label)
                assertEquals(tests.all { it.passes }, b.evidence.passesForAllTestedValues, label)
                assertEquals(tests.none { it.passes }, b.evidence.failsForAllTestedValues, label)
                if (!b.evidence.passesForAllTestedValues) {
                    for (start in 0 until 6) {
                        val r = b.evidence.repairs[start]
                        assertEquals(bruteRepair(s, combos, start, respectLimits = false), r.financialMinimum, "$label start=$start financial")
                        assertEquals(bruteRepair(s, combos, start, respectLimits = true), r.minimalMonthlyReduction, "$label start=$start acceptable")
                    }
                }
                configs++
            }
        assertEquals(96, configs) // 4 buffers x 4 stress budgets x (move: 2 planned x 2 limits + stay: 2 limits)
    }

    /** The original algorithm: try every grid value from 0, simulate every stress case of every combination. */
    private fun bruteRepair(s: DecisionSession, combos: List<Scenario>, start: Int, respectLimits: Boolean): Chf? {
        var r = Chf.ZERO
        while (r <= s.repairCap) {
            val allowed = !respectLimits || s.constraints.excludes(start, r).isEmpty()
            if (allowed && combos.all {
                    stressTest(s.profile, it.withExpenseReduction(6, ExpenseReduction(start, r)), s.stress, s.effectiveFloor).passes
                }) return r
            r += s.repairStep
        }
        return null
    }
}
