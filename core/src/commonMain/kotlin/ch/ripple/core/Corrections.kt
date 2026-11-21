package ch.ripple.core

/*
 * Self-correction: the user's corrections become typed session data that the engine uses.
 *
 * Two kinds of change, never mixed:
 *  - Correction: something the user CONFIRMED (an answer or a personal limit). Changes facts.
 *  - Hypothesis: a what-if to explore. Never touches confirmed values or constraints.
 * Nothing here infers anything about the person; reasons are stored verbatim and never parsed.
 * A future LLM may only PROPOSE a Correction for the user to confirm in the UI.
 */

enum class RejectionScope {
    /** Only this amount at this start month. */
    EXACT_ACTION,
    /** "I can't cut that much": this amount or more, any start month. */
    THIS_AMOUNT_OR_MORE,
    /** Any amount starting in this month. */
    THIS_START_MONTH,
    /** No spending cuts at all. */
    ANY_SPENDING_REDUCTION,
}

data class Rejection(
    val id: String,
    val action: ExpenseReduction,
    val scope: RejectionScope,
    /** The user's own words, shown back to them; never interpreted by the engine. */
    val reason: String? = null,
)

/** A confirmed recurring obligation added to every decision path and protected from repairs. */
data class PersonalCommitment(
    val id: String,
    val labelKey: String,
    val monthly: Chf,
    val startMonth: Int = 0,
    val endMonth: Int? = null,
    val provenance: Provenance = Provenance.USER_STATED,
    val evidenceRef: String? = null,
) {
    init {
        require(id.isNotBlank() && labelKey.isNotBlank()) { "commitment id and labelKey cannot be blank" }
        require(!monthly.isNegative) { "commitment cannot be negative" }
        require(startMonth >= 0 && (endMonth == null || endMonth > startMonth)) { "invalid commitment interval" }
    }

    fun appliesIn(month: Int): Boolean = month >= startMonth && (endMonth == null || month < endMonth)

    internal fun requireFits(horizonMonths: Int) {
        require(startMonth < horizonMonths && (endMonth == null || endMonth <= horizonMonths)) {
            "commitment outside horizon"
        }
    }
}

/** User-confirmed limits. Empty = no personal limits stated (the model's assumptions apply). */
data class PersonalConstraints(
    val maxMonthlyReduction: Chf? = null,
    val minCashBuffer: Chf? = null,
    val rejections: List<Rejection> = emptyList(),
    val commitments: List<PersonalCommitment> = emptyList(),
) {
    /** Ids of the constraints that rule out cutting [monthly]/month from [startMonth] (empty = allowed). */
    fun excludes(startMonth: Int, monthly: Chf): List<String> = listOfNotNull(
        Keys.CONSTRAINT_MAX_REDUCTION.takeIf { maxMonthlyReduction != null && monthly > maxMonthlyReduction },
    ) + rejections.filter { r ->
        when (r.scope) {
            RejectionScope.EXACT_ACTION -> r.action.startMonth == startMonth && r.action.monthly == monthly
            RejectionScope.THIS_AMOUNT_OR_MORE -> monthly >= r.action.monthly
            RejectionScope.THIS_START_MONTH -> r.action.startMonth == startMonth
            RejectionScope.ANY_SPENDING_REDUCTION -> true
        }
    }.map { it.id }
}

sealed interface Correction {
    data class AnswerInput(val inputId: String, val value: InputState) : Correction
    data class SetMaxMonthlyReduction(val amount: Chf) : Correction
    data class SetMinCashBuffer(val amount: Chf) : Correction
    data class RejectRepair(val action: ExpenseReduction, val scope: RejectionScope, val reason: String? = null) : Correction
    data object ClearMaxMonthlyReduction : Correction
    data object ClearMinCashBuffer : Correction
    data class RemoveRejection(val rejectionId: String) : Correction
    /** Add or replace a confirmed obligation with the same id. */
    data class SetCommitment(val commitment: PersonalCommitment) : Correction
    data class RemoveCommitment(val commitmentId: String) : Correction
    /** "This estimate doesn't apply to me": never offered again; any what-if using it is removed. */
    data class RejectMarketEstimate(val estimateId: String) : Correction
}

sealed interface Hypothesis {
    /** "What if the deposit were CHF 1,300?" — fixed for this branch, labelled ASSUMED. */
    data class WhatIfInput(val inputId: String, val value: Chf) : Hypothesis
    /** Evaluate another modelled decision, e.g. "stay". */
    data class TryOption(val optionId: String) : Hypothesis
    /** Try a spending cut of a given size and timing in this branch (e.g. CHF 100 from month 4). */
    data class ApplyCut(val cut: ExpenseReduction) : Hypothesis
    /** "Try this market assumption": the estimate's value for [inputId], labelled EXTERNAL_SOURCE. */
    data class UseMarketEstimate(val inputId: String, val estimateId: String) : Hypothesis
    /**
     * "Try this researched figure": a [FindingAssessment.estimate] that passed every validation check.
     * The estimate (with source, quote and checks) is stored in this branch's snapshot, so the
     * provenance stays with the node; earlier nodes are not touched.
     */
    data class UseResearchedEstimate(val inputId: String, val estimate: MarketEstimate) : Hypothesis
}

fun DecisionSession.apply(c: Correction): DecisionSession = when (c) {
    is Correction.AnswerInput -> answer(c.inputId, c.value)
    is Correction.SetMaxMonthlyReduction -> {
        require(!c.amount.isNegative) { "limit cannot be negative" }
        copy(constraints = constraints.copy(maxMonthlyReduction = c.amount))
    }
    is Correction.SetMinCashBuffer -> {
        require(!c.amount.isNegative) { "buffer cannot be negative" }
        copy(constraints = constraints.copy(minCashBuffer = c.amount))
    }
    is Correction.RejectRepair -> {
        require(c.action.startMonth in 0 until profile.horizonMonths && !c.action.monthly.isNegative) { "invalid action" }
        val id = "rejection:${c.scope.name.lowercase()}:${c.action.startMonth}:${c.action.monthly.rappen}"
        if (constraints.rejections.any { it.id == id }) this
        else copy(constraints = constraints.copy(rejections = constraints.rejections + Rejection(id, c.action, c.scope, c.reason)))
    }
    Correction.ClearMaxMonthlyReduction -> copy(constraints = constraints.copy(maxMonthlyReduction = null))
    Correction.ClearMinCashBuffer -> copy(constraints = constraints.copy(minCashBuffer = null))
    is Correction.RemoveRejection -> {
        require(constraints.rejections.any { it.id == c.rejectionId }) { "no rejection '${c.rejectionId}'" }
        copy(constraints = constraints.copy(rejections = constraints.rejections.filterNot { it.id == c.rejectionId }))
    }
    is Correction.SetCommitment -> {
        val commitment = c.commitment.copy(
            provenance = Provenance.USER_STATED,
            evidenceRef = c.commitment.evidenceRef ?: "commitment:${c.commitment.id}",
        )
        copy(constraints = constraints.copy(
            commitments = constraints.commitments.filterNot { it.id == commitment.id } + commitment,
        ))
    }
    is Correction.RemoveCommitment -> {
        require(constraints.commitments.any { it.id == c.commitmentId }) { "no commitment '${c.commitmentId}'" }
        copy(constraints = constraints.copy(commitments = constraints.commitments.filterNot { it.id == c.commitmentId }))
    }
    is Correction.RejectMarketEstimate -> {
        requireNotNull(marketData?.estimate(c.estimateId)) { "unknown market estimate '${c.estimateId}'" }
        val using = marketAssumptions.filterValues { it == c.estimateId }.keys
        copy(
            marketReviews = marketReviews + (c.estimateId to MarketReview.REJECTED),
            marketAssumptions = marketAssumptions - using,
            hypotheticalValues = hypotheticalValues - using,
        )
    }
}

fun DecisionSession.apply(h: Hypothesis): DecisionSession = when (h) {
    is Hypothesis.WhatIfInput -> {
        require(inputs.any { it.id == h.inputId }) { "unknown input '${h.inputId}'" }
        require(!h.value.isNegative) { "value cannot be negative" }
        copy(hypotheticalValues = hypotheticalValues + (h.inputId to h.value))
    }
    is Hypothesis.TryOption -> copy(activeOptionId = h.optionId) // validated in DecisionSession.init
    is Hypothesis.ApplyCut -> copy(plannedCut = h.cut)           // validated in DecisionSession.init
    is Hypothesis.UseResearchedEstimate -> {
        val e = h.estimate
        require(e.retrieval != RetrievalMode.CURATED_SNAPSHOT && e.checks.isNotEmpty() && e.checks.all { it.passed }) {
            "only research findings that passed every validation check can be applied"
        }
        val base = marketData ?: MarketSnapshot("none", emptyList())
        require(base.estimate(e.id).let { it == null || it == e }) { "a different estimate already uses id '${e.id}'" }
        val snapshot = if (base.estimate(e.id) == null) MarketSnapshot("${base.version}+${e.id}", base.estimates + e) else base
        copy(marketData = snapshot).apply(Hypothesis.UseMarketEstimate(h.inputId, e.id))
    }
    is Hypothesis.UseMarketEstimate -> {
        val input = requireNotNull(inputs.firstOrNull { it.id == h.inputId }) { "unknown input '${h.inputId}'" }
        val query = requireNotNull(input.market) { "input '${h.inputId}' has no market link" }
        val e = requireNotNull(marketData?.estimate(h.estimateId)) { "unknown market estimate '${h.estimateId}'" }
        require(state(h.inputId) !is InputState.Exact) { "'${h.inputId}' is user-confirmed; a market estimate never replaces it" }
        require(marketReviews[h.estimateId] != MarketReview.REJECTED) { "estimate '${h.estimateId}' was rejected" }
        require(assess(query, e).relevant) { "estimate '${h.estimateId}' is not comparable to '${h.inputId}'" }
        copy(
            hypotheticalValues = hypotheticalValues + (h.inputId to e.value.point),
            marketAssumptions = marketAssumptions + (h.inputId to h.estimateId),
            marketReviews = marketReviews + (h.estimateId to MarketReview.TRIED_AS_ASSUMPTION),
        )
    }
}
