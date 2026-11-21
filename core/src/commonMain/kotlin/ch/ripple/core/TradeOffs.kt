package ch.ripple.core

/**
 * One modelled option (main decision or an alternative) under the SAME confirmed facts and
 * personal constraints. Objective engine results and the user's own limits are separate
 * fields; there are no scores, weights or probabilities. Every number comes from brief().
 */
data class TradeOff(
    /** null = the main decision (e.g. move); otherwise a [DecisionOption.id] (e.g. "stay"). */
    val optionId: String?,
    val labelKey: String,
    // --- Objective: engine results (all tested input values x all stress cases) ---
    val worstMinimumCash: Chf,
    val cashBuffer: Chf,
    /** Buffer kept in every tested case without any spending change. */
    val bufferKeptWithoutChanges: Boolean,
    /** No stress, worst-case inputs. */
    val nominalClosingCash: Chf,
    val nominalClosingNetWorth: Chf,
    /** Smallest working cut ignoring personal limits (null: none needed, or none in the action space). */
    val financialCut: ExpenseReduction?,
    val latestFinancialStart: Int?,
    /** Fails, and no cut in the modelled action space fixes it. */
    val noCutInActionSpace: Boolean,
    // --- The user's stated limits ---
    /** Smallest working cut that also respects the user's constraints. */
    val acceptableCut: ExpenseReduction?,
    val latestAcceptableStart: Int?,
    /** Constraint ids that rule out [financialCut] (empty if it is acceptable or not needed). */
    val conflictingConstraints: List<String>,
    // --- Honesty ---
    val disclosureKeys: List<String>,
    val limitationKeys: List<String>,
)

/** Trade-offs of every modelled option for [session]'s facts, what-ifs and constraints. */
fun tradeOffs(session: DecisionSession): List<TradeOff> =
    (listOf<String?>(null) + session.options.map { it.id }).map { optionId ->
        val s = session.copy(activeOptionId = optionId, plannedCut = null)
        val b = brief(s)
        val repairs = b.evidence.repairs
        val financial = repairs.firstOrNull { it.financiallyFeasible }
        val acceptable = repairs.firstOrNull { it.feasible }
        val nominal = b.scenarios.getValue("decision-nominal")
        TradeOff(
            optionId = optionId,
            labelKey = s.activeLabelKey,
            worstMinimumCash = b.evidence.worstStressTest.worstMinimumCash,
            cashBuffer = s.effectiveFloor,
            bufferKeptWithoutChanges = b.evidence.passesForAllTestedValues,
            nominalClosingCash = nominal.closingCash,
            nominalClosingNetWorth = nominal.closingNetWorth,
            financialCut = financial?.let { ExpenseReduction(it.startMonth, it.financialMinimum!!) },
            latestFinancialStart = repairs.lastOrNull { it.financiallyFeasible }?.startMonth,
            noCutInActionSpace = !b.evidence.passesForAllTestedValues && financial == null,
            acceptableCut = b.offeredRepair,
            latestAcceptableStart = repairs.lastOrNull { it.feasible }?.startMonth,
            conflictingConstraints = if (acceptable == null) financial?.blockedBy.orEmpty() else emptyList(),
            disclosureKeys = b.disclosureKeys,
            limitationKeys = (b.primary.limitationKeys + Keys.LIMIT_ACTION_SET + Keys.LIMIT_NOT_ADVICE).distinct(),
        )
    }

fun DecisionGraph.tradeOffs(id: String = activeNodeId): List<TradeOff> = tradeOffs(node(id).session)
