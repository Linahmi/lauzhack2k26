package ch.ripple.core

/** A signed recurring cash-flow delta over [startMonth, endMonth). */
data class RecurringCashFlowChange(
    val labelKey: String,
    val startMonth: Int,
    val endMonth: Int,
    val monthlyIncomeDelta: Chf = Chf.ZERO,
    val monthlyExpenseDelta: Chf = Chf.ZERO,
    val provenance: Provenance = Provenance.ASSUMED,
    val evidenceRef: String? = null,
) {
    init {
        require(labelKey.isNotBlank()) { "recurring change labelKey cannot be blank" }
        require(startMonth >= 0 && endMonth > startMonth) { "invalid recurring change interval" }
        require(monthlyIncomeDelta != Chf.ZERO || monthlyExpenseDelta != Chf.ZERO) {
            "recurring change must change income or expenses"
        }
    }

    fun appliesIn(month: Int): Boolean = month in startMonth until endMonth
}

/**
 * Reusable cash-flow consequences for any free-form life decision. Signed recurring deltas are
 * applied to a base scenario; existing one-off and restricted-asset primitives are reused.
 */
data class FinancialConsequences(
    val recurring: List<RecurringCashFlowChange> = emptyList(),
    val oneOffCosts: List<OneOffCost> = emptyList(),
    val restrictedAssetTransfers: List<AssetTransfer> = emptyList(),
    val protectedCommitments: List<PersonalCommitment> = emptyList(),
)

/** Builds an inspectable scenario from a base path plus explicit, provenance-carrying changes. */
fun Scenario.withConsequences(horizonMonths: Int, consequences: FinancialConsequences): Scenario {
    consequences.recurring.forEach { require(it.endMonth <= horizonMonths) { "recurring change outside horizon" } }
    consequences.protectedCommitments.forEach { it.requireFits(horizonMonths) }
    val allCommitments = protectedCommitments + consequences.protectedCommitments
    require(allCommitments.map { it.id }.distinct().size == allCommitments.size) { "duplicate commitment id" }

    return adjusted(
        horizonMonths,
        incomeAdd = { month -> consequences.recurring.filter { it.appliesIn(month) }
            .fold(Chf.ZERO) { total, it -> total + it.monthlyIncomeDelta } },
        expenseAdd = { month -> consequences.recurring.filter { it.appliesIn(month) }
            .fold(Chf.ZERO) { total, it -> total + it.monthlyExpenseDelta } },
        commitmentAdd = { month -> consequences.protectedCommitments.filter { it.appliesIn(month) }
            .fold(Chf.ZERO) { total, it -> total + it.monthly } },
    ).copy(
        oneOffCosts = oneOffCosts + consequences.oneOffCosts,
        transfers = transfers + consequences.restrictedAssetTransfers,
        recurringChanges = recurringChanges + consequences.recurring,
        protectedCommitments = allCommitments,
    )
}

/** Financial domains are engine capabilities, not a list of permitted life decisions. */
enum class FinancialDomain { CASH_FLOW, PENSION, DEBT, TAX, INVESTMENT }

data class ModelCoverage(
    val requiredDomains: Set<FinancialDomain> = setOf(FinancialDomain.CASH_FLOW),
) {
    init { require(requiredDomains.isNotEmpty()) { "at least one financial domain is required" } }

    val supportedDomains: Set<FinancialDomain> get() = requiredDomains intersect SUPPORTED
    val unsupportedDomains: Set<FinancialDomain> get() = requiredDomains - SUPPORTED
    val cashFlowSupported: Boolean get() = FinancialDomain.CASH_FLOW in supportedDomains
    val complete: Boolean get() = unsupportedDomains.isEmpty()

    companion object {
        private val SUPPORTED = setOf(FinancialDomain.CASH_FLOW)
    }
}
