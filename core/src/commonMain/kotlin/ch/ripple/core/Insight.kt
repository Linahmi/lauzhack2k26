package ch.ripple.core

/*
 * Backend -> frontend contract types. Produced by brief() in Brief.kt.
 *
 * The engine decides WHAT matters and WHY; the UI only chooses HOW to say it.
 * Text is never stored here: the UI maps *Key strings to localized copy and fills in typed
 * facts. Every number in a fact comes from the engine or from a labelled user input.
 */

/** Where a value comes from. Shown in the Inspect level; never dropped. */
enum class Provenance { USER_STATED, USER_ESTIMATE_RANGE, ASSUMED, UNKNOWN, COMPUTED, EXTERNAL_SOURCE }

/** A typed value a headline or explanation can display. Months are zero-based. */
sealed interface FactValue {
    data class Money(val amount: Chf) : FactValue
    data class Month(val index: Int) : FactValue
    data class Months(val indexes: List<Int>) : FactValue
    data class Count(val n: Int) : FactValue
    data class MoneyRange(val low: Chf, val high: Chf) : FactValue
}

/** One verifiable statement, e.g. key "worst_cash", value Money(300), COMPUTED, ref "stress:worst". */
data class ExplanationFact(
    val key: String,
    val value: FactValue,
    val provenance: Provenance,
    /** Points at the evidence (scenario id, input id, month record) for the Inspect level. */
    val evidenceRef: String? = null,
)

enum class InsightType {
    LIQUIDITY_BREACH,          // Guardian: a tested case goes below the user's floor
    FRAGILE_PLAN,              // nominal plan fine, a small tested change breaks it
    DECISION_CHANGING_UNKNOWN, // Skeptic: a missing input flips the verdict
    FEASIBLE_REPAIR,           // Explorer: an approved action fixes all tested cases
    ACTION_DEADLINE,           // Explorer: latest start for that action
    NO_REPAIR_IN_SCOPE,        // Explorer: nothing in the declared action set works
    NO_ACCEPTABLE_REPAIR,      // Explorer: something works financially, but not within the user's limits
    ROBUST_CONCLUSION,         // verdict holds across every tested case and unknown range
}

enum class InsightSeverity { CRITICAL, WARNING, INFO, REASSURING }

/** Why the ranker put an insight where it is — shown in Inspect, used by tests. */
enum class RankReason {
    SAFETY_CRITICAL, ON_USER_DECISION, ACTIONABLE, SUPPORTED_BY_ALL_TESTED_CASES,
    COULD_CHANGE_WITH_MISSING_ANSWER, NOT_SEEN_YET, ALREADY_SEEN,
}

data class FinancialInsight(
    /** Stable across recomputes with the same inputs, so "already seen" works. */
    val id: String,
    val type: InsightType,
    val severity: InsightSeverity,
    val headlineKey: String,
    val facts: List<ExplanationFact>,
    val scenarioIds: List<String>,
    val inputIds: List<String>,
    val actionIds: List<String>,
    /** Limitation keys that MUST be shown with this insight at every disclosure level. */
    val limitationKeys: List<String>,
    val rankReasons: List<RankReason>,
)

/** What the user can answer. "I'm not sure" is always allowed and keeps the input UNKNOWN. */
sealed interface AnswerSpec {
    data class MoneyRange(val low: Chf, val high: Chf, val step: Chf) : AnswerSpec
    data class MonthChoice(val options: List<Int>) : AnswerSpec
}

data class FollowUpQuestion(
    val id: String,
    val inputId: String,
    val promptKey: String,
    val answer: AnswerSpec,
    /** Why this question matters, e.g. threshold fact "passes up to CHF 1,500". */
    val whyFacts: List<ExplanationFact>,
    val allowsUnsure: Boolean = true,
)

enum class ActionKind { EXPLORE_ALTERNATIVE, ANSWER_QUESTION, CORRECT_ASSUMPTION, INSPECT }

data class UserAction(val id: String, val kind: ActionKind, val labelKey: String, val scenarioId: String? = null)

/**
 * Everything the UI needs for one screen: ONE primary insight, at most ONE question,
 * the rest available on request. [disclosureKeys] (fictional, hypothetical, tested-cases-only…)
 * are never ranked away.
 */
data class Briefing(
    val primary: FinancialInsight,
    val followUp: FollowUpQuestion?,
    val actions: List<UserAction>,
    val moreInsights: List<FinancialInsight>,
    val disclosureKeys: List<String>,
    /** Timelines referenced by scenarioIds: "baseline", "decision-nominal", "decision-worst", "decision-repaired". */
    val scenarios: Map<String, SimulationResult>,
    /** Full search evidence for the Inspect level. */
    val evidence: BriefingEvidence,
)
