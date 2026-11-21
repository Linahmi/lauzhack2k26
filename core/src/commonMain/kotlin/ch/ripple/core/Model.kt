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

/**
 * A stretch of months with constant monthly income and living expenses.
 * [monthlyCommitments] is the protected part of [monthlyExpenses] that an Explorer repair
 * must never reduce (for example rent or confirmed family support).
 */
data class Phase(
    val startMonth: Int,
    val endMonth: Int,
    val monthlyIncome: Chf,
    val monthlyExpenses: Chf,
    val monthlyCommitments: Chf = Chf.ZERO,
)

/** A single expense in one month (e.g. tuition, relocation). */
data class OneOffCost(
    val month: Int,
    val amount: Chf,
    val label: String = "",
    val provenance: Provenance = Provenance.ASSUMED,
    val evidenceRef: String? = null,
)

/**
 * Cash moved into an asset the person still owns but cannot spend (e.g. a refundable rental
 * deposit). It lowers liquid cash but is NOT consumption: net worth is unchanged.
 */
data class AssetTransfer(
    val month: Int,
    val amount: Chf,
    val label: String = "",
    val provenance: Provenance = Provenance.ASSUMED,
    val evidenceRef: String? = null,
)

/** One decision path. Phases must cover every month of the horizon exactly once. */
data class Scenario(
    val name: String,
    val phases: List<Phase>,
    val oneOffCosts: List<OneOffCost> = emptyList(),
    val transfers: List<AssetTransfer> = emptyList(),
    /** Inspectable deltas used to construct this scenario, if it was assembled from primitives. */
    val recurringChanges: List<RecurringCashFlowChange> = emptyList(),
    val protectedCommitments: List<PersonalCommitment> = emptyList(),
)

/** Outputs. One reconciled row per month: closing = opening + income - expenses - oneOffCosts - transfers. */
data class MonthRecord(
    val month: Int,
    val openingCash: Chf,
    val income: Chf,
    val expenses: Chf,
    val oneOffCosts: Chf,
    val closingCash: Chf,
    val transfersToRestricted: Chf = Chf.ZERO,
    val commitments: Chf = Chf.ZERO,
) {
    val netFlow: Chf get() = income - expenses - oneOffCosts - transfersToRestricted
    val adjustableExpenses: Chf get() = expenses - commitments
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

    /** Restricted (non-spendable) assets held at the end of the horizon, e.g. deposits. */
    val restrictedAssets: Chf get() = months.fold(Chf.ZERO) { acc, m -> acc + m.transfersToRestricted }

    /** Cash + restricted assets. No liabilities are modelled, so this is net worth in this model. */
    val closingNetWorth: Chf get() = closingCash + restrictedAssets
}

data class ScenarioComparison(
    val baseline: SimulationResult,
    val alternative: SimulationResult,
) {
    /** alternative - baseline */
    val closingCashDifference: Chf get() = alternative.closingCash - baseline.closingCash
    val minimumCashDifference: Chf get() = alternative.minimumCash - baseline.minimumCash
}
