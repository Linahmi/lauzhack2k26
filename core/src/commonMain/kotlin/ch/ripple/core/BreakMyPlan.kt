package ch.ripple.core

/*
 * Break My Plan: Break (Guardian) -> Question (Skeptic) -> Repair (Explorer) -> Retest.
 *
 * Every function is an exhaustive search over a small, explicitly declared finite set and
 * reuses simulate() for each case. Statements ("passes", "minimal", "latest") hold ONLY for
 * that declared set, grid and horizon: they are not probabilities, forecasts or advice.
 * Months are zero-indexed like the rest of core; UIs show month + 1.
 */

/**
 * Cardinality-limited income stress: in at most [maxLowIncomeMonths] months, income is lower
 * by [lowIncomeReduction]. Inspired by budgeted uncertainty (Bertsimas & Sim 2004); it is a
 * chosen stress severity, not a calibrated probability.
 */
data class StressSet(val maxLowIncomeMonths: Int, val lowIncomeReduction: Chf) {
    init {
        require(maxLowIncomeMonths >= 0) { "maxLowIncomeMonths cannot be negative" }
        require(!lowIncomeReduction.isNegative) { "lowIncomeReduction cannot be negative" }
    }

    /** Every set of low-income months, ordered by size then lexicographically (deterministic). */
    fun cases(horizonMonths: Int): List<List<Int>> {
        require(maxLowIncomeMonths <= horizonMonths) { "maxLowIncomeMonths exceeds horizon" }
        val out = mutableListOf<List<Int>>()
        fun pick(size: Int, from: Int, acc: List<Int>) {
            if (acc.size == size) { out += acc; return }
            for (m in from until horizonMonths) pick(size, m + 1, acc + m)
        }
        for (size in 0..maxLowIncomeMonths) pick(size, 0, emptyList())
        return out
    }
}

/** One stress case and its full simulation (every month reconciled by simulate()). */
data class StressWitness(val lowIncomeMonths: List<Int>, val result: SimulationResult)

/** Result of checking a scenario against every case of a [StressSet]. */
data class StressTest(val floor: Chf, val cases: List<StressWitness>) {
    /** Lowest-cash case; ties go to the first case in [StressSet.cases] order. */
    val worst: StressWitness get() = cases.minBy { it.result.minimumCash }
    val worstMinimumCash: Chf get() = worst.result.minimumCash

    /** True only if every enumerated case keeps cash (incl. opening balance) >= floor. */
    val passes: Boolean get() = worstMinimumCash >= floor

    /** The worst case if it breaks the floor — the counterexample; null if none exists. */
    val counterexample: StressWitness? get() = worst.takeUnless { passes }

    /** First month-end in the worst case below the floor (null if none). */
    val firstViolationMonth: Int? get() = worst.result.months.firstOrNull { it.closingCash < floor }?.month

    /** How far the worst case falls below the floor (zero if it passes). */
    val floorShortfall: Chf get() = if (passes) Chf.ZERO else floor - worstMinimumCash
}

/** Guardian: simulate every stress case and report the worst one. */
fun stressTest(profile: FinancialProfile, scenario: Scenario, stress: StressSet, floor: Chf): StressTest =
    StressTest(floor, stress.cases(profile.horizonMonths).also {
        simulate(profile, scenario) // validate the unstressed plan first (gaps/overlaps would be hidden by adjusted())
    }.map { low ->
        StressWitness(low, simulate(profile, scenario.adjusted(profile.horizonMonths, incomeCut = { m ->
            if (m in low) stress.lowIncomeReduction else Chf.ZERO
        })))
    })

/**
 * Exact shortcut for the worst case of a [StressSet] without simulating every case.
 * Stress only lowers income, so in any case month t's cash is the nominal cash minus
 * reduction × (low months up to t). The worst case for month t therefore has
 * min(k, t + 1) low months: worstByMonth[t] = nominal[t] − reduction × min(k, t + 1).
 * Same validation as [stressTest]; results are identical (see FastPathEquivalenceTest).
 */
internal class StressProfile(val nominal: SimulationResult, val worstByMonth: List<Chf>) {
    /** Equals stressTest(...).worstMinimumCash (opening balance included). */
    val worstMinimum: Chf get() = worstByMonth.minOf { it }.coerceAtMost(nominal.openingCash)
}

internal fun stressProfile(profile: FinancialProfile, scenario: Scenario, stress: StressSet): StressProfile {
    require(stress.maxLowIncomeMonths <= profile.horizonMonths) { "maxLowIncomeMonths exceeds horizon" }
    val nominal = simulate(profile, scenario)
    if (stress.maxLowIncomeMonths > 0) {
        // Every month is low in some case; stressTest would reject a negative income there.
        nominal.months.forEach { require(it.income >= stress.lowIncomeReduction) { "monthlyIncome cannot be negative" } }
    }
    val r = stress.lowIncomeReduction.rappen
    return StressProfile(nominal, nominal.months.map { m -> m.closingCash - Chf(r * minOf(stress.maxLowIncomeMonths, m.month + 1)) })
}

/** A user-approved action: cut flexible spending by [monthly] from [startMonth] to the horizon end. */
data class ExpenseReduction(val startMonth: Int, val monthly: Chf)

fun Scenario.withExpenseReduction(horizonMonths: Int, r: ExpenseReduction): Scenario =
    adjusted(horizonMonths, expenseCut = { m -> if (m >= r.startMonth) r.monthly else Chf.ZERO })
        .copy(name = "$name, -${r.monthly.format()}/month from month ${r.startMonth + 1}")

/** Adds confirmed obligations to expenses while marking them as unavailable to repairs. */
fun Scenario.withCommitments(horizonMonths: Int, commitments: List<PersonalCommitment>): Scenario {
    if (commitments.isEmpty()) return this
    return withConsequences(horizonMonths, FinancialConsequences(protectedCommitments = commitments))
}

/**
 * Explorer search for one start month: tries 0, step, 2*step, ... <= cap in order, so the
 * first passing value is the smallest on that grid. [retest] is the passing plan's stress test.
 */
data class RepairSearch(
    val startMonth: Int,
    val cap: Chf,
    val step: Chf,
    val candidatesChecked: Int,
    val minimalMonthlyReduction: Chf?,
    val retest: StressTest?,
) {
    val feasible: Boolean get() = minimalMonthlyReduction != null
}

fun findMinimalMonthlyRepair(
    profile: FinancialProfile,
    scenario: Scenario,
    stress: StressSet,
    floor: Chf,
    startMonth: Int,
    cap: Chf,
    step: Chf = Chf.francs(1),
): RepairSearch {
    require(startMonth in 0 until profile.horizonMonths) { "startMonth $startMonth outside horizon" }
    require(!cap.isNegative) { "cap cannot be negative" }
    require(step.rappen > 0) { "step must be positive" }
    val validCap = minOf(cap, simulate(profile, scenario).months
        .filter { it.month >= startMonth }.minOf { it.adjustableExpenses })
    var checked = 0
    var r = Chf.ZERO
    while (r <= validCap) {
        checked++
        val plan = scenario.withExpenseReduction(profile.horizonMonths, ExpenseReduction(startMonth, r))
        val test = stressTest(profile, plan, stress, floor)
        if (test.passes) return RepairSearch(startMonth, cap, step, checked, r, test)
        r += step
    }
    return RepairSearch(startMonth, cap, step, checked, null, null)
}

/** Explorer timing: the repair search for every start month, and the latest one that works. */
data class StartWindow(val byStartMonth: List<RepairSearch>) {
    val latestFeasibleStart: Int? get() = byStartMonth.lastOrNull { it.feasible }?.startMonth
}

fun findLatestFeasibleStart(
    profile: FinancialProfile,
    scenario: Scenario,
    stress: StressSet,
    floor: Chf,
    cap: Chf,
    step: Chf = Chf.francs(1),
): StartWindow = StartWindow((0 until profile.horizonMonths).map {
    findMinimalMonthlyRepair(profile, scenario, stress, floor, it, cap, step)
})

/** An input the user has not confirmed, bounded to [low]..[high] and checked every [step]. */
class UnknownInput(
    val name: String,
    val low: Chf,
    val high: Chf,
    val step: Chf,
    val scenarioFor: (Chf) -> Scenario,
) {
    init {
        require(low <= high) { "low must be <= high" }
        require(step.rappen > 0 && (high - low).rappen % step.rappen == 0L) { "range must be a whole number of steps" }
    }
}

/** Skeptic: how the stress-test verdict depends on one unknown, checked at every grid value. */
data class UnknownImpact(
    val name: String,
    val low: Chf,
    val high: Chf,
    val step: Chf,
    val floor: Chf,
    /** (value, worst-case minimum cash over the stress set) for every grid value. */
    val worstMinimumCashByValue: List<Pair<Chf, Chf>>,
) {
    val passingValues: List<Chf> get() = worstMinimumCashByValue.filter { it.second >= floor }.map { it.first }

    /** True if pass/fail differs somewhere inside the range: worth asking the user about. */
    val changesDecision: Boolean get() = passingValues.size in 1 until worstMinimumCashByValue.size

    /** Largest value that passes, if the passing values are exactly low..that value (else null: no single threshold). */
    val passesUpTo: Chf? get() = passingValues.takeIf { p ->
        p.isNotEmpty() && p == worstMinimumCashByValue.map { it.first }.take(p.size)
    }?.last()

    /** Spread of worst-case cash across the range. */
    val swing: Chf get() = worstMinimumCashByValue.maxOf { it.second } - worstMinimumCashByValue.minOf { it.second }
}

fun analyzeUnknown(profile: FinancialProfile, unknown: UnknownInput, stress: StressSet, floor: Chf): UnknownImpact {
    val values = generateSequence(unknown.low) { it + unknown.step }.takeWhile { it <= unknown.high }
    return UnknownImpact(unknown.name, unknown.low, unknown.high, unknown.step, floor,
        values.map { v -> v to stressProfile(profile, unknown.scenarioFor(v), stress).worstMinimum }.toList())
}

/**
 * Skeptic question order: unknowns that flip the verdict first, then larger swing.
 * A heuristic, not proven-optimal question selection.
 */
fun rankUnknowns(profile: FinancialProfile, unknowns: List<UnknownInput>, stress: StressSet, floor: Chf): List<UnknownImpact> =
    unknowns.map { analyzeUnknown(profile, it, stress, floor) }
        .sortedWith(compareByDescending<UnknownImpact> { it.changesDecision }.thenByDescending { it.swing })

/** Rewrites phases month by month, subtracting the given income / expense cuts. Validation stays in simulate(). */
internal fun Scenario.adjusted(
    horizonMonths: Int,
    incomeCut: (Int) -> Chf = { Chf.ZERO },
    expenseCut: (Int) -> Chf = { Chf.ZERO },
    incomeAdd: (Int) -> Chf = { Chf.ZERO },
    expenseAdd: (Int) -> Chf = { Chf.ZERO },
    commitmentAdd: (Int) -> Chf = { Chf.ZERO },
): Scenario = copy(phases = (0 until horizonMonths).mapNotNull { m ->
    phases.firstOrNull { m >= it.startMonth && m < it.endMonth }?.let {
        val cut = expenseCut(m)
        require(!cut.isNegative && cut <= it.monthlyExpenses - it.monthlyCommitments) {
            "expense reduction exceeds adjustable spending in month $m"
        }
        val added = commitmentAdd(m)
        require(!added.isNegative) { "commitment cannot be negative" }
        Phase(
            m,
            m + 1,
            it.monthlyIncome + incomeAdd(m) - incomeCut(m),
            it.monthlyExpenses + expenseAdd(m) + added - cut,
            it.monthlyCommitments + added,
        )
    }
})
