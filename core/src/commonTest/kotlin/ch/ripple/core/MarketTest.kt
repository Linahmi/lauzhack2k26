package ch.ripple.core

import ch.ripple.core.SampleData.alexLausanneSession
import ch.ripple.core.SampleData.alexRentQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Market grounding. Two kinds of data are used here, never mixed up:
 *  - GENUINE sourced observations: MarketData.vaudHousing (FSO je-d-09.03.03.42_2022-2024 and
 *    Statistique Vaud Numerus 8-2026, figures as printed; see docs/MARKET_DATA.md).
 *  - FICTIONAL fixtures: Alex's income/costs/bounds (SampleData) and every estimate built with
 *    [fake] below (test-only values, marked kind = ASSUMPTION, never shipped).
 */
class MarketTest {
    private fun chf(f: Long) = Chf.francs(f)
    private val lausanne1r = "fso-lausanne-1r-2022-2024"
    private val vaud1r = "statvd-vd-1r-2024"

    private fun fake(id: String, point: Long, scope: GeoScope = GeoScope.LAUSANNE, unit: MarketUnit = MarketUnit.CHF_PER_MONTH,
                     tenancy: TenancyDuration = TenancyDuration.UP_TO_ONE_YEAR, endYear: Int = 2025) = MarketEstimate(
        id, MarketCategory.HOUSING_NET_RENT, scope, HousingCharacteristics(1, tenancy = tenancy, includesCharges = false),
        MarketValue(chf(point), unit, MarketStatistic.MEAN, chf(point - 10), chf(point + 10), 95),
        MarketSource("Test fixture", "fictional", null, "https://example.invalid", "2026-01-01", "test only", RightsStatus.OPEN_WITH_ATTRIBUTION),
        ObservationPeriod(endYear, endYear), "2026-11-21", EvidenceKind.ASSUMPTION, emptyList())

    private fun check(s: DecisionSession) = brief(s).marketChecks.single { it.inputId == "rent" }

    // --- 1. Confirmed user values are never overwritten -------------------------------------

    @Test fun confirmedUserValueIsNeverReplacedByMarketData() {
        val g = startGraph(alexLausanneSession).correct(Correction.AnswerInput("rent", InputState.Exact(chf(1_050)))).graph
        assertFailsWith<IllegalArgumentException> { g.explore(Hypothesis.UseMarketEstimate("rent", lausanne1r)) }

        val updated = g.updateMarketData(MarketSnapshot("v2", listOf(fake(lausanne1r, 700)))).graph
        val s = updated.active.session
        assertEquals(InputState.Exact(chf(1_050)), s.state("rent"))
        assertTrue(s.hypotheticalValues.isEmpty())
        val c = check(s)
        assertFalse(c.offersEstimate)
        assertFalse(c.needsExactValue)
        assertEquals(Provenance.USER_STATED, c.facts.single { it.key == "input:rent" }.provenance)
        assertEquals(listOf(chf(1_050 + 1_200 + 150)), updated.briefing().scenarios.getValue("decision-nominal").months.map { it.expenses }.distinct())
    }

    @Test fun ownValueReplacesATriedMarketAssumption() {
        val tried = startGraph(alexLausanneSession).explore(Hypothesis.UseMarketEstimate("rent", lausanne1r)).graph
        val corrected = tried.correct(Correction.AnswerInput("rent", InputState.Exact(chf(1_050)))).node.session
        assertTrue(corrected.marketAssumptions.isEmpty())
        assertNull(corrected.hypotheticalValues["rent"])
        assertEquals(MarketReview.REPLACED_BY_USER_VALUE, corrected.marketReviews[lausanne1r])
        assertEquals(InputState.Exact(chf(1_050)), corrected.state("rent"))
    }

    // --- 2. Estimated / unknown values keep their provenance ---------------------------------

    @Test fun provenanceOfUnknownRangeAndMarketValuesIsPreserved() {
        val start = brief(alexLausanneSession)
        val rentFact = { b: Briefing -> b.primary.facts.single { it.key == "input:rent" } }
        assertEquals(Provenance.UNKNOWN, rentFact(start).provenance)

        val ranged = brief(alexLausanneSession.answer("rent", InputState.Range(chf(900), chf(1_000))))
        assertEquals(Provenance.USER_ESTIMATE_RANGE, rentFact(ranged).provenance)
        assertEquals(FactValue.MoneyRange(chf(900), chf(1_000)), rentFact(ranged).value)

        val market = brief(alexLausanneSession.apply(Hypothesis.UseMarketEstimate("rent", lausanne1r)))
        assertEquals(ExplanationFact("input:rent", FactValue.Money(chf(899)), Provenance.EXTERNAL_SOURCE, "market:$lausanne1r"), rentFact(market))
        assertEquals(Provenance.UNKNOWN, market.primary.facts.single { it.key == "input:deposit" }.provenance)
        assertTrue(MarketKeys.DISCLOSURE_MARKET_ASSUMPTION in market.disclosureKeys)
        assertFalse(MarketKeys.DISCLOSURE_MARKET_ASSUMPTION in start.disclosureKeys)
    }

    // --- 3. Missing or stale market data gives a clearly limited result ----------------------

    @Test fun missingMarketDataIsSaidPlainly() {
        val c = check(alexLausanneSession.copy(marketData = null))
        assertEquals(MarketCheckStatus.NO_MARKET_DATA, c.status)
        assertTrue(c.benchmarks.isEmpty() && c.benchmarkOutcomes.isEmpty())
        assertTrue(MarketKeys.LIMIT_NO_DATA in c.limitationKeys)
        assertTrue(c.facts.none { it.provenance == Provenance.EXTERNAL_SOURCE })
        assertTrue(c.decisionSensitive) // the engine still knows rent matters
    }

    @Test fun staleDataIsFlaggedAndNeverClaimedAsAMismatch() {
        val comparable = alexRentQuery.copy(housing = alexRentQuery.housing!!.copy(tenancy = TenancyDuration.ALL_DURATIONS))
        val fresh = sessionWith(comparable).answer("rent", InputState.Exact(chf(1_050)))
        assertEquals(MarketCheckStatus.ABOVE_AVERAGE_INTERVAL, check(fresh).status)

        val stale = sessionWith(comparable.copy(asOfYear = 2030)).answer("rent", InputState.Exact(chf(1_050)))
        val c = check(stale)
        assertEquals(MarketCheckStatus.NOT_DIRECTLY_COMPARABLE, c.status)
        assertNull(c.comparedWith)
        assertTrue(ComparabilityIssue.STALE in c.benchmarks.first().issues)
        assertTrue(MarketKeys.LIMIT_STALE in c.limitationKeys && MarketKeys.LIMIT_NO_COMPARABLE in c.limitationKeys)
    }

    @Test fun newLeaseIsNotComparedWithExistingTenancyAverages() {
        val c = check(alexLausanneSession.answer("rent", InputState.Exact(chf(1_300))))
        assertEquals(MarketCheckStatus.NOT_DIRECTLY_COMPARABLE, c.status)
        assertEquals(listOf(ComparabilityIssue.DIFFERENT_TENANCY_DURATION), c.benchmarks.first().issues)
        assertTrue(MarketKeys.LIMIT_TENANCY in c.limitationKeys)
    }

    @Test fun comparablePositionsAreAgainstThePublishedIntervalOnly() {
        val s = sessionWith(alexRentQuery.copy(housing = alexRentQuery.housing!!.copy(tenancy = TenancyDuration.ALL_DURATIONS)))
        assertEquals(MarketCheckStatus.WITHIN_AVERAGE_INTERVAL, check(s.answer("rent", InputState.Exact(chf(900)))).status) // 883..915
        assertEquals(MarketCheckStatus.BELOW_AVERAGE_INTERVAL, check(s.answer("rent", InputState.Exact(chf(880)))).status)
        assertEquals(MarketCheckStatus.WITHIN_AVERAGE_INTERVAL, check(s.answer("rent", InputState.Range(chf(850), chf(890)))).status)
        assertEquals(lausanne1r, check(s.answer("rent", InputState.Exact(chf(920)))).comparedWith)
        assertTrue(MarketKeys.LIMIT_AVERAGE_NOT_RANGE in check(s).limitationKeys)
    }

    // --- 4. Invalid or incompatible units are rejected ---------------------------------------

    @Test fun incompatibleUnitsAndInvalidValuesAreRejected() {
        val perM2 = fake("m2", 20, unit = MarketUnit.CHF_PER_M2_PER_MONTH)
        assertEquals(listOf(ComparabilityIssue.INCOMPATIBLE_UNIT), assess(alexRentQuery, perM2).issues)
        val s = alexLausanneSession.copy(marketData = MarketSnapshot("t", listOf(perM2)))
        assertTrue(check(s).benchmarks.isEmpty())
        assertFailsWith<IllegalArgumentException> { s.apply(Hypothesis.UseMarketEstimate("rent", "m2")) }
        assertFailsWith<IllegalArgumentException> { MarketValue(chf(900), MarketUnit.CHF_PER_MONTH, MarketStatistic.MEAN, chf(950), chf(990), 95) }
        assertFailsWith<IllegalArgumentException> { MarketValue(chf(900), MarketUnit.CHF_PER_MONTH, MarketStatistic.MEAN, chf(880), null, 95) }
        assertFailsWith<IllegalArgumentException> { MarketSnapshot("dup", listOf(perM2, perM2)) }
        // An input without a market link cannot take a market value.
        assertFailsWith<IllegalArgumentException> { alexLausanneSession.apply(Hypothesis.UseMarketEstimate("deposit", lausanne1r)) }
    }

    // --- 5. Geographic scopes stay distinguishable -------------------------------------------

    @Test fun geographyIsMatchedExactlyBroaderOrNotAtAll() {
        val snap = MarketData.vaudHousing
        val bench = snap.benchmarksFor(alexRentQuery)
        assertEquals(listOf(lausanne1r, vaud1r), bench.map { it.estimate.id }) // other sizes excluded
        assertTrue(ComparabilityIssue.BROADER_GEOGRAPHY in bench[1].issues)
        assertFalse(ComparabilityIssue.BROADER_GEOGRAPHY in bench[0].issues)
        val geneva = fake("ge", 1_000, scope = GeoScope(GeoLevel.CANTON, "GE", GeoScope.CH))
        assertTrue(ComparabilityIssue.DIFFERENT_GEOGRAPHY in assess(alexRentQuery, geneva).issues)
        assertFalse(assess(alexRentQuery, geneva).relevant)
        assertEquals(ComparabilityIssue.DIFFERENT_SIZE, assess(alexRentQuery, snap.estimate("statvd-vd-3r-new-2024")!!).issues.first())
    }

    // --- 6 + 7. A tried benchmark really changes the simulation and creates a branch ---------

    @Test fun tryingABenchmarkRecomputesAndCreatesAnInspectableBranch() {
        val g0 = startGraph(alexLausanneSession)
        val u = g0.explore(Hypothesis.UseMarketEstimate("rent", lausanne1r))
        val n = u.node

        assertEquals(GraphChange.Explored(Hypothesis.UseMarketEstimate("rent", lausanne1r)), n.change)
        assertTrue(n.hypothetical)
        assertEquals("node.use_market_estimate", n.labelKey)
        assertEquals(mapOf("rent" to lausanne1r), n.summary.marketAssumptions)
        assertEquals(MarketData.vaudHousing.version, n.summary.marketDataVersion)
        assertEquals(MarketReview.TRIED_AS_ASSUMPTION, n.session.marketReviews[lausanne1r])
        assertTrue("market_assumptions" in u.changed && "status" in u.changed)
        // Parent untouched.
        assertTrue(g0.active.session.marketAssumptions.isEmpty())
        assertEquals(u.graph.node("n0"), g0.active)

        // Real recomputation: monthly expenses = other costs + charges + the benchmark rent.
        val nominal = u.briefing.scenarios.getValue("decision-nominal")
        assertEquals(chf(1_200 + 150 + 899), nominal.months.first().expenses)
        assertEquals(nominal, simulate(SampleData.alex, SampleData.alexMoveLausanne(chf(899), u.briefing.evidence.worstCaseInputs.getValue("deposit"))))
        assertEquals(NodeStatus.NO_REPAIR_IN_SCOPE, u.before.status)
        assertEquals(NodeStatus.PASSES, u.after.status)
    }

    // --- 8. Rejecting restores the state -----------------------------------------------------

    @Test fun rejectingAnEstimateRestoresTheInputAndStopsOfferingIt() {
        val g0 = startGraph(alexLausanneSession)
        val tried = g0.explore(Hypothesis.UseMarketEstimate("rent", lausanne1r)).graph
        val r = tried.correct(Correction.RejectMarketEstimate(lausanne1r))
        val s = r.node.session

        assertEquals(InputState.Unknown, s.state("rent"))
        assertTrue(s.hypotheticalValues.isEmpty() && s.marketAssumptions.isEmpty())
        assertEquals(MarketReview.REJECTED, s.marketReviews[lausanne1r])
        assertEquals(g0.active.summary.status, r.after.status)
        assertEquals(g0.active.summary.worstMinimumCash, r.after.worstMinimumCash)
        val c = check(s)
        assertEquals(listOf(lausanne1r), c.rejectedEstimateIds)
        assertEquals(listOf(vaud1r), c.benchmarks.map { it.estimate.id })
        assertEquals(listOf(vaud1r), r.briefing.followUp!!.marketEstimateIds)
        assertFailsWith<IllegalArgumentException> { r.graph.explore(Hypothesis.UseMarketEstimate("rent", lausanne1r)) }
    }

    // --- 9. Determinism ----------------------------------------------------------------------

    @Test fun sameInputsGiveIdenticalResults() {
        val s = alexLausanneSession.apply(Hypothesis.UseMarketEstimate("rent", vaud1r))
        assertEquals(brief(s).marketChecks, brief(s).marketChecks)
        assertEquals(brief(s).scenarios, brief(s).scenarios)
        assertEquals(brief(s).primary, brief(s).primary)
    }

    // --- 10. The Reality Check never invents figures or thresholds ----------------------------

    @Test fun everyExternalFigureIsInTheSnapshotAndEveryThresholdIsVerified() {
        val c = check(alexLausanneSession)
        val published = MarketData.vaudHousing.estimates.flatMap { listOfNotNull(it.value.point, it.value.ciLow, it.value.ciHigh) }.toSet()
        c.facts.filter { it.provenance == Provenance.EXTERNAL_SOURCE }.forEach { f ->
            when (val v = f.value) {
                is FactValue.Money -> assertTrue(v.amount in published)
                is FactValue.MoneyRange -> assertTrue(v.low in published && v.high in published)
                else -> error("unexpected external fact ${f.key}")
            }
            assertTrue(f.evidenceRef!!.removePrefix("market:").let { MarketData.vaudHousing.estimate(it) != null })
        }
        // Threshold: passes at 950, fails one grid step above, checked with the full stress test.
        val threshold = c.sensitivity.passesUpTo!!
        assertEquals(chf(950), threshold)
        val deposit = brief(alexLausanneSession).evidence.worstCaseInputs.getValue("deposit")
        val test = { rent: Chf -> stressTest(SampleData.alex, SampleData.alexMoveLausanne(rent, deposit), SampleData.alexStress, SampleData.alexFloor) }
        assertTrue(test(threshold).passes)
        assertFalse(test(threshold + chf(10)).passes)
        // Benchmark outcomes are engine results at the published values.
        val o = c.benchmarkOutcomes.first { it.estimateId == lausanne1r }
        assertEquals(test(chf(899)).worstMinimumCash, o.worstMinimumCashAtPoint)
        assertEquals(true, o.passesAtIntervalHigh)
        // No market link -> no check, and the original demo is unchanged.
        assertTrue(brief(SampleData.alexSession).marketChecks.isEmpty())
    }

    // --- Prioritisation, brief() and command integration ---------------------------------------

    @Test fun marketInsightNeverOutranksTheSafetyVerdictOrTheQuestion() {
        val b = brief(alexLausanneSession)
        assertEquals("breach", b.primary.id)
        val ids = b.moreInsights.map { it.id }
        assertTrue(ids.indexOf("unknown:rent") < ids.indexOf("market:rent"))
        val m = b.moreInsights.single { it.id == "market:rent" }
        assertEquals(InsightType.MARKET_SENSITIVE_ASSUMPTION, m.type)
        assertEquals("insight.market_check.value_not_stated", m.headlineKey)
        assertTrue("market:try:rent" in m.actionIds && "market:why:rent" in m.actionIds)
        assertEquals("rent", b.followUp!!.inputId)
        assertEquals(listOf(lausanne1r, vaud1r), b.followUp!!.marketEstimateIds)
        assertTrue(check(alexLausanneSession).needsExactValue)
    }

    @Test fun commandsMapToStructuredMarketUpdates() {
        val g0 = startGraph(alexLausanneSession)
        assertEquals(TwinCommand.ShowMarket("rent"), g0.parseCommand("Help me estimate my rent"))
        assertEquals(TwinCommand.ShowMarket("rent"), g0.parseCommand("Why this estimate?"))
        assertEquals(TwinCommand.Explore(Hypothesis.UseMarketEstimate("rent", lausanne1r)), g0.parseCommand("Try the market rent"))
        assertEquals(TwinCommand.Correct(Correction.AnswerInput("rent", InputState.Exact(chf(1_050)))), g0.parseCommand("My rent is actually CHF 1,050"))
        val shown = g0.dispatch(TwinCommand.ShowMarket("rent")) as CommandResult.Market
        assertEquals(MarketCheckStatus.VALUE_NOT_STATED, shown.check.status)
        // The original demo vocabulary still parses as before.
        assertEquals(TwinCommand.Explain, startGraph(SampleData.alexSession).parseCommand("Why does this plan fail?"))
    }

    // --- Revisions: a snapshot update never rewrites old branches ------------------------------

    @Test fun snapshotUpdateCreatesANewRevisionAndKeepsOldBranches() {
        val tried = startGraph(alexLausanneSession).explore(Hypothesis.UseMarketEstimate("rent", lausanne1r)).graph
        val v2 = MarketSnapshot("v2-test", listOf(fake(lausanne1r, 990), MarketData.vaudHousing.estimate(vaud1r)!!))
        val u = tried.updateMarketData(v2)
        assertEquals(GraphChange.MarketDataUpdated(MarketData.vaudHousing.version, "v2-test", emptyList()), u.node.change)
        assertEquals(chf(990), u.node.session.hypotheticalValues["rent"])
        assertEquals(chf(899), u.graph.node("n1").session.hypotheticalValues["rent"]) // old branch untouched
        assertEquals(MarketData.vaudHousing.version, u.graph.node("n1").summary.marketDataVersion)
        assertTrue("market_data_version" in u.changed)

        val gone = tried.updateMarketData(MarketSnapshot("v3-test", listOf(MarketData.vaudHousing.estimate(vaud1r)!!)))
        assertEquals(listOf("rent"), (gone.node.change as GraphChange.MarketDataUpdated).droppedAssumptions)
        assertTrue(gone.node.session.hypotheticalValues.isEmpty())
    }

    // --- Definition of done: the Alex housing walk-through ------------------------------------

    @Test fun endToEndAlexLausanne() {
        // 1-3. Rent is unknown; sourced benchmarks are offered as external context.
        val g0 = startGraph(alexLausanneSession)
        val c0 = g0.marketCheck("rent")!!
        assertEquals(MarketCheckStatus.VALUE_NOT_STATED, c0.status)
        assertEquals(EvidenceKind.OBSERVED_OFFICIAL_STATISTIC, c0.benchmarks.first().estimate.kind)
        // 4-5. Try the FSO Lausanne figure: the engine recomputes and the plan keeps the buffer.
        val g1 = g0.explore(Hypothesis.UseMarketEstimate("rent", lausanne1r)).graph
        assertEquals(NodeStatus.PASSES, g1.active.summary.status)
        // 6. ...but rent is still decision-sensitive: above CHF 950 the buffer breaks, and the real lease is unknown.
        val c1 = g1.marketCheck("rent")!!
        assertTrue(c1.decisionSensitive && c1.needsExactValue)
        assertEquals(lausanne1r, c1.assumedEstimateId)
        // 7-8. Correct with the real offer; recompute; both branches remain with their provenance.
        val g2 = g1.correct(Correction.AnswerInput("rent", InputState.Exact(chf(1_050)))).graph
        assertEquals(NodeStatus.REPAIRABLE, g2.active.summary.status)
        assertEquals(ExpenseReduction(0, chf(100)), g2.active.summary.offeredRepair)
        val cmp = g2.compareBranches("n1", "n2")
        assertTrue("market_assumptions" in cmp.differences && "status" in cmp.differences)
        assertNotEquals(cmp.a.summary.marketAssumptions, cmp.b.summary.marketAssumptions)
    }

    private fun sessionWith(q: MarketQuery) =
        alexLausanneSession.copy(inputs = alexLausanneSession.inputs.map { if (it.id == "rent") it.copy(market = q) else it })
}
