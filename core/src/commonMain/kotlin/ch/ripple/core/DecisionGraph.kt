package ch.ripple.core

/*
 * A small, immutable, directed history of decision states. Each node = one session state the
 * user reached by a correction or a what-if. Nodes store the session (inputs) and a compact
 * summary; full briefings and simulations are recomputed deterministically on demand, never
 * copied. Uncertainty inside a node is still handled by the stress search, not by more nodes.
 */

enum class NodeStatus {
    /** Passes every tested case. */
    PASSES,
    /** Fails, and a repair within the user's limits works. */
    REPAIRABLE,
    /** Fails; a repair works financially but the user's constraints rule it out. */
    ONLY_UNACCEPTABLE_REPAIRS,
    /** Fails; nothing in the modelled action space works. */
    NO_REPAIR_IN_SCOPE,
}

sealed interface GraphChange {
    data object Start : GraphChange
    data class Corrected(val correction: Correction) : GraphChange
    data class Explored(val hypothesis: Hypothesis) : GraphChange
}

/** What a node concluded — small enough to list and diff; evidence via [DecisionGraph.briefing]. */
data class NodeSummary(
    val status: NodeStatus,
    val primaryInsightId: String,
    val primaryHeadlineKey: String,
    val worstMinimumCash: Chf,
    val cashBuffer: Chf,
    /** Earliest acceptable repair (what Ripple would offer). */
    val offeredRepair: ExpenseReduction?,
    /** Earliest financially working repair, ignoring personal limits. */
    val financialRepair: ExpenseReduction?,
    val latestAcceptableStart: Int?,
    val openQuestionInputId: String?,
    /** Why the financial repair is not offered (constraint / rejection ids). */
    val blockedBy: List<String>,
    /** The what-if cut tried in this branch, if any. */
    val plannedCut: ExpenseReduction? = null,
    /** Personal constraints the planned cut conflicts with (objective result may still pass). */
    val plannedCutConflicts: List<String> = emptyList(),
)

/** An option offered in the previous state that the user's new constraints now rule out. */
data class InvalidatedOffer(val action: ExpenseReduction, val conflicts: List<String>)

data class DecisionNode(
    val id: String,
    val parentId: String?,
    val revision: Int,
    val change: GraphChange,
    /** True for what-if nodes and everything below them: not the user's confirmed situation. */
    val hypothetical: Boolean,
    val labelKey: String,
    val session: DecisionSession,
    val summary: NodeSummary,
)

data class DecisionGraph(val nodes: List<DecisionNode>, val activeNodeId: String) {
    val active: DecisionNode get() = node(activeNodeId)
    fun node(id: String): DecisionNode = requireNotNull(nodes.firstOrNull { it.id == id }) { "no node '$id'" }
    fun children(id: String): List<DecisionNode> = nodes.filter { it.parentId == id }

    /** Root → [id]. */
    fun path(id: String): List<DecisionNode> =
        generateSequence(node(id)) { n -> n.parentId?.let(::node) }.toList().reversed()
}

/** Result of one correction/what-if: the new graph, its briefing, and what changed vs. the parent. */
data class GraphUpdate(
    val graph: DecisionGraph,
    val node: DecisionNode,
    val before: NodeSummary,
    val after: NodeSummary,
    /** Summary field names that differ (e.g. "status", "offered_repair"). */
    val changed: List<String>,
    val briefing: Briefing,
    /** Why it changed = [node].change. If the parent's offer is now excluded, which one and by what. */
    val invalidatedOffer: InvalidatedOffer? = null,
)

data class BranchComparison(
    val a: DecisionNode,
    val b: DecisionNode,
    /** b − a on the nominal (no stress) plans at each branch's worst-case inputs. */
    val nominal: ScenarioComparison,
    /** b − a on each branch's worst stress case. */
    val worstCase: ScenarioComparison,
    val differences: List<String>,
)

fun startGraph(session: DecisionSession): DecisionGraph {
    val b = brief(session)
    return DecisionGraph(listOf(DecisionNode("n0", null, 0, GraphChange.Start, false, "node.start", session, summarize(session, b))), "n0")
}

/** Apply a user-confirmed correction as a new child of [fromId]; it becomes the active node. */
fun DecisionGraph.correct(correction: Correction, fromId: String = activeNodeId): GraphUpdate =
    addChild(fromId, GraphChange.Corrected(correction), node(fromId).session.apply(correction), node(fromId).hypothetical)

/** Explore a what-if as a new hypothetical child of [fromId]; confirmed data stays untouched. */
fun DecisionGraph.explore(hypothesis: Hypothesis, fromId: String = activeNodeId): GraphUpdate =
    addChild(fromId, GraphChange.Explored(hypothesis), node(fromId).session.apply(hypothesis), true)

/** Switch to (or revert to) any existing node. */
fun DecisionGraph.select(id: String): DecisionGraph = copy(activeNodeId = node(id).id)

/** Full briefing for a node, recomputed from its session (deterministic). */
fun DecisionGraph.briefing(id: String = activeNodeId): Briefing = brief(node(id).session)

/**
 * Re-run node [id] under the personal constraints of node [constraintsFrom] (default: active),
 * e.g. to show what an earlier option looks like after the user's latest correction.
 */
fun DecisionGraph.reevaluate(id: String, constraintsFrom: String = activeNodeId): NodeSummary {
    val s = node(id).session.copy(constraints = node(constraintsFrom).session.constraints)
    return summarize(s, brief(s))
}

fun DecisionGraph.compareBranches(aId: String, bId: String): BranchComparison {
    val (a, b) = node(aId) to node(bId)
    val (ba, bb) = briefing(aId) to briefing(bId)
    return BranchComparison(
        a, b,
        ScenarioComparison(ba.scenarios.getValue("decision-nominal"), bb.scenarios.getValue("decision-nominal")),
        ScenarioComparison(ba.scenarios.getValue("decision-worst"), bb.scenarios.getValue("decision-worst")),
        diff(a.summary, b.summary),
    )
}

private fun DecisionGraph.addChild(fromId: String, change: GraphChange, session: DecisionSession, hypothetical: Boolean): GraphUpdate {
    val parent = node(fromId)
    val b = brief(session)
    val child = DecisionNode("n${nodes.size}", parent.id, parent.revision + 1, change, hypothetical, labelKey(change), session, summarize(session, b))
    val graph = DecisionGraph(nodes + child, child.id)
    val invalidated = parent.summary.offeredRepair?.let { a ->
        session.constraints.excludes(a.startMonth, a.monthly).takeIf { it.isNotEmpty() }?.let { InvalidatedOffer(a, it) }
    }
    return GraphUpdate(graph, child, parent.summary, child.summary, diff(parent.summary, child.summary), b, invalidated)
}

internal fun summarize(s: DecisionSession, b: Briefing): NodeSummary {
    val repairs = b.evidence.repairs
    val acceptable = repairs.firstOrNull { it.feasible }
    val financial = repairs.firstOrNull { it.financiallyFeasible }
    return NodeSummary(
        status = when {
            b.evidence.passesForAllTestedValues -> NodeStatus.PASSES
            acceptable != null -> NodeStatus.REPAIRABLE
            financial != null -> NodeStatus.ONLY_UNACCEPTABLE_REPAIRS
            else -> NodeStatus.NO_REPAIR_IN_SCOPE
        },
        primaryInsightId = b.primary.id,
        primaryHeadlineKey = b.primary.headlineKey,
        worstMinimumCash = b.evidence.worstStressTest.worstMinimumCash,
        cashBuffer = s.effectiveFloor,
        offeredRepair = b.offeredRepair,
        financialRepair = financial?.let { ExpenseReduction(it.startMonth, it.financialMinimum!!) },
        latestAcceptableStart = repairs.lastOrNull { it.feasible }?.startMonth,
        openQuestionInputId = b.followUp?.inputId,
        blockedBy = if (acceptable == null) financial?.blockedBy.orEmpty() else emptyList(),
        plannedCut = s.plannedCut,
        plannedCutConflicts = s.plannedCut?.let { s.constraints.excludes(it.startMonth, it.monthly) }.orEmpty(),
    )
}

private fun diff(a: NodeSummary, b: NodeSummary) = listOfNotNull(
    "status".takeIf { a.status != b.status },
    "primary_insight".takeIf { a.primaryHeadlineKey != b.primaryHeadlineKey },
    "worst_min_cash".takeIf { a.worstMinimumCash != b.worstMinimumCash },
    "cash_buffer".takeIf { a.cashBuffer != b.cashBuffer },
    "offered_repair".takeIf { a.offeredRepair != b.offeredRepair },
    "financial_repair".takeIf { a.financialRepair != b.financialRepair },
    "latest_acceptable_start".takeIf { a.latestAcceptableStart != b.latestAcceptableStart },
    "open_question".takeIf { a.openQuestionInputId != b.openQuestionInputId },
    "blocked_by".takeIf { a.blockedBy != b.blockedBy },
    "planned_cut".takeIf { a.plannedCut != b.plannedCut },
    "planned_cut_conflicts".takeIf { a.plannedCutConflicts != b.plannedCutConflicts },
)

private fun labelKey(c: GraphChange) = when (c) {
    GraphChange.Start -> "node.start"
    is GraphChange.Corrected -> when (c.correction) {
        is Correction.AnswerInput -> "node.answer_input"
        is Correction.SetMaxMonthlyReduction -> "node.set_max_reduction"
        is Correction.SetMinCashBuffer -> "node.set_cash_buffer"
        is Correction.RejectRepair -> "node.reject_repair"
        Correction.ClearMaxMonthlyReduction, Correction.ClearMinCashBuffer -> "node.clear_constraint"
        is Correction.RemoveRejection -> "node.remove_rejection"
    }
    is GraphChange.Explored -> when (c.hypothesis) {
        is Hypothesis.WhatIfInput -> "node.what_if_input"
        is Hypothesis.TryOption -> "node.try_option"
        is Hypothesis.ApplyCut -> "node.apply_cut"
    }
}
