package ch.ripple.core

/**
 * Deterministic monthly cash simulation. Same inputs -> same outputs, on every platform.
 *
 * Invalid input (gaps, overlaps, out-of-range months, negative amounts, non-positive horizon)
 * throws [IllegalArgumentException]; nothing is silently filled in or clamped.
 */
fun simulate(profile: FinancialProfile, scenario: Scenario): SimulationResult {
    val n = profile.horizonMonths
    require(n >= 1) { "horizonMonths must be >= 1, was $n" }
    require(scenario.phases.isNotEmpty()) { "Scenario '${scenario.name}' has no phases" }

    val phaseByMonth = arrayOfNulls<Phase>(n)
    for (p in scenario.phases) {
        require(p.startMonth in 0 until p.endMonth && p.endMonth <= n) {
            "Invalid phase interval [${p.startMonth}, ${p.endMonth}) for horizon $n"
        }
        require(!p.monthlyIncome.isNegative) { "monthlyIncome cannot be negative" }
        require(!p.monthlyExpenses.isNegative) { "monthlyExpenses cannot be negative" }
        require(!p.monthlyCommitments.isNegative && p.monthlyCommitments <= p.monthlyExpenses) {
            "monthlyCommitments must be between zero and monthlyExpenses"
        }
        for (m in p.startMonth until p.endMonth) {
            require(phaseByMonth[m] == null) { "Overlapping phases at month $m" }
            phaseByMonth[m] = p
        }
    }
    phaseByMonth.indexOfFirst { it == null }.let { gap ->
        require(gap == -1) { "Phases must cover every month; month $gap is missing" }
    }

    val oneOffByMonth = Array(n) { Chf.ZERO }
    for (c in scenario.oneOffCosts) {
        require(c.month in 0 until n) { "One-off cost month ${c.month} outside horizon $n" }
        require(!c.amount.isNegative) { "One-off costs cannot be negative" }
        oneOffByMonth[c.month] += c.amount
    }

    val transferByMonth = Array(n) { Chf.ZERO }
    for (t in scenario.transfers) {
        require(t.month in 0 until n) { "Transfer month ${t.month} outside horizon $n" }
        require(!t.amount.isNegative) { "Transfers cannot be negative" }
        transferByMonth[t.month] += t.amount
    }

    var cash = profile.openingCash
    val records = List(n) { m ->
        val p = phaseByMonth[m]!!
        val closing = cash + p.monthlyIncome - p.monthlyExpenses - oneOffByMonth[m] - transferByMonth[m]
        MonthRecord(m, cash, p.monthlyIncome, p.monthlyExpenses, oneOffByMonth[m], closing, transferByMonth[m], p.monthlyCommitments)
            .also { cash = closing }
    }
    return SimulationResult(scenario.name, profile.openingCash, records)
}

/** Simulates both scenarios against the same profile. */
fun compare(profile: FinancialProfile, baseline: Scenario, alternative: Scenario) =
    ScenarioComparison(simulate(profile, baseline), simulate(profile, alternative))
