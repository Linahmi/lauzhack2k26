package ch.ripple.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun f(x: Long) = Chf.francs(x)

/** Figures below are derived by hand from the fictional specs in ExampleDecisions. */
class DecisionSpecTest {
    private val sam = ExampleDecisions.samStudyAbroad.toSession()
    private val noor = ExampleDecisions.noorFewerHours.toSession()

    @Test fun studyAbroadRunsThroughTheGenericPrimitives() {
        val b = brief(sam)
        // Hardest tested value of the uncertain living costs is the top of the range.
        assertEquals(f(700), b.evidence.worstCaseInputs["living_abroad"])
        // +500/month, then from March: 1,200 - 2,600 = -1,400/month for 6 months, travel 900 and deposit 1,000 in March.
        val cash = b.evidence.worstStressTest.worst.result.cashByMonth
        assertEquals(listOf(9_500L, 10_000, 6_700, 5_300, 3_900, 2_500, 1_100, -300, 200, 700, 1_200, 1_700).map(::f), cash)
        // The deposit lowers cash but is still owned: net worth is cash + 1,000.
        assertEquals(f(2_700), b.scenarios.getValue("decision-worst").closingNetWorth)
        // Baseline (no exchange): 9,000 + 12 × 500.
        assertEquals(f(15_000), b.scenarios.getValue("baseline").closingCash)
        // Where the living-cost answer turns: 3,900 - 6x >= 1,000  ->  x <= 483.
        assertEquals(f(483), b.evidence.unknownImpacts.single { it.name == "living_abroad" }.passesUpTo)
        assertEquals(401, b.evidence.valueCombinationsChecked)
    }

    @Test fun fewerHoursIsADifferentDecisionWithTheSameEngine() {
        val b = brief(noor)
        assertEquals(f(200), b.evidence.worstCaseInputs["childcare"])
        val cash = b.evidence.worstStressTest.worst.result.cashByMonth
        assertEquals(listOf(2_900L, 3_300, 3_700, 3_300, 2_900, 2_500, 600, 200, -200, -600, -1_000, -1_400).map(::f), cash)
        assertEquals(8, b.evidence.worstStressTest.worst.result.firstNegativeMonth)
        // More childcare saved is better, so the boundary is a lower bound: 9x - 3,200 >= 1,500  ->  x >= 523.
        val childcare = b.evidence.unknownImpacts.single { it.name == "childcare" }
        assertNull(childcare.passesUpTo)
        assertEquals(f(523), childcare.passesFrom)
        // What the person can't cut is protected in every month.
        assertTrue(b.evidence.worstStressTest.worst.result.months.all { it.commitments == f(2_400) })
        assertNotEquals(brief(sam).evidence.worstStressTest.worstMinimumCash, b.evidence.worstStressTest.worstMinimumCash)
    }

    @Test fun timingIsAnInputWithAComputedBoundary() {
        // Starting later: 2,500 + 400s - 400(12 - s) - 1,500 >= 1,500  ->  s >= 7 (August), within the 12 months looked at.
        val start = noor.whereItTurns(DecisionSpec.START)!!
        assertEquals(f(7), start.passesFrom)
        // Studying abroad: 8,100 - 1,400d >= 1,000  ->  at most 5 months.
        assertEquals(f(5), sam.whereItTurns(DecisionSpec.DURATION)!!.passesUpTo)
        assertEquals(InputUnit.MONTH_INDEX, noor.inputs.single { it.id == DecisionSpec.START }.unit)
    }

    @Test fun alternativesComeFromTheSpecNotFromScreens() {
        val t = tradeOffs(sam)
        assertEquals(listOf(null, OPTION_STAY, OPTION_LATER), t.map { it.optionId })
        assertFalse(t[0].bufferKeptWithoutChanges)
        // Keeping things as they are: never below 9,000.
        assertEquals(f(9_000), t[1].worstMinimumCash)
        // The same exchange 3 months later (June–November): lowest is November, 1,200, above the 1,000 cushion.
        assertTrue(t[2].bufferKeptWithoutChanges)
        assertEquals(f(1_200), t[2].worstMinimumCash)
    }

    @Test fun whatIfAndCorrectionStaySeparateAndKeepHistory() {
        val g = startGraph(sam)
        val tried = g.explore(Hypothesis.WhatIfInput(DecisionSpec.START, f(5)))
        assertTrue(tried.node.hypothetical)
        assertEquals(InputState.Exact(f(2)), tried.node.session.state(DecisionSpec.START))
        val fixed = g.correct(Correction.AnswerInput("living_abroad", InputState.Exact(f(400))))
        // 3,900 - 6 × 400 = 1,500 in August: now above the cushion.
        assertEquals(f(1_500), fixed.graph.briefing(fixed.node.id).evidence.worstStressTest.worstMinimumCash)
        assertEquals(f(-300), fixed.graph.briefing("n0").evidence.worstStressTest.worstMinimumCash)
        assertEquals(2, fixed.graph.path(fixed.node.id).size)
    }

    @Test fun correctingTheSituationAlsoMovesTheBaseline() {
        val g = startGraph(noor).correct(Correction.AnswerInput(DecisionSpec.INCOME, InputState.Exact(f(5_200))))
        // Baseline now gains 600 a month: 2,500 + 12 × 600 - 1,500.
        assertEquals(f(8_200), g.graph.briefing(g.node.id).scenarios.getValue("baseline").closingCash)
    }

    @Test fun personalLimitsApplyToCustomDecisions() {
        val g = startGraph(noor)
        val offered = g.briefing().offeredRepair!!
        val rejected = g.correct(Correction.RejectRepair(offered, RejectionScope.THIS_AMOUNT_OR_MORE, "I support my parents"))
        val after = rejected.graph.briefing(rejected.node.id)
        assertTrue(after.offeredRepair == null || after.offeredRepair!!.monthly < offered.monthly)
        assertEquals("I support my parents", rejected.node.session.constraints.rejections.single().reason)
    }

    @Test fun incompleteOrImpossibleSpecsAreRefusedNotGuessed() {
        val draft = interpretDecision("What happens if I study abroad for six months?")
        assertTrue(draft.recognized)
        assertEquals("Studying abroad", draft.title)
        assertEquals(6, draft.durationMonths)
        assertTrue(draft.changes.all { it.amount == null })
        val spec = draft.toSpec(Situation(f(5_000), Amount.Known(f(3_000)), Amount.Known(f(2_000))))
        assertEquals(4, spec.problems().size)
        assertFailsWith<IllegalArgumentException> { spec.toSession() }

        val tooMuch = ExampleDecisions.noorFewerHours.copy(changes = listOf(
            FinancialChange("lost_pay", "Pay", ChangeKind.LESS_INCOME, Amount.Known(f(6_000))),
        ))
        assertTrue(tooMuch.problems().any { "more than your pay" in it })
    }

    @Test fun interpreterOnlyTakesFiguresThePersonTyped() {
        val car = interpretDecision("What if I buy a car in 3 months for CHF 15,000?")
        assertEquals("Buying a car", car.title)
        assertEquals(3, car.startMonth)
        assertEquals(Amount.Known(f(15_000)), car.changes.first { it.kind == ChangeKind.ONE_OFF_COST }.amount)
        assertNull(car.changes.first { it.kind == ChangeKind.MORE_SPENDING }.amount)

        val hours = interpretDecision("What happens if I reduce my working hours to 80%?")
        assertEquals("Working fewer hours", hours.title)
        assertTrue(hours.changes.all { it.amount == null })

        val childcare = interpretDecision("What if I pay for childcare?")
        assertNotEquals("Buying a car", childcare.title)

        val pension = interpretDecision("Should I pay more into my pension?")
        assertFalse(pension.recognized)
        assertTrue(FinancialDomain.PENSION in pension.coverage.unsupportedDomains)
    }
}

class AiAndResearchTest {
    @Test fun aiReadingKeepsStructureAndDropsNumbersAndInventedPlaces() {
        val q = "What if I studied abroad in Amsterdam?"
        val builtIn = interpretDecision(q)
        assertEquals("Amsterdam", builtIn.location)
        val json = """{"title":"Studying in Amsterdam","location":"Amsterdam","changes":[
            {"label":"Extra rent in Amsterdam","kind":"MORE_SPENDING","while_it_lasts":true},
            {"label":"Tuition fees","kind":"ONE_OFF_COST"},
            {"label":"Erasmus grant","kind":"MORE_INCOME","while_it_lasts":true},
            {"label":"Costs 1500 a month","kind":"LESS_SPENDING"},
            {"label":"Win the lottery","kind":"JACKPOT"}]}"""
        val r = readAiProposal(q, builtIn, json)
        assertEquals("Amsterdam", r.location)
        // Only the kind the built-in reading lacked survives; amounts are always null.
        assertEquals(listOf(ChangeKind.MORE_INCOME), r.extraChanges.map { it.kind })
        assertTrue(r.extraChanges.all { it.amount == null })
        assertEquals(ChangeTiming.WHILE_IT_LASTS, r.extraChanges.single().timing)
        assertEquals(4, r.dropped)
        // A place the person never typed is rejected; malformed output yields nothing.
        assertNull(readAiProposal(q, builtIn, """{"location":"Paris","changes":[]}""").location)
        assertTrue(readAiProposal(q, builtIn, "not json").extraChanges.isEmpty())
    }

    @Test fun rentResearchIsOfferedOnlyWhereItCanBeVerified() {
        assertNull(rentResearchRequest("Amsterdam", 1, 2026))
        val req = rentResearchRequest("Lausanne", 1, 2026)!!
        val outcome = runResearch(req, null, ResearchCache.snapshot)
        assertEquals(ResearchMode.CACHED, outcome.mode)
        val best = outcome.verified.first().estimate!!
        assertEquals(MarketCategory.HOUSING_NET_RENT, best.category)
        assertTrue(best.source.url.startsWith("https://"))
    }

    @Test fun researchedAmountsKeepExternalProvenance() {
        val spec = ExampleDecisions.samStudyAbroad.let { s ->
            s.copy(changes = s.changes.map { if (it.id == "living_abroad") it.copy(amount = Amount.Estimate(f(500)), source = "FSO", sourceRef = "market:x") else it })
        }
        val sc = spec.decisionScenario(brief(spec.toSession()).evidence.worstCaseInputs)
        assertEquals(Provenance.EXTERNAL_SOURCE, sc.recurringChanges.single { it.labelKey == "Extra living costs abroad" }.provenance)
    }
}

class FindAWayTest {
    @Test fun samsStrongestWayIsTheUncertainLivingCost() {
        val d = findWays(ExampleDecisions.samStudyAbroad.toSession())
        assertFalse(d.keepsCushion)
        val first = d.ways.first() as Way.Amount
        assertEquals("living_abroad", first.inputId)
        assertEquals(f(483), first.impact.passesUpTo)
        // Going three months later is a tested route that keeps the cushion (within 12 months).
        assertTrue(d.ways.any { it is Way.Route && it.optionId == OPTION_LATER && it.works })
        assertTrue(d.ways.any { it is Way.Timing && it.inputId == DecisionSpec.DURATION })
    }

    @Test fun noorsWaysIncludeLaterStartAndNeverTrimProtectedSpending() {
        val s = ExampleDecisions.noorFewerHours.toSession()
        val d = findWays(s)
        assertEquals(f(523), (d.ways.first() as Way.Amount).impact.passesFrom)
        assertEquals(f(7), d.ways.filterIsInstance<Way.Timing>().single { it.inputId == DecisionSpec.START }.impact.passesFrom)
        // Any offered trim stays within what can be cut (4,600 - 2,400 protected).
        d.ways.filterIsInstance<Way.Trim>().forEach { assertTrue(it.cut.monthly <= f(2_200)) }
    }

    @Test fun alexsWaysComeFromTheSameGenericSearch() {
        val d = findWays(SampleData.alexEvolvingSession)
        assertEquals(f(1_500), (d.ways.first() as Way.Amount).impact.passesUpTo)
        assertTrue(d.ways.any { it is Way.Route && it.optionId == "delay" && it.works })
    }

    @Test fun aCustomDecisionWorksAndNoWayIsInventedWhenNoneExists() {
        val car = DecisionSpec(
            intention = "What if I buy a car?", title = "Buying a car",
            situation = Situation(f(3_000), Amount.Known(f(4_400)), Amount.Known(f(3_900)), cannotCut = f(3_900)),
            changes = listOf(
                FinancialChange("price", "Purchase price", ChangeKind.ONE_OFF_COST, Amount.Known(f(12_000))),
                FinancialChange("running", "Running costs", ChangeKind.MORE_SPENDING, Amount.Between(f(200), f(400))),
            ),
            laterBy = 0,
        )
        val d = findWays(car.toSession())
        // +500 pay margin - 400 running = +100 a month at worst: a cheaper car works, up to a price the engine found.
        assertFalse(d.keepsCushion)
        val price = d.ways.filterIsInstance<Way.Amount>().single { it.inputId == "price" }
        assertTrue(price.works)
        assertEquals(f(3_100), price.impact.passesUpTo)
        // Nothing can be trimmed: all spending is protected.
        assertTrue(d.ways.none { it is Way.Trim })

        // With no pay margin the running costs drain cash whatever the price: only keeping things as they are works.
        val tight = findWays(car.copy(situation = car.situation.copy(monthlyIncome = Amount.Known(f(3_900)))).toSession())
        assertEquals(listOf(OPTION_STAY), tight.ways.filter { it.works }.map { (it as Way.Route).optionId })
        assertTrue(tight.ways.none { it is Way.Trim })
    }
}
