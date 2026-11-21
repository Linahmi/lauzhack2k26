package ch.ripple.core

/**
 * Inputs. All amounts CHF, time step = one month, months are zero-indexed,
 * phase intervals are half-open `[startMonth, endMonth)`. Cash-only model:
 * no taxes, investments, debt, inflation or benefits (by design for Milestone 1).
 */
data class FinancialProfile(
    val name: String,
    val openingCash: Chf,
    val horizonMonths: Int,
    /** Always true in this project: Ripple never handles real client data. */
    val fictional: Boolean = true,
)

/** A stretch of months with constant monthly income and living expenses. */
data class Phase(
    val startMonth: Int,
    val endMonth: Int,
    val monthlyIncome: Chf,
    val monthlyExpenses: Chf,
)

/** A single expense in one month (e.g. tuition, relocation). */
data class OneOffCost(
    val month: Int,
    val amount: Chf,
    val label: String = "",
)

/** One decision path. Phases must cover every month of the horizon exactly once. */
data class Scenario(
    val name: String,
    val phases: List<Phase>,
    val oneOffCosts: List<OneOffCost> = emptyList(),
)

/** Outputs. One reconciled row per month: closing = opening + income - expenses - oneOffCosts. */
data class MonthRecord(
    val month: Int,
    val openingCash: Chf,
    val income: Chf,
    val expenses: Chf,
    val oneOffCosts: Chf,
    val closingCash: Chf,
) {
    val netFlow: Chf get() = income - expenses - oneOffCosts
}

data class SimulationResult(
    val scenarioName: String,
    val openingCash: Chf,
    val months: List<MonthRecord>,
) {
    val closingCash: Chf get() = months.last().closingCash

    /** Lowest balance including the opening balance. Negative values are kept, never clamped. */
    val minimumCash: Chf get() = months.minOf { it.closingCash }.coerceAtMost(openingCash)

    /** Zero-indexed month whose closing balance first goes below zero; null if never. */
    val firstNegativeMonth: Int? get() = months.firstOrNull { it.closingCash.isNegative }?.month

    val cashByMonth: List<Chf> get() = months.map { it.closingCash }
}

data class ScenarioComparison(
    val baseline: SimulationResult,
    val alternative: SimulationResult,
) {
    /** alternative - baseline */
    val closingCashDifference: Chf get() = alternative.closingCash - baseline.closingCash
    val minimumCashDifference: Chf get() = alternative.minimumCash - baseline.minimumCash
}
