package ch.ripple.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Builds the scenario FROM the team fixture JSON (not from SampleData) and checks every
 * "expected" value against the engine. Fixture months are 1-based; core is zero-based.
 */
class BreakMyPlanFixtureTest {
    private val fx = Json.parseToJsonElement(File("src/commonTest/resources/BREAK_MY_PLAN_FIXTURE.json").readText()).jsonObject
    private fun JsonObject.chf(key: String) = Chf.francs(this[key]!!.jsonPrimitive.long)
    private fun JsonObject.int(key: String) = this[key]!!.jsonPrimitive.int
    private val p = fx["profile"]!!.jsonObject
    private val mv = fx["move"]!!.jsonObject
    private val st = fx["stress_set"]!!.jsonObject
    private val rep = fx["repair_action"]!!.jsonObject
    private val exp = fx["expected"]!!.jsonObject

    private val horizon = fx.int("horizon_months")
    private val profile = FinancialProfile("Fixture", p.chf("initial_liquid_cash"), horizon)
    private val stress = StressSet(st.int("max_low_months"), p.chf("normal_monthly_net_income") - p.chf("low_monthly_net_income"))
    private val floor = p.chf("liquidity_floor")
    private val cap = rep.chf("max_monthly_amount")
    private val fee = mv.chf("nonrefundable_fee")

    private fun scenario(deposit: Chf) = Scenario(
        name = "Fixture move",
        phases = listOf(Phase(0, horizon, p.chf("normal_monthly_net_income"), p.chf("post_move_monthly_expenses"))),
        oneOffCosts = listOf(OneOffCost(mv.int("month") - 1, fee)) +
            fx["one_time_obligations"]!!.jsonArray.map { val o = it.jsonObject; OneOffCost(o.int("month") - 1, o.chf("amount")) },
        transfers = listOf(AssetTransfer(mv.int("month") - 1, deposit)),
    )
    private val upfront2200 = scenario(Chf.francs(2_200) - fee)
    private fun trace(key: String) = exp[key]!!.jsonArray.map { Chf.francs(it.jsonPrimitive.long) }
    private fun witnessTrace(s: Scenario) = stressTest(profile, s, stress, floor).cases.first { it.lowIncomeMonths == listOf(0, 1) }.result.cashByMonth

    @Test fun fixtureInputsAreConsistent() {
        assertEquals("1-based", fx["month_indexing"]!!.jsonPrimitive.content)
        assertEquals(st.int("number_of_sequences"), stress.cases(horizon).size)
        assertEquals(false, st["probabilities_attached"]!!.jsonPrimitive.boolean)
        assertEquals(mv.chf("upfront_cash_outflow_min"), fee + mv.chf("refundable_restricted_deposit_min"))
        assertEquals(mv.chf("upfront_cash_outflow_max"), fee + mv.chf("refundable_restricted_deposit_max"))
    }

    @Test fun expectedValues() {
        assertEquals(trace("upfront_2200_nominal_trace"), simulate(profile, upfront2200).cashByMonth)
        assertEquals(trace("upfront_2200_low_months_1_2_trace"), witnessTrace(upfront2200))

        val t = stressTest(profile, upfront2200, stress, floor)
        assertEquals(exp.chf("upfront_2200_worst_minimum_cash"), t.worstMinimumCash)
        assertEquals(exp.chf("upfront_2200_worst_minimum_cash_repeat"), t.worstMinimumCash)
        assertEquals(exp.int("upfront_2200_first_witness_violation_month"), t.firstViolationMonth!! + 1)
        assertEquals(exp.chf("upfront_2200_floor_shortfall"), t.floorShortfall)

        val w = findLatestFeasibleStart(profile, upfront2200, stress, floor, cap, Chf.francs(rep.int("amount_granularity").toLong()))
        assertEquals(exp.chf("upfront_2200_start_1_minimum_monthly_reduction"), w.byStartMonth[0].minimalMonthlyReduction)
        assertEquals(exp.chf("upfront_2200_start_2_minimum_monthly_reduction"), w.byStartMonth[1].minimalMonthlyReduction)
        assertEquals(exp.chf("upfront_2200_start_3_minimum_monthly_reduction"), w.byStartMonth[2].minimalMonthlyReduction)
        assertEquals(exp["upfront_2200_start_4_feasible_at_max_100"]!!.jsonPrimitive.boolean, w.byStartMonth[3].feasible)
        assertEquals(exp.int("upfront_2200_latest_start_at_cap_100"), w.latestFeasibleStart!! + 1)

        fun repaired(start: Int, r: Long) = upfront2200.withExpenseReduction(horizon, ExpenseReduction(start - 1, Chf.francs(r)))
        assertEquals(trace("upfront_2200_start_1_reduction_50_low_months_1_2_trace"), witnessTrace(repaired(1, 50)))
        assertEquals(trace("upfront_2200_start_3_reduction_100_low_months_1_2_trace"), witnessTrace(repaired(3, 100)))
        assertEquals(trace("upfront_2200_start_4_reduction_100_low_months_1_2_trace"), witnessTrace(repaired(4, 100)))

        for (f in listOf(1_800L, 1_900, 2_000)) {
            val worst = stressTest(profile, scenario(Chf.francs(f) - fee), stress, floor).worstMinimumCash
            assertEquals(exp.chf("upfront_${f}_worst_minimum_cash"), worst)
        }

        val deposit = analyzeUnknown(profile, UnknownInput("deposit", mv.chf("refundable_restricted_deposit_min"),
            mv.chf("refundable_restricted_deposit_max"), Chf.francs(1), ::scenario), stress, floor)
        assertEquals(exp.chf("deposit_exact_upper_bound_for_feasibility_without_repair"), deposit.passesUpTo)
    }
}
