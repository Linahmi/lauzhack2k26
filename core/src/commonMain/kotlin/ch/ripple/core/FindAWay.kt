package ch.ripple.core

/*
 * "Find a way": what would need to change for a decision to keep the person's cushion, searched
 * ONLY with existing engine operations (exhaustive input grids, timing inputs, modelled
 * alternatives, constraint-respecting spending trims). Generic over any DecisionSession; nothing
 * here knows scenario names. Every result is a tested set within declared bounds and the
 * simulated horizon — never a claim about all possible futures.
 */

sealed interface Way {
    /** True if at least one tested value/route/trim keeps the cushion in every tested case. */
    val works: Boolean

    /** A money figure: where its answer turns, searched over [impact]'s bounds. */
    data class Amount(val inputId: String, val impact: UnknownImpact, val uncertain: Boolean) : Way {
        override val works get() = impact.passingValues.isNotEmpty()
    }

    /** When it starts / how long it lasts: searched month by month. */
    data class Timing(val inputId: String, val unit: InputUnit, val impact: UnknownImpact) : Way {
        override val works get() = impact.passingValues.isNotEmpty()
    }

    /** A modelled alternative (e.g. later, or keeping things as they are). */
    data class Route(val optionId: String, val labelKey: String, override val works: Boolean, val lowest: Chf) : Way

    /** The smallest spending trim that works AND respects the person's limits and protected spending. */
    data class Trim(val cut: ExpenseReduction) : Way {
        override val works get() = true
    }
}

data class Discovery(
    /** Whether the plan as it stands keeps the cushion in every tested case. */
    val keepsCushion: Boolean,
    /** Best first: ways that work, then informative ones that don't. */
    val ways: List<Way>,
    /** A trim exists financially but every one is ruled out by the person's own limits. */
    val trimRuledOutByLimits: Boolean,
) {
    /** No tested value, timing, route or trim keeps the cushion. */
    val noneWork: Boolean get() = !keepsCushion && ways.none { it.works }
}

/** Searches the decision's own levers with the engine. Cost: one brief per probed input plus trade-offs. */
fun findWays(session: DecisionSession, b: Briefing = brief(session)): Discovery {
    val uncertain = b.evidence.unknownImpacts.associateBy { it.name }
    fun impact(i: DecisionInput) = uncertain[i.id] ?: runCatching { session.whereItTurns(i.id, b.evidence.worstCaseInputs) }.getOrNull()

    // Engine order for uncertain inputs (verdict-changing first, larger swing next), then the rest.
    val ordered = b.evidence.unknownImpacts.mapNotNull { u -> session.inputs.firstOrNull { it.id == u.name } } +
        session.inputs.filter { it.id !in uncertain }
    val amounts = mutableListOf<Way.Amount>()
    val timing = mutableListOf<Way.Timing>()
    // The person's existing pay and spending are facts about them, not levers of the decision.
    ordered.filter { it.id != DecisionSpec.INCOME && it.id != DecisionSpec.SPENDING }
        .filter { it.market == null || it.id !in session.marketAssumptions }.forEach { i ->
        val u = impact(i) ?: return@forEach
        when (i.unit) {
            InputUnit.CHF -> {
                // A fixed figure is only worth exploring where its value actually changes the answer.
                if (i.id in uncertain || u.changesDecision) amounts += Way.Amount(i.id, u, i.id in uncertain)
            }
            else -> if (u.changesDecision) timing += Way.Timing(i.id, i.unit, u)
        }
    }
    val routes = if (session.activeOptionId == null) tradeOffs(session).filter { it.optionId != null }
        .map { Way.Route(it.optionId!!, it.labelKey, it.bufferKeptWithoutChanges, it.worstMinimumCash) } else emptyList()
    val trim = b.offeredRepair?.let { Way.Trim(it) }
    val financialOnly = !b.evidence.passesForAllTestedValues && trim == null && b.evidence.repairs.any { it.financiallyFeasible }

    val uncertainAmounts = amounts.filter { it.uncertain }
    val fixedAmounts = amounts.filter { !it.uncertain }
    // The decision's own levers first; "keep things as they are" is the least informative way, so it comes last.
    val stayIds = session.options.filter { it.kind == DecisionKind.STAY_CURRENT }.map { it.id }.toSet()
    val all = uncertainAmounts + timing + fixedAmounts + routes.filter { it.optionId !in stayIds } + listOfNotNull(trim) + routes.filter { it.optionId in stayIds }
    return Discovery(b.evidence.passesForAllTestedValues, all.filter { it.works } + all.filter { !it.works }, financialOnly)
}
