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

/** User-confirmed limits. Empty = no personal limits stated (the model's assumptions apply). */
data class PersonalConstraints(
    val maxMonthlyReduction: Chf? = null,
    val minCashBuffer: Chf? = null,
    val rejections: List<Rejection> = emptyList(),
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
}

sealed interface Hypothesis {
    /** "What if the deposit were CHF 1,300?" — fixed for this branch, labelled ASSUMED. */
    data class WhatIfInput(val inputId: String, val value: Chf) : Hypothesis
    /** Evaluate another modelled decision, e.g. "stay". */
    data class TryOption(val optionId: String) : Hypothesis
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
}

fun DecisionSession.apply(h: Hypothesis): DecisionSession = when (h) {
    is Hypothesis.WhatIfInput -> {
        require(inputs.any { it.id == h.inputId }) { "unknown input '${h.inputId}'" }
        require(!h.value.isNegative) { "value cannot be negative" }
        copy(hypotheticalValues = hypotheticalValues + (h.inputId to h.value))
    }
    is Hypothesis.TryOption -> copy(activeOptionId = h.optionId) // validated in DecisionSession.init
}
