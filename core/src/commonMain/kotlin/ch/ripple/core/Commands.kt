package ch.ripple.core

/*
 * Safe conversational action layer. Text (or a button, or later an LLM) becomes ONE typed
 * TwinCommand; only dispatch() acts on it. Commands that change confirmed state (Correct)
 * require confirmedByUser = true. Nothing here computes money: results come from the engine.
 */

sealed interface TwinCommand {
    /** Changes confirmed facts or personal limits — needs user confirmation. */
    data class Correct(val correction: Correction) : TwinCommand
    /** Opens a what-if branch; confirmed data is untouched. */
    data class Explore(val hypothesis: Hypothesis) : TwinCommand
    data class Select(val nodeId: String) : TwinCommand
    data class Compare(val aId: String, val bId: String) : TwinCommand
    /** "Why does this plan fail?" */
    data object Explain : TwinCommand
    /** "Show me the alternative(s)." */
    data object ShowTradeOffs : TwinCommand
    /** "Help me estimate" / "Why this estimate?": the Market Reality Check for one input. */
    data class ShowMarket(val inputId: String) : TwinCommand
}

sealed interface CommandResult {
    /** Ask the user to confirm this interpretation before anything changes. */
    data class NeedsConfirmation(val command: TwinCommand.Correct) : CommandResult
    data class Updated(val update: GraphUpdate) : CommandResult
    data class Selected(val graph: DecisionGraph, val briefing: Briefing) : CommandResult
    data class Compared(val comparison: BranchComparison) : CommandResult
    /** The primary finding with the exact stress case and month behind it. */
    data class Explanation(val insight: FinancialInsight, val worstCase: StressWitness, val firstViolationMonth: Int?, val disclosureKeys: List<String>) : CommandResult
    data class TradeOffs(val options: List<TradeOff>) : CommandResult
    data class Market(val check: MarketRealityCheck) : CommandResult
}

fun DecisionGraph.dispatch(command: TwinCommand, confirmedByUser: Boolean = false): CommandResult = when (command) {
    is TwinCommand.Correct ->
        if (confirmedByUser) CommandResult.Updated(correct(command.correction)) else CommandResult.NeedsConfirmation(command)
    is TwinCommand.Explore -> CommandResult.Updated(explore(command.hypothesis))
    is TwinCommand.Select -> select(command.nodeId).let { CommandResult.Selected(it, it.briefing()) }
    is TwinCommand.Compare -> CommandResult.Compared(compareBranches(command.aId, command.bId))
    TwinCommand.Explain -> briefing().let { b ->
        CommandResult.Explanation(b.primary, b.evidence.worstStressTest.worst, b.evidence.worstStressTest.firstViolationMonth, b.disclosureKeys)
    }
    TwinCommand.ShowTradeOffs -> CommandResult.TradeOffs(tradeOffs())
    is TwinCommand.ShowMarket -> CommandResult.Market(requireNotNull(marketCheck(command.inputId)) { "no market link for '${command.inputId}'" })
}

/**
 * Deterministic English phrase matcher for the demo vocabulary. Returns null when unsure —
 * the UI then offers buttons instead of guessing. Never fills in a number the user didn't say.
 *  - "why ..."                                   -> Explain
 *  - "show ... alternative(s)/options"           -> ShowTradeOffs
 *  - "what if I <option id> ..."                 -> Explore(TryOption)
 *  - "what if (the) <input> is/were <amount>"    -> Explore(WhatIfInput)
 *  - "can't/cannot reduce|cut ... that much"     -> Correct(RejectRepair(offered, THIS_AMOUNT_OR_MORE))
 *  - "can't/cannot reduce|cut ..." (otherwise)   -> Correct(RejectRepair(..., ANY_SPENDING_REDUCTION))
 *  - "<input> is (actually) <amount>"            -> Correct(AnswerInput(Exact))
 *  - "... buffer ... <amount>"                   -> Correct(SetMinCashBuffer)
 *  - "at most / max <amount>"                    -> Correct(SetMaxMonthlyReduction)
 */
fun DecisionGraph.parseCommand(text: String): TwinCommand? {
    val t = text.lowercase().replace('’', '\'')
    val s = active.session
    val amount = parseAmount(t)
    val input = s.inputs.firstOrNull { it.id in t }
    val cannotCut = Regex("""\b(can't|cannot|can not|won't|unable to)\s+(reduce|cut|lower)""").containsMatchIn(t)
    val marketInput = (input?.takeIf { it.market != null } ?: s.inputs.firstOrNull { it.market != null })
        ?.takeIf { amount == null && ("estimate" in t || "market" in t || "typical" in t) }
    return when {
        // "Try the market rent" -> what-if with the best offered estimate; "Help me estimate" / "Why this estimate?" -> show it.
        marketInput != null -> {
            val offered = marketCheck(marketInput.id)?.takeIf { it.offersEstimate }?.benchmarks?.firstOrNull()
            if (("try" in t || "use" in t) && offered != null) TwinCommand.Explore(Hypothesis.UseMarketEstimate(marketInput.id, offered.estimate.id))
            else TwinCommand.ShowMarket(marketInput.id)
        }
        t.startsWith("why") || " why " in " $t " -> TwinCommand.Explain
        "show" in t && ("alternative" in t || "option" in t) -> TwinCommand.ShowTradeOffs
        "what if" in t -> s.options.firstOrNull { it.id in t }?.let { TwinCommand.Explore(Hypothesis.TryOption(it.id)) }
            ?: if (input != null && amount != null) TwinCommand.Explore(Hypothesis.WhatIfInput(input.id, amount)) else null
        cannotCut -> {
            val offered = briefing().offeredRepair
            if (("that much" in t || "by that" in t) && offered != null) {
                TwinCommand.Correct(Correction.RejectRepair(offered, RejectionScope.THIS_AMOUNT_OR_MORE, text))
            } else {
                TwinCommand.Correct(Correction.RejectRepair(ExpenseReduction(0, Chf.ZERO), RejectionScope.ANY_SPENDING_REDUCTION, text))
            }
        }
        input != null && amount != null -> TwinCommand.Correct(Correction.AnswerInput(input.id, InputState.Exact(amount)))
        "buffer" in t && amount != null -> TwinCommand.Correct(Correction.SetMinCashBuffer(amount))
        ("at most" in t || "max" in t) && amount != null -> TwinCommand.Correct(Correction.SetMaxMonthlyReduction(amount))
        else -> null
    }
}

/** First amount in the text: "1500", "1,500", "1'500", "CHF 1,500.50". */
internal fun parseAmount(t: String): Chf? {
    val m = Regex("""(\d{1,3}(?:[,']\d{3})+|\d+)(?:\.(\d{1,2}))?""").find(t) ?: return null
    val francs = m.groupValues[1].filter(Char::isDigit).toLong()
    val cents = m.groupValues[2].padEnd(2, '0').ifEmpty { "00" }.toLong()
    return Chf(francs * 100 + cents)
}
