package ch.ripple.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun openChf(francs: Long) = Chf.francs(francs)

class OpenLifeDecisionTest {
    @Test fun studyingAbroadIsBuiltFromFinancialChangesWithoutANewDecisionKind() {
        val profile = FinancialProfile("Student", openChf(10_000), 12)
        val familySupport = PersonalCommitment(
            id = "family_support",
            labelKey = "commitment.family_support",
            monthly = openChf(300),
            endMonth = 12,
            provenance = Provenance.USER_STATED,
            evidenceRef = "user:family_support",
        )
        val current = Scenario(
            "Current situation",
            listOf(Phase(0, 12, openChf(3_000), openChf(2_000))),
        ).withConsequences(12, FinancialConsequences(protectedCommitments = listOf(familySupport)))
        val studyChanges = FinancialConsequences(
            recurring = listOf(
                RecurringCashFlowChange(
                    "change.lost_work_income", 2, 8,
                    monthlyIncomeDelta = -openChf(1_000),
                    provenance = Provenance.USER_ESTIMATE_RANGE,
                    evidenceRef = "input:expected_work_income",
                ),
                RecurringCashFlowChange(
                    "change.higher_living_costs", 2, 8,
                    monthlyExpenseDelta = openChf(500),
                    provenance = Provenance.ASSUMED,
                    evidenceRef = "assumption:living_costs_abroad",
                ),
            ),
            oneOffCosts = listOf(OneOffCost(
                2, openChf(2_000), "Relocation",
                Provenance.EXTERNAL_SOURCE, "source:relocation_quote",
            )),
            restrictedAssetTransfers = listOf(AssetTransfer(
                2, openChf(1_000), "Housing deposit",
                Provenance.USER_STATED, "input:housing_deposit",
            )),
        )
        val option = DecisionOption(
            id = "study-abroad",
            labelKey = "option.study_abroad",
            assumptionKeys = listOf("assumption.six_month_semester", "assumption.cash_only"),
            intention = "What would change financially if I studied abroad for a semester?",
            category = "education",
        ) { current.withConsequences(profile.horizonMonths, studyChanges) }
        val session = DecisionSession(
            profile = profile,
            baseline = current,
            inputs = emptyList(),
            stress = StressSet(0, Chf.ZERO),
            floor = openChf(1_000),
            repairCap = Chf.ZERO,
            options = listOf(option),
            decisionIntention = "What happens if I keep my current routine?",
            decision = { current },
        )

        val explored = startGraph(session).explore(Hypothesis.TryOption("study-abroad"))
        val scenario = explored.node.session.scenarioFor(emptyMap())
        val comparison = compare(profile, current, scenario)

        assertEquals(DecisionKind.OTHER, explored.after.decision.kind)
        assertEquals("education", explored.after.decision.category)
        assertEquals(option.intention, explored.after.decision.intention)
        assertEquals(option.assumptionKeys, explored.after.decision.assumptionKeys)
        assertEquals(studyChanges.recurring, scenario.recurringChanges.takeLast(2))
        assertEquals(listOf(familySupport), scenario.protectedCommitments)
        assertTrue(simulate(profile, scenario).months.all { it.commitments == openChf(300) })
        assertEquals(Provenance.EXTERNAL_SOURCE, scenario.oneOffCosts.single().provenance)
        assertEquals("source:relocation_quote", scenario.oneOffCosts.single().evidenceRef)
        assertEquals(Provenance.USER_STATED, scenario.transfers.single().provenance)
        assertEquals(openChf(-12_000), comparison.closingCashDifference)
        assertEquals(openChf(-6_400), comparison.minimumCashDifference)
        assertEquals(openChf(-12_000), explored.graph.compareBranches("n0", "n1").nominal.closingCashDifference)
    }

    @Test fun earlyRetirementExposesThatTheCashFlowViewIsNotACompleteRetirementAnalysis() {
        val profile = FinancialProfile("Career changer", openChf(60_000), 24)
        val current = Scenario(
            "Continue working",
            listOf(Phase(0, 24, openChf(6_000), openChf(4_000))),
        )
        val coverage = ModelCoverage(setOf(
            FinancialDomain.CASH_FLOW,
            FinancialDomain.PENSION,
            FinancialDomain.TAX,
            FinancialDomain.INVESTMENT,
        ))
        val option = DecisionOption(
            id = "retire-five-years-earlier",
            labelKey = "option.retire_early",
            intention = "How would retiring five years earlier change my financial outlook?",
            category = "retirement",
            coverage = coverage,
            assumptionKeys = listOf("assumption.cash_income_after_retirement", "assumption.cash_only"),
        ) {
            current.withConsequences(24, FinancialConsequences(recurring = listOf(
                RecurringCashFlowChange(
                    "change.stop_working_income", 0, 24,
                    monthlyIncomeDelta = -openChf(4_000),
                    provenance = Provenance.ASSUMED,
                    evidenceRef = "assumption:retirement_cash_income",
                ),
            )))
        }
        val session = DecisionSession(
            profile = profile,
            baseline = current,
            inputs = emptyList(),
            stress = StressSet(0, Chf.ZERO),
            floor = openChf(10_000),
            repairCap = Chf.ZERO,
            options = listOf(option),
            decision = { current },
        )

        val explored = startGraph(session).explore(Hypothesis.TryOption(option.id))

        assertTrue(coverage.cashFlowSupported)
        assertFalse(coverage.complete)
        assertEquals(
            setOf(FinancialDomain.PENSION, FinancialDomain.TAX, FinancialDomain.INVESTMENT),
            coverage.unsupportedDomains,
        )
        assertEquals(NodeStatus.PASSES, explored.after.status)
        assertTrue(Keys.LIMIT_UNMODELED_DOMAINS in explored.briefing.primary.limitationKeys)
        assertTrue(explored.briefing.moreInsights.all { Keys.LIMIT_UNMODELED_DOMAINS in it.limitationKeys })

        val unsupported = ModelCoverage(setOf(FinancialDomain.PENSION))
        assertFalse(unsupported.cashFlowSupported)
        assertFalse(unsupported.complete)
        assertEquals(emptySet(), unsupported.supportedDomains)
    }
}
