package ch.ripple.core

/*
 * brief(): turns the Break My Plan searches into ONE primary insight, at most ONE question and
 * a few actions. Pure and deterministic. No numbers are produced here: every figure comes from
 * stressTest / rankUnknowns / findMinimalMonthlyRepair, which all run on simulate().
 */

/** What the user told us about one input. Unknown and Range are never replaced by a guess. */
sealed interface InputState {
    val provenance: Provenance

    data class Exact(val value: Chf) : InputState {
        init { require(!value.isNegative) { "value cannot be negative" } }
        override val provenance get() = Provenance.USER_STATED
    }

    data class Range(val low: Chf, val high: Chf) : InputState {
        init { require(!low.isNegative && low <= high) { "need 0 <= low <= high" } }
        override val provenance get() = Provenance.USER_ESTIMATE_RANGE
    }

    data object Unknown : InputState {
        override val provenance get() = Provenance.UNKNOWN
    }
}

/** An input of the decision. [low]..[high] (every [step]) are the bounds searched while it is Unknown. */
data class DecisionInput(val id: String, val questionKey: String, val low: Chf, val high: Chf, val step: Chf) {
    init {
        require(!low.isNegative && low <= high) { "need 0 <= low <= high" }
        require(step.rappen > 0 && (high - low).rappen % step.rappen == 0L) { "bounds must be a whole number of steps" }
    }
}

data class DecisionSession(
    val profile: FinancialProfile,
    /** The "don't do it" path, used for the before/after comparison. */
    val baseline: Scenario,
    val inputs: List<DecisionInput>,
    /** Missing entries mean Unknown. */
    val values: Map<String, InputState> = emptyMap(),
    val stress: StressSet,
    /** The user's chosen minimum month-end cash buffer. */
    val floor: Chf,
    /** Largest monthly flexible-spending cut the user approves (the only repair action modelled). */
    val repairCap: Chf,
    val repairStep: Chf = Chf.francs(1),
    /** Inputs already asked about (answered or "I'm not sure"): never asked again. */
    val askedInputIds: Set<String> = emptySet(),
    val seenInsightIds: Set<String> = emptySet(),
    /** Builds the decision scenario from concrete input values. */
    val decision: (Map<String, Chf>) -> Scenario,
) {
    init {
        require(inputs.map { it.id }.toSet().size == inputs.size) { "duplicate input ids" }
        require(values.keys.all { k -> inputs.any { it.id == k } }) { "values for undeclared inputs" }
    }

    fun state(inputId: String): InputState = values[inputId] ?: InputState.Unknown
}

/** Record an answer ("I'm not sure" = InputState.Unknown). Recompute with brief(). */
fun DecisionSession.answer(inputId: String, value: InputState): DecisionSession {
    val input = requireNotNull(inputs.firstOrNull { it.id == inputId }) { "unknown input '$inputId'" }
    if (value is InputState.Range) {
        require((value.high - value.low).rappen % input.step.rappen == 0L) { "range must be a whole number of ${input.step} steps" }
    }
    return copy(values = values + (inputId to value), askedInputIds = askedInputIds + inputId)
}

fun DecisionSession.markSeen(insightIds: Collection<String>): DecisionSession =
    copy(seenInsightIds = seenInsightIds + insightIds)

/** A repair start month verified against EVERY tested input value and stress case. */
data class RepairOption(val startMonth: Int, val minimalMonthlyReduction: Chf?, val candidatesChecked: Int) {
    val feasible: Boolean get() = minimalMonthlyReduction != null
}

data class BriefingEvidence(
    /** The input values (within the stated bounds) that gave the lowest cash. */
    val worstCaseInputs: Map<String, Chf>,
    val valueCombinationsChecked: Int,
    val stressCasesPerCombination: Int,
    /** Stress test of the plan at [worstCaseInputs] (witness months, full monthly records). */
    val worstStressTest: StressTest,
    val passesForAllTestedValues: Boolean,
    val failsForAllTestedValues: Boolean,
    /** One per uncertain input, best question first. Other unknowns held at [worstCaseInputs]. */
    val unknownImpacts: List<UnknownImpact>,
    /** Empty when the plan already passes. */
    val repairs: List<RepairOption>,
)

/** Guard: the joint search is exhaustive, so keep the grid small (coarser steps or fewer unknowns). */
const val MAX_VALUE_COMBINATIONS = 5_000

object Keys {
    const val DISCLOSURE_FICTIONAL = "disclosure.fictional"
    const val DISCLOSURE_HYPOTHETICAL = "disclosure.hypothetical_not_prediction"
    const val DISCLOSURE_TESTED_CASES = "disclosure.tested_cases_only"
    const val DISCLOSURE_CASH_ONLY = "disclosure.cash_only_month_end"
    const val DISCLOSURE_UNKNOWN_BOUNDED = "disclosure.unknown_inputs_searched_within_bounds"
    const val LIMIT_NO_PROBABILITIES = "limitation.no_probabilities"
    const val LIMIT_DEPENDS_ON_INPUT = "limitation.depends_on_unknown_input"
    const val LIMIT_ACTION_SET = "limitation.repair_within_declared_action_only"
    const val LIMIT_NOT_ADVICE = "limitation.not_advice"
    const val LIMIT_QUESTION_HEURISTIC = "limitation.question_order_heuristic"
}

fun brief(session: DecisionSession): Briefing = with(session) {
    // 1. Every combination of uncertain input values x every stress case.
    val grids = inputs.associate { it.id to it.candidates(state(it.id)) }
    val combinationCount = grids.values.fold(1L) { acc, g -> acc * g.size }
    require(combinationCount <= MAX_VALUE_COMBINATIONS) {
        "$combinationCount input combinations exceed $MAX_VALUE_COMBINATIONS; use coarser steps or answer an input"
    }
    val combos = inputs.fold(listOf(emptyMap<String, Chf>())) { acc, i -> acc.flatMap { m -> grids.getValue(i.id).map { m + (i.id to it) } } }
    val tests = combos.map { it to stressTest(profile, decision(it), stress, floor) }
    val (worstInputs, worst) = tests.minBy { it.second.worstMinimumCash }
    val allPass = tests.all { it.second.passes }
    val nonePass = tests.none { it.second.passes }

    // 2. Skeptic: which uncertain input could change the verdict (others at the worst case).
    val uncertain = inputs.filter { grids.getValue(it.id).size > 1 }
    val impacts = rankUnknowns(profile, uncertain.map { i ->
        val g = grids.getValue(i.id)
        UnknownInput(i.id, g.first(), g.last(), i.step) { v -> decision(worstInputs + (i.id to v)) }
    }, stress, floor)

    // 3. Explorer: repairs that hold for every combination (exact minimum on the CHF grid).
    val repairs = if (allPass) emptyList() else (0 until profile.horizonMonths).map { start ->
        repairAcrossAll(session, decision(worstInputs), combos.map(decision), start)
    }
    val firstRepair = repairs.firstOrNull { it.feasible }

    // 4. Timelines for Explore.
    val scenarios = buildMap {
        put("baseline", simulate(profile, baseline))
        put("decision-nominal", simulate(profile, decision(worstInputs)))
        put("decision-worst", worst.worst.result)
        firstRepair?.let { r ->
            val repaired = decision(worstInputs).withExpenseReduction(profile.horizonMonths, ExpenseReduction(r.startMonth, r.minimalMonthlyReduction!!))
            put("decision-repaired", stressTest(profile, repaired, stress, floor).worst.result)
        }
    }

    val evidence = BriefingEvidence(worstInputs, combos.size, stress.cases(profile.horizonMonths).size, worst, allPass, nonePass, impacts, repairs)
    val inputFacts = inputs.map { inputFact(it, state(it.id)) }
    val askableIds = impacts.filter { it.changesDecision && it.name !in askedInputIds }.map { it.name }
    val followUpInput = askableIds.firstOrNull()?.let { id -> inputs.first { it.id == id } }

    val actions = buildList {
        followUpInput?.let { add(UserAction("answer:${it.id}", ActionKind.ANSWER_QUESTION, "action.answer_question")) }
        if (firstRepair != null) add(UserAction("explore:repair", ActionKind.EXPLORE_ALTERNATIVE, "action.explore_repair", "decision-repaired"))
        add(UserAction("explore:baseline", ActionKind.EXPLORE_ALTERNATIVE, "action.compare_baseline", "baseline"))
        inputs.forEach { add(UserAction("correct:${it.id}", ActionKind.CORRECT_ASSUMPTION, "action.correct_input")) }
        add(UserAction("inspect", ActionKind.INSPECT, "action.inspect"))
    }
    val actionIds = actions.map { it.id }

    // 5. Candidate insights.
    val candidates = buildList {
        val depends = !allPass && !nonePass
        val stressFacts = listOf(
            fact("worst_min_cash", FactValue.Money(worst.worstMinimumCash), "stress:worst"),
            fact("floor", FactValue.Money(floor), "session:floor", Provenance.USER_STATED),
            fact("cases_checked", FactValue.Count(combos.size * evidence.stressCasesPerCombination), "stress:all"),
            fact("low_income_months", FactValue.Months(worst.worst.lowIncomeMonths), "stress:worst"),
        )
        if (allPass) {
            add(FinancialInsight("robust", InsightType.ROBUST_CONCLUSION, InsightSeverity.REASSURING, "insight.robust",
                stressFacts + fact("margin", FactValue.Money(worst.worstMinimumCash - floor), "stress:worst") + inputFacts,
                listOf("decision-nominal", "decision-worst", "baseline"), inputs.map { it.id }, actionIds,
                listOf(Keys.LIMIT_NO_PROBABILITIES), emptyList()))
        } else {
            val nominalPasses = scenarios.getValue("decision-nominal").minimumCash >= floor
            val type = if (nominalPasses) InsightType.FRAGILE_PLAN else InsightType.LIQUIDITY_BREACH
            val base = if (nominalPasses) "insight.fragile_plan" else "insight.liquidity_breach"
            add(FinancialInsight("breach", type,
                if (worst.worstMinimumCash.isNegative) InsightSeverity.CRITICAL else InsightSeverity.WARNING,
                if (depends) "$base.depends_on_input" else base,
                stressFacts + listOfNotNull(
                    worst.firstViolationMonth?.let { fact("first_violation_month", FactValue.Month(it), "stress:worst:month:$it") },
                    fact("floor_shortfall", FactValue.Money(worst.floorShortfall), "stress:worst"),
                    fact("nominal_min_cash", FactValue.Money(scenarios.getValue("decision-nominal").minimumCash), "scenario:decision-nominal"),
                ) + inputFacts,
                listOf("decision-worst", "decision-nominal", "baseline"), inputs.map { it.id }, actionIds,
                listOfNotNull(Keys.LIMIT_NO_PROBABILITIES, Keys.LIMIT_DEPENDS_ON_INPUT.takeIf { depends }),
                listOf(RankReason.SAFETY_CRITICAL, RankReason.ON_USER_DECISION) +
                    if (depends) RankReason.COULD_CHANGE_WITH_MISSING_ANSWER else RankReason.SUPPORTED_BY_ALL_TESTED_CASES))
        }
        impacts.filter { it.changesDecision }.forEach { u ->
            add(FinancialInsight("unknown:${u.name}", InsightType.DECISION_CHANGING_UNKNOWN, InsightSeverity.INFO,
                "insight.unknown_changes_decision", unknownFacts(u, session),
                listOf("decision-worst"), listOf(u.name), listOfNotNull(if (u.name in askableIds) "answer:${u.name}" else null, "correct:${u.name}"),
                listOf(Keys.LIMIT_QUESTION_HEURISTIC), listOf(RankReason.COULD_CHANGE_WITH_MISSING_ANSWER, RankReason.ACTIONABLE)))
        }
        if (!allPass) {
            if (firstRepair != null) {
                add(FinancialInsight("repair", InsightType.FEASIBLE_REPAIR, InsightSeverity.INFO, "insight.feasible_repair",
                    listOf(
                        fact("monthly_reduction", FactValue.Money(firstRepair.minimalMonthlyReduction!!), "repair:start:${firstRepair.startMonth}"),
                        fact("start_month", FactValue.Month(firstRepair.startMonth), "repair:start:${firstRepair.startMonth}"),
                        fact("repair_cap", FactValue.Money(repairCap), "session:repairCap", Provenance.USER_STATED),
                        fact("candidates_checked", FactValue.Count(firstRepair.candidatesChecked), "repair:start:${firstRepair.startMonth}"),
                    ),
                    listOf("decision-repaired", "decision-worst"), inputs.map { it.id }, listOf("explore:repair"),
                    listOf(Keys.LIMIT_ACTION_SET, Keys.LIMIT_NOT_ADVICE),
                    listOf(RankReason.ACTIONABLE, RankReason.SUPPORTED_BY_ALL_TESTED_CASES)))
                val latest = repairs.last { it.feasible }
                add(FinancialInsight("deadline", InsightType.ACTION_DEADLINE, InsightSeverity.INFO, "insight.action_deadline",
                    listOf(
                        fact("latest_start_month", FactValue.Month(latest.startMonth), "repair:start:${latest.startMonth}"),
                        fact("monthly_reduction_at_latest", FactValue.Money(latest.minimalMonthlyReduction!!), "repair:start:${latest.startMonth}"),
                        fact("repair_cap", FactValue.Money(repairCap), "session:repairCap", Provenance.USER_STATED),
                    ),
                    listOf("decision-worst"), inputs.map { it.id }, listOf("explore:repair"),
                    listOf(Keys.LIMIT_ACTION_SET, Keys.LIMIT_NOT_ADVICE),
                    listOf(RankReason.ACTIONABLE, RankReason.SUPPORTED_BY_ALL_TESTED_CASES)))
            } else {
                add(FinancialInsight("no-repair", InsightType.NO_REPAIR_IN_SCOPE, InsightSeverity.WARNING, "insight.no_repair_in_scope",
                    listOf(fact("repair_cap", FactValue.Money(repairCap), "session:repairCap", Provenance.USER_STATED)),
                    listOf("decision-worst"), inputs.map { it.id }, listOf("inspect"),
                    listOf(Keys.LIMIT_ACTION_SET), listOf(RankReason.SUPPORTED_BY_ALL_TESTED_CASES)))
            }
        }
    }

    // 6. Fixed ranking: tier, then unseen before seen, then severity, then id. The breach/robust
    //    verdict is always tier 0, so being "seen" never demotes a safety finding.
    val ranked = candidates.map { i ->
        i.copy(rankReasons = i.rankReasons + if (i.id in seenInsightIds) RankReason.ALREADY_SEEN else RankReason.NOT_SEEN_YET)
    }.sortedWith(compareBy<FinancialInsight>({ tier(it.type) }, { it.id in seenInsightIds }, { it.severity.ordinal }, { it.id }))

    Briefing(
        primary = ranked.first(),
        followUp = followUpInput?.let { i ->
            FollowUpQuestion("q:${i.id}", i.id, i.questionKey, AnswerSpec.MoneyRange(i.low, i.high, i.step),
                unknownFacts(impacts.first { it.name == i.id }, session))
        },
        actions = actions,
        moreInsights = ranked.drop(1),
        disclosureKeys = listOfNotNull(
            Keys.DISCLOSURE_FICTIONAL, Keys.DISCLOSURE_HYPOTHETICAL, Keys.DISCLOSURE_TESTED_CASES, Keys.DISCLOSURE_CASH_ONLY,
            Keys.DISCLOSURE_UNKNOWN_BOUNDED.takeIf { uncertain.isNotEmpty() },
        ),
        scenarios = scenarios,
        evidence = evidence,
    )
}

private fun tier(t: InsightType) = when (t) {
    InsightType.LIQUIDITY_BREACH, InsightType.FRAGILE_PLAN, InsightType.ROBUST_CONCLUSION -> 0
    InsightType.DECISION_CHANGING_UNKNOWN -> 1
    InsightType.FEASIBLE_REPAIR, InsightType.NO_REPAIR_IN_SCOPE -> 2
    InsightType.ACTION_DEADLINE -> 3
}

private fun DecisionInput.candidates(state: InputState): List<Chf> = when (state) {
    is InputState.Exact -> listOf(state.value)
    is InputState.Range -> generateSequence(state.low) { it + step }.takeWhile { it <= state.high }.toList()
    InputState.Unknown -> generateSequence(low) { it + step }.takeWhile { it <= high }.toList()
}

private fun fact(key: String, value: FactValue, ref: String, provenance: Provenance = Provenance.COMPUTED) =
    ExplanationFact(key, value, provenance, ref)

private fun inputFact(i: DecisionInput, s: InputState) = when (s) {
    is InputState.Exact -> ExplanationFact("input:${i.id}", FactValue.Money(s.value), s.provenance, "input:${i.id}")
    is InputState.Range -> ExplanationFact("input:${i.id}", FactValue.MoneyRange(s.low, s.high), s.provenance, "input:${i.id}")
    InputState.Unknown -> ExplanationFact("input:${i.id}", FactValue.MoneyRange(i.low, i.high), s.provenance, "input:${i.id}")
}

private fun unknownFacts(u: UnknownImpact, s: DecisionSession) = listOfNotNull(
    inputFact(s.inputs.first { it.id == u.name }, s.state(u.name)),
    u.passesUpTo?.let { ExplanationFact("passes_up_to", FactValue.Money(it), Provenance.COMPUTED, "unknown:${u.name}") },
    ExplanationFact("swing", FactValue.Money(u.swing), Provenance.COMPUTED, "unknown:${u.name}"),
    ExplanationFact("values_checked", FactValue.Count(u.worstMinimumCashByValue.size), Provenance.COMPUTED, "unknown:${u.name}"),
)

/**
 * Smallest monthly cut from [start] that passes the stress set for EVERY scenario.
 * Below the worst scenario's own minimum nothing can pass (that scenario is one of them),
 * so the search starts there and then checks each candidate against all scenarios.
 */
private fun repairAcrossAll(s: DecisionSession, worstScenario: Scenario, all: List<Scenario>, start: Int): RepairOption {
    val first = findMinimalMonthlyRepair(s.profile, worstScenario, s.stress, s.floor, start, s.repairCap, s.repairStep)
    var checked = first.candidatesChecked
    var r = first.minimalMonthlyReduction ?: return RepairOption(start, null, checked)
    while (r <= s.repairCap) {
        if (r != first.minimalMonthlyReduction) checked++
        val cut = ExpenseReduction(start, r)
        if (all.all { stressTest(s.profile, it.withExpenseReduction(s.profile.horizonMonths, cut), s.stress, s.floor).passes }) {
            return RepairOption(start, r, checked)
        }
        r += s.repairStep
    }
    return RepairOption(start, null, checked)
}
