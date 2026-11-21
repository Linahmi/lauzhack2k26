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
data class DecisionInput(
    val id: String,
    val questionKey: String,
    val low: Chf,
    val high: Chf,
    val step: Chf,
    /** What this input means in market terms (enables market estimates for it); null = none. */
    val market: MarketQuery? = null,
) {
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
    /** Confirmed answers (missing entries mean Unknown). Only corrections change these. */
    val values: Map<String, InputState> = emptyMap(),
    val stress: StressSet,
    /** Default minimum month-end cash buffer of the scenario (ASSUMED unless the user sets one). */
    val floor: Chf,
    /** Upper end of the modelled action space: monthly flexible-spending cuts searched (ASSUMED). */
    val repairCap: Chf,
    val repairStep: Chf = Chf.francs(1),
    /** Inputs already asked about (answered or "I'm not sure"): never asked again. */
    val askedInputIds: Set<String> = emptySet(),
    val seenInsightIds: Set<String> = emptySet(),
    /** User-confirmed personal limits (only set through corrections). */
    val constraints: PersonalConstraints = PersonalConstraints(),
    /** Other modelled decisions, e.g. "stay". Selected only through a Hypothesis. */
    val options: List<DecisionOption> = emptyList(),
    val activeOptionId: String? = null,
    /** What-if values for a hypothetical branch; never mixed into [values]. */
    val hypotheticalValues: Map<String, Chf> = emptyMap(),
    /** What-if spending cut applied to the plan in this branch (amount + timing). */
    val plannedCut: ExpenseReduction? = null,
    /** Label of the main decision (e.g. "option.move"); alternatives carry their own. */
    val decisionLabelKey: String = "option.main",
    /** Versioned market estimates available to this session (external context, never facts). */
    val marketData: MarketSnapshot? = null,
    /** inputId -> estimateId tried as a what-if; the value itself is in [hypotheticalValues]. */
    val marketAssumptions: Map<String, String> = emptyMap(),
    /** What the user did with each estimate (tried / replaced by own value / rejected). */
    val marketReviews: Map<String, MarketReview> = emptyMap(),
    /** Builds the main decision scenario from concrete input values. */
    val decision: (Map<String, Chf>) -> Scenario,
) {
    init {
        require(inputs.map { it.id }.toSet().size == inputs.size) { "duplicate input ids" }
        require(values.keys.all { k -> inputs.any { it.id == k } }) { "values for undeclared inputs" }
        require(hypotheticalValues.keys.all { k -> inputs.any { it.id == k } }) { "what-if for undeclared inputs" }
        require(activeOptionId == null || options.any { it.id == activeOptionId }) { "unknown option '$activeOptionId'" }
        plannedCut?.let { require(it.startMonth in 0 until profile.horizonMonths && !it.monthly.isNegative) { "invalid planned cut" } }
        marketAssumptions.forEach { (inputId, estimateId) ->
            val e = requireNotNull(marketData?.estimate(estimateId)) { "unknown market estimate '$estimateId'" }
            require(hypotheticalValues[inputId] == e.value.point) { "market what-if for '$inputId' must use the estimate's value" }
        }
    }

    /** True if this state contains any what-if (not the user's confirmed situation). */
    val isHypothetical: Boolean get() = hypotheticalValues.isNotEmpty() || activeOptionId != null || plannedCut != null

    fun state(inputId: String): InputState = values[inputId] ?: InputState.Unknown

    /** The cash buffer actually tested: the user's, else the scenario default. */
    val effectiveFloor: Chf get() = constraints.minCashBuffer ?: floor

    fun scenarioFor(values: Map<String, Chf>): Scenario {
        val base = (options.firstOrNull { it.id == activeOptionId }?.build ?: decision)(values)
        return plannedCut?.let { base.withExpenseReduction(profile.horizonMonths, it) } ?: base
    }

    val activeLabelKey: String get() = options.firstOrNull { it.id == activeOptionId }?.labelKey ?: decisionLabelKey
}

/** An alternative decision the model can evaluate with the same inputs (e.g. not moving). */
class DecisionOption(val id: String, val labelKey: String, val build: (Map<String, Chf>) -> Scenario)

/** Record an answer ("I'm not sure" = InputState.Unknown). Recompute with brief(). */
fun DecisionSession.answer(inputId: String, value: InputState): DecisionSession {
    val input = requireNotNull(inputs.firstOrNull { it.id == inputId }) { "unknown input '$inputId'" }
    if (value is InputState.Range) {
        require((value.high - value.low).rappen % input.step.rappen == 0L) { "range must be a whole number of ${input.step} steps" }
    }
    // The user's own value replaces a market what-if for this input (never the other way round).
    val replacedEstimate = marketAssumptions[inputId]?.takeIf { value !is InputState.Unknown }
    return copy(
        values = values + (inputId to value),
        askedInputIds = askedInputIds + inputId,
        hypotheticalValues = if (replacedEstimate != null) hypotheticalValues - inputId else hypotheticalValues,
        marketAssumptions = if (replacedEstimate != null) marketAssumptions - inputId else marketAssumptions,
        marketReviews = replacedEstimate?.let { marketReviews + (it to MarketReview.REPLACED_BY_USER_VALUE) } ?: marketReviews,
    )
}

fun DecisionSession.markSeen(insightIds: Collection<String>): DecisionSession =
    copy(seenInsightIds = seenInsightIds + insightIds)

/** A repair start month verified against EVERY tested input value and stress case. */
data class RepairOption(
    val startMonth: Int,
    /** Smallest cut that works AND respects the user's personal constraints (null: none). */
    val minimalMonthlyReduction: Chf?,
    val candidatesChecked: Int,
    /** Smallest cut that works financially within the modelled action space, ignoring personal limits. */
    val financialMinimum: Chf? = minimalMonthlyReduction,
    /** When nothing acceptable remains: the constraints that exclude [financialMinimum]. */
    val blockedBy: List<String> = emptyList(),
) {
    val feasible: Boolean get() = minimalMonthlyReduction != null
    val financiallyFeasible: Boolean get() = financialMinimum != null
}

/** The repair the briefing currently offers (earliest acceptable start), for "reject" buttons. */
val Briefing.offeredRepair: ExpenseReduction?
    get() = evidence.repairs.firstOrNull { it.feasible }?.let { ExpenseReduction(it.startMonth, it.minimalMonthlyReduction!!) }

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
    const val DISCLOSURE_WHAT_IF = "disclosure.what_if_branch_not_confirmed"
    const val CONSTRAINT_MAX_REDUCTION = "constraint.max_monthly_reduction"
    const val LIMIT_NO_PROBABILITIES = "limitation.no_probabilities"
    const val LIMIT_DEPENDS_ON_INPUT = "limitation.depends_on_unknown_input"
    const val LIMIT_ACTION_SET = "limitation.repair_within_declared_action_only"
    const val LIMIT_NOT_ADVICE = "limitation.not_advice"
    const val LIMIT_QUESTION_HEURISTIC = "limitation.question_order_heuristic"
}

fun brief(session: DecisionSession): Briefing = with(session) {
    // Personal buffer and the selected option apply to EVERY analysis below (deliberate shadowing).
    val floor = effectiveFloor
    val decision: (Map<String, Chf>) -> Scenario = ::scenarioFor

    // 1. Every combination of uncertain input values x every stress case (what-if values are fixed).
    val grids = inputs.associate { it.id to (hypotheticalValues[it.id]?.let(::listOf) ?: it.candidates(state(it.id))) }
    val combinationCount = grids.values.fold(1L) { acc, g -> acc * g.size }
    require(combinationCount <= MAX_VALUE_COMBINATIONS) {
        "$combinationCount input combinations exceed $MAX_VALUE_COMBINATIONS; use coarser steps or answer an input"
    }
    val combos = inputs.fold(listOf(emptyMap<String, Chf>())) { acc, i -> acc.flatMap { m -> grids.getValue(i.id).map { m + (i.id to it) } } }
    // Exact per-month worst case for each combination (no per-case simulation), then the full
    // 22-case stressTest only for the worst combination, whose witness the UI shows.
    val profiles = combos.map { it to stressProfile(profile, decision(it), stress) }
    val worstInputs = profiles.minBy { it.second.worstMinimum }.first
    val worst = stressTest(profile, decision(worstInputs), stress, floor)
    val allPass = profiles.all { it.second.worstMinimum >= floor }
    val nonePass = profiles.none { it.second.worstMinimum >= floor }

    // 2. Skeptic: which uncertain input could change the verdict (others at the worst case).
    val uncertain = inputs.filter { grids.getValue(it.id).size > 1 }
    val impacts = rankUnknowns(profile, uncertain.map { i ->
        val g = grids.getValue(i.id)
        UnknownInput(i.id, g.first(), g.last(), i.step) { v -> decision(worstInputs + (i.id to v)) }
    }, stress, floor)

    // 3. Explorer: repairs that hold for every combination (exact minimum on the CHF grid).
    val repairs = if (allPass) emptyList() else (0 until profile.horizonMonths).map { start ->
        repairAcrossAll(session, profiles.map { it.second }, start)
    }
    val firstRepair = repairs.firstOrNull { it.feasible }
    val firstFinancialRepair = repairs.firstOrNull { it.financiallyFeasible }

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

    // 5a. Market Reality Check for every market-linked input (computed at the same worst case).
    val marketChecks = inputs.mapNotNull { marketCheck(it, worstInputs) }

    val evidence = BriefingEvidence(worstInputs, combos.size, stress.cases(profile.horizonMonths).size, worst, allPass, nonePass, impacts, repairs)
    val inputFacts = inputs.map { i ->
        hypotheticalValues[i.id]?.let { v ->
            marketAssumptions[i.id]?.let { ExplanationFact("input:${i.id}", FactValue.Money(v), Provenance.EXTERNAL_SOURCE, "market:$it") }
                ?: ExplanationFact("input:${i.id}", FactValue.Money(v), Provenance.ASSUMED, "hypothesis:${i.id}")
        }
            ?: inputFact(i, state(i.id))
    }
    val floorProvenance = if (constraints.minCashBuffer != null) Provenance.USER_STATED else Provenance.ASSUMED
    val limitFacts = listOfNotNull(
        constraints.maxMonthlyReduction?.let { fact("max_acceptable_reduction", FactValue.Money(it), "constraint:max_monthly_reduction", Provenance.USER_STATED) },
    )
    val askableIds = impacts.filter { it.changesDecision && it.name !in askedInputIds }.map { it.name }
    val followUpInput = askableIds.firstOrNull()?.let { id -> inputs.first { it.id == id } }

    val actions = buildList {
        followUpInput?.let { add(UserAction("answer:${it.id}", ActionKind.ANSWER_QUESTION, "action.answer_question")) }
        if (firstRepair != null) {
            add(UserAction("explore:repair", ActionKind.EXPLORE_ALTERNATIVE, "action.explore_repair", "decision-repaired"))
            add(UserAction("reject:repair", ActionKind.CORRECT_ASSUMPTION, "action.reject_repair"))
        }
        if (firstRepair == null && firstFinancialRepair != null) {
            add(UserAction("edit:constraints", ActionKind.CORRECT_ASSUMPTION, "action.edit_constraints"))
        }
        add(UserAction("explore:baseline", ActionKind.EXPLORE_ALTERNATIVE, "action.compare_baseline", "baseline"))
        inputs.forEach { add(UserAction("correct:${it.id}", ActionKind.CORRECT_ASSUMPTION, "action.correct_input")) }
        marketChecks.forEach { c ->
            if (c.offersEstimate) add(UserAction("market:try:${c.inputId}", ActionKind.EXPLORE_ALTERNATIVE, "action.try_market_assumption"))
            if (c.benchmarks.isNotEmpty()) add(UserAction("market:why:${c.inputId}", ActionKind.INSPECT, "action.why_this_estimate"))
        }
        add(UserAction("inspect", ActionKind.INSPECT, "action.inspect"))
    }
    val actionIds = actions.map { it.id }

    // 5. Candidate insights.
    val candidates = buildList {
        val depends = !allPass && !nonePass
        val stressFacts = listOf(
            fact("worst_min_cash", FactValue.Money(worst.worstMinimumCash), "stress:worst"),
            fact("floor", FactValue.Money(floor), "session:floor", floorProvenance),
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
        // Market context: only for inputs whose value could change the verdict (computed).
        marketChecks.filter { it.decisionSensitive }.forEach { c ->
            add(FinancialInsight("market:${c.inputId}", InsightType.MARKET_SENSITIVE_ASSUMPTION, InsightSeverity.INFO,
                "insight.market_check.${c.status.name.lowercase()}", c.facts,
                listOf("decision-worst"), listOf(c.inputId),
                listOfNotNull("market:try:${c.inputId}".takeIf { c.offersEstimate }, "market:why:${c.inputId}".takeIf { c.benchmarks.isNotEmpty() },
                    "answer:${c.inputId}".takeIf { c.inputId in askableIds }, "correct:${c.inputId}"),
                c.limitationKeys + Keys.LIMIT_NO_PROBABILITIES,
                listOfNotNull(RankReason.COULD_CHANGE_WITH_MISSING_ANSWER.takeIf { c.needsExactValue }, RankReason.ACTIONABLE)))
        }
        if (!allPass) {
            if (firstRepair != null) {
                add(FinancialInsight("repair", InsightType.FEASIBLE_REPAIR, InsightSeverity.INFO, "insight.feasible_repair",
                    listOfNotNull(
                        fact("monthly_reduction", FactValue.Money(firstRepair.minimalMonthlyReduction!!), "repair:start:${firstRepair.startMonth}"),
                        fact("start_month", FactValue.Month(firstRepair.startMonth), "repair:start:${firstRepair.startMonth}"),
                        fact("repair_cap", FactValue.Money(repairCap), "session:repairCap", Provenance.ASSUMED),
                        fact("candidates_checked", FactValue.Count(firstRepair.candidatesChecked), "repair:start:${firstRepair.startMonth}"),
                        // Shown when the user's limits pushed the offer above the financial minimum.
                        firstFinancialRepair?.takeIf { it != firstRepair || it.financialMinimum != it.minimalMonthlyReduction }?.let {
                            fact("financial_min_reduction", FactValue.Money(it.financialMinimum!!), "repair:start:${it.startMonth}")
                        },
                    ) + limitFacts,
                    listOf("decision-repaired", "decision-worst"), inputs.map { it.id }, listOf("explore:repair", "reject:repair"),
                    listOf(Keys.LIMIT_ACTION_SET, Keys.LIMIT_NOT_ADVICE),
                    listOf(RankReason.ACTIONABLE, RankReason.SUPPORTED_BY_ALL_TESTED_CASES)))
                val latest = repairs.last { it.feasible }
                add(FinancialInsight("deadline", InsightType.ACTION_DEADLINE, InsightSeverity.INFO, "insight.action_deadline",
                    listOf(
                        fact("latest_start_month", FactValue.Month(latest.startMonth), "repair:start:${latest.startMonth}"),
                        fact("monthly_reduction_at_latest", FactValue.Money(latest.minimalMonthlyReduction!!), "repair:start:${latest.startMonth}"),
                        fact("repair_cap", FactValue.Money(repairCap), "session:repairCap", Provenance.ASSUMED),
                    ) + limitFacts,
                    listOf("decision-worst"), inputs.map { it.id }, listOf("explore:repair"),
                    listOf(Keys.LIMIT_ACTION_SET, Keys.LIMIT_NOT_ADVICE),
                    listOf(RankReason.ACTIONABLE, RankReason.SUPPORTED_BY_ALL_TESTED_CASES)))
            } else if (firstFinancialRepair != null) {
                // Financially possible, but not within what the user accepts: say exactly that.
                add(FinancialInsight("no-acceptable-repair", InsightType.NO_ACCEPTABLE_REPAIR, InsightSeverity.WARNING, "insight.no_acceptable_repair",
                    listOf(
                        fact("financial_min_reduction", FactValue.Money(firstFinancialRepair.financialMinimum!!), "repair:start:${firstFinancialRepair.startMonth}"),
                        fact("financial_start_month", FactValue.Month(firstFinancialRepair.startMonth), "repair:start:${firstFinancialRepair.startMonth}"),
                        fact("constraints_blocking", FactValue.Count(firstFinancialRepair.blockedBy.size), "repair:start:${firstFinancialRepair.startMonth}"),
                    ) + limitFacts,
                    listOf("decision-worst"), inputs.map { it.id }, listOf("edit:constraints", "inspect"),
                    listOf(Keys.LIMIT_ACTION_SET), listOf(RankReason.SUPPORTED_BY_ALL_TESTED_CASES)))
            } else {
                add(FinancialInsight("no-repair", InsightType.NO_REPAIR_IN_SCOPE, InsightSeverity.WARNING, "insight.no_repair_in_scope",
                    listOf(fact("repair_cap", FactValue.Money(repairCap), "session:repairCap", Provenance.ASSUMED)) + limitFacts,
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
                unknownFacts(impacts.first { it.name == i.id }, session),
                marketEstimateIds = marketChecks.firstOrNull { it.inputId == i.id && it.offersEstimate }?.benchmarks?.map { it.estimate.id }.orEmpty())
        },
        actions = actions,
        moreInsights = ranked.drop(1),
        disclosureKeys = listOfNotNull(
            Keys.DISCLOSURE_FICTIONAL, Keys.DISCLOSURE_HYPOTHETICAL, Keys.DISCLOSURE_TESTED_CASES, Keys.DISCLOSURE_CASH_ONLY,
            Keys.DISCLOSURE_UNKNOWN_BOUNDED.takeIf { uncertain.isNotEmpty() },
            Keys.DISCLOSURE_WHAT_IF.takeIf { isHypothetical },
            MarketKeys.DISCLOSURE_MARKET_ASSUMPTION.takeIf { marketAssumptions.isNotEmpty() },
        ),
        scenarios = scenarios,
        evidence = evidence,
        marketChecks = marketChecks,
    )
}

private fun tier(t: InsightType) = when (t) {
    InsightType.LIQUIDITY_BREACH, InsightType.FRAGILE_PLAN, InsightType.ROBUST_CONCLUSION -> 0
    InsightType.DECISION_CHANGING_UNKNOWN -> 1
    // Market context sits after the Skeptic question and never above the safety verdict (tier 0).
    InsightType.MARKET_SENSITIVE_ASSUMPTION -> 2
    InsightType.FEASIBLE_REPAIR, InsightType.NO_ACCEPTABLE_REPAIR, InsightType.NO_REPAIR_IN_SCOPE -> 3
    InsightType.ACTION_DEADLINE -> 4
}

internal fun DecisionInput.candidates(state: InputState): List<Chf> = when (state) {
    is InputState.Exact -> listOf(state.value)
    is InputState.Range -> generateSequence(state.low) { it + step }.takeWhile { it <= state.high }.toList()
    InputState.Unknown -> generateSequence(low) { it + step }.takeWhile { it <= high }.toList()
}

private fun fact(key: String, value: FactValue, ref: String, provenance: Provenance = Provenance.COMPUTED) =
    ExplanationFact(key, value, provenance, ref)

internal fun inputFact(i: DecisionInput, s: InputState) = when (s) {
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
 * For [start]: (1) the smallest monthly cut on the CHF grid that passes the stress set for EVERY
 * combination (financial minimum, action-space cap only), then (2) the smallest such cut the
 * user's personal constraints allow.
 *
 * Exact, no search: a cut r from [start] adds r × (t − start + 1) to month t in every stress
 * case, so month t needs r ≥ (floor − worstByMonth[t]) / (t − start + 1), rounded up to the grid;
 * months before [start] (and the opening balance) must already be at the floor. Cash only grows
 * with r, so every larger grid value passes too — (2) is the first one no constraint excludes.
 * [RepairOption.candidatesChecked] = grid values covered (0..answer), all smaller ones proven short.
 */
private fun repairAcrossAll(s: DecisionSession, profiles: List<StressProfile>, start: Int): RepairOption {
    val floor = s.effectiveFloor
    val step = s.repairStep.rappen
    val gridCount = { r: Long -> (r / step + 1).toInt() }
    var need = 0L
    for (p in profiles) {
        if (p.nominal.openingCash < floor) return RepairOption(start, null, gridCount(s.repairCap.rappen), null)
        p.worstByMonth.forEachIndexed { t, w ->
            if (w < floor) {
                val months = t - start + 1
                if (months <= 0) return RepairOption(start, null, gridCount(s.repairCap.rappen), null)
                need = maxOf(need, ceilDiv((floor - w).rappen, months.toLong()))
            }
        }
    }
    val r = ceilDiv(need, step) * step
    if (r > s.repairCap.rappen) return RepairOption(start, null, gridCount(s.repairCap.rappen), null)
    val financial = Chf(r)
    // A cut larger than a month's expenses is not a valid scenario (simulate() rejects it).
    val minExpenses = profiles.minOf { p -> p.nominal.months.filter { it.month >= start }.minOf { it.expenses } }

    var a = financial
    while (a <= s.repairCap) {
        if (s.constraints.excludes(start, a).isEmpty()) {
            require(a <= minExpenses) { "monthlyExpenses cannot be negative" }
            return RepairOption(start, a, gridCount(r), financial)
        }
        a += s.repairStep
    }
    require(financial <= minExpenses) { "monthlyExpenses cannot be negative" }
    return RepairOption(start, null, gridCount(r), financial, s.constraints.excludes(start, financial))
}

private fun ceilDiv(x: Long, d: Long) = if (x <= 0) 0L else (x + d - 1) / d
