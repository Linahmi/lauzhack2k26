package ch.ripple.app.phone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.ripple.app.KeyMoment
import ch.ripple.app.keyMoment
import ch.ripple.core.*

/*
 * One session behind Explore, My Routes and My Twin. It holds the person's situation, and for
 * each decision its structure plus the ordered steps (corrections, what-ifs, selections). The
 * decision graph — every version, every number — is rebuilt by the shared engine from those
 * steps. Nothing here computes money.
 */

enum class Via { VOICE, TEXT, TAP, EXAMPLE }
enum class ProfileField { CASH, INCOME, SPENDING, CUSHION }

data class Fact(val amount: Amount, val via: Via, val at: Long)
data class ProfileEdit(val field: ProfileField, val before: Amount?, val after: Amount?, val via: Via, val at: Long)

/** What Routes knows about the person's situation, independent of any one decision. */
data class TwinProfile(
    val name: String,
    val fictional: Boolean,
    val facts: Map<ProfileField, Fact> = emptyMap(),
    /** Example data only for now: the phone does not ask about these yet. */
    val plannedBills: List<OneOffCost> = emptyList(),
    val lowerPay: StressSet = StressSet(0, Chf.ZERO),
    val edits: List<ProfileEdit> = emptyList(),
)

sealed interface Step {
    val from: String; val at: Long; val via: Via
    data class Correct(override val from: String, override val at: Long, override val via: Via, val correction: Correction) : Step
    data class Explore(override val from: String, override val at: Long, override val via: Via, val hypothesis: Hypothesis) : Step
    data class Select(override val from: String, override val at: Long, override val via: Via, val nodeId: String) : Step
}

class RoutesDecision(
    val id: String,
    val spec: DecisionSpec,
    val createdAt: Long,
    val via: Via,
    val steps: List<Step>,
    built: DecisionGraph? = null,
    /** Inputs Routes filled in because the person didn't say (e.g. "starts now"). Shown as assumed until corrected. */
    val assumedInputs: Set<String> = emptySet(),
) {
    /** Rebuilt from the steps by the engine (deterministic node ids n0, n1, …). */
    val graph: DecisionGraph by lazy {
        built ?: steps.fold(startGraph(spec.toSession())) { g, s ->
            when (s) {
                is Step.Correct -> g.correct(s.correction, s.from).graph
                is Step.Explore -> g.explore(s.hypothesis, s.from).graph
                is Step.Select -> g.select(s.nodeId)
            }
        }
    }

    fun with(step: Step, graph: DecisionGraph) = RoutesDecision(id, spec, createdAt, via, steps + step, graph, assumedInputs)

    /** Still Routes' assumption: never corrected by the person. */
    fun isAssumed(inputId: String) = inputId in assumedInputs &&
        steps.none { it is Step.Correct && (it.correction as? Correction.AnswerInput)?.inputId == inputId }

    /** The newest version that is the person's confirmed situation (never a what-if). */
    val confirmedHead: DecisionNode get() = graph.nodes.last { !it.hypothetical }
    val title: String get() = spec.title
}

interface SessionStore {
    fun load(): String?
    fun save(text: String)
    /** Moves an unreadable file aside (kept, never deleted) so a fresh session can start. */
    fun setAside()
    fun clear()
}

class MemoryStore(var text: String? = null) : SessionStore {
    var setAsideText: String? = null
    override fun load() = text
    override fun save(text: String) { this.text = text }
    override fun setAside() { setAsideText = text; text = null }
    override fun clear() { text = null }
}

sealed interface Turn {
    data class User(val text: String, val via: Via) : Turn
    data class Routes(val text: String) : Turn
    /** A calculated result; the card links to the decision in My Routes. */
    data class Finding(val decisionId: String, val nodeId: String, val text: String) : Turn
}

/** A change to a confirmed fact that Routes heard but has not saved. */
data class Pending(
    val decisionId: String,
    val correction: Correction,
    val summary: String,
    val before: KeyMoment,
    val after: KeyMoment,
    val via: Via,
)

class RoutesSession(private val store: SessionStore, private val now: () -> Long) {
    var profile by mutableStateOf<TwinProfile?>(null); private set
    var decisions by mutableStateOf<List<RoutesDecision>>(emptyList()); private set
    var activeDecisionId by mutableStateOf<String?>(null); private set
    var turns by mutableStateOf<List<Turn>>(emptyList()); private set
    var pending by mutableStateOf<Pending?>(null); private set
    var interview by mutableStateOf<Interview?>(null); private set
    /** Set when the saved file could not be read; saving stays off until the person chooses. */
    var loadProblem by mutableStateOf<String?>(null); private set
    var saveProblem by mutableStateOf<String?>(null); private set

    val activeDecision: RoutesDecision? get() = decisions.firstOrNull { it.id == activeDecisionId }
    fun decision(id: String) = decisions.first { it.id == id }

    init { load() }

    private fun load() {
        val text = try { store.load() } catch (e: Exception) { loadProblem = "The saved session could not be opened (${e.message})."; return }
        if (text == null) return
        try {
            val s = decodeSession(text)
            profile = s.profile; decisions = s.decisions
            activeDecisionId = s.activeDecisionId?.takeIf { id -> s.decisions.any { it.id == id } }
        } catch (e: Exception) {
            loadProblem = "Your saved session could not be read: ${e.message ?: e::class.simpleName}. It has not been changed."
        }
    }

    fun startFresh() {
        if (loadProblem != null) store.setAside()
        loadProblem = null; profile = null; decisions = emptyList(); activeDecisionId = null; turns = emptyList(); pending = null; interview = null
    }

    fun forgetEverything() { store.clear(); startFresh() }

    private fun save() {
        if (loadProblem != null) return
        saveProblem = try { store.save(StoredSession(profile, decisions, activeDecisionId).encode()); null }
            catch (e: Exception) { "Couldn't save on this phone (${e.message}). Changes last until the app closes." }
    }

    private fun say(text: String) { turns = turns + Turn.Routes(text) }

    // ------------------------------------------------------------ example person

    fun startExample() {
        val t = now()
        fun f(v: Long) = Fact(Amount.Known(Chf.francs(v)), Via.EXAMPLE, t)
        profile = TwinProfile(
            name = "Alex", fictional = true,
            facts = mapOf(ProfileField.CASH to f(4_000), ProfileField.INCOME to f(2_600), ProfileField.SPENDING to f(2_000), ProfileField.CUSHION to f(500)),
            plannedBills = listOf(OneOffCost(3, Chf.francs(900), "Annual obligation", Provenance.USER_STATED)),
            lowerPay = StressSet(2, Chf.francs(800)),
        )
        say("You're exploring as Alex, a fictional example. Ask a “What if…” question about a decision.")
        save()
    }

    // ------------------------------------------------------------ conversation

    /** One utterance, spoken or typed. Every financial effect goes through the engine. */
    fun handle(raw: String, via: Via) {
        val text = raw.trim()
        if (text.isEmpty()) return
        turns = turns + Turn.User(text, via)
        pending = null
        interview?.let { answerInterview(it, text, via); return }
        activeDecision?.let { d -> if (actOn(d, text, via)) return }
        // A recognisable decision starts even without a perfect "what if" (typos, speech errors).
        if (looksLikeDecision(text) || interpretDecision(presentTense(text)).recognized) { startDecision(text, via); return }
        say("I didn't catch a decision or a change. Try “What if I moved out?” or name an amount, like “the deposit is 1,450”.")
    }

    private fun looksLikeDecision(t: String) = Regex("""\b(what if|what happens if|should i|thinking (of|about)|i want to|i'd like to)\b""", RegexOption.IGNORE_CASE).containsMatchIn(t)

    private fun startDecision(question: String, via: Via) {
        val draft = interpretDecision(presentTense(question)).copy(intention = question.trim())
        if (!draft.recognized) {
            say("I can't model that decision yet. I can explore moving, studying abroad, working fewer hours, a career break, changing jobs, buying a car, supporting family, or a big one-off expense.")
            return
        }
        val unsupported = draft.coverage.unsupportedDomains
        if (unsupported.isNotEmpty()) say("I'll only look at the cash coming in and going out. ${unsupported.joinToString { it.name.lowercase() }} effects aren't modelled.")
        if (profile == null) profile = TwinProfile(name = "You", fictional = false)
        val iv = Interview(draft, draft.changes, draft.durationMonths, via)
        interview = iv
        ask(iv)
    }

    private fun ask(iv: Interview) {
        val q = iv.next(profile!!) ?: return finishInterview(iv)
        interview = iv.copy(asking = q)
        say(q.question(iv))
    }

    private fun answerInterview(iv: Interview, text: String, via: Via) {
        val q = iv.asking ?: return ask(iv)
        if (Regex("""\b(cancel|stop|never mind|forget it)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            interview = null; say("Stopped. Nothing was calculated."); return
        }
        val a = parseAnswer(text, months = q is Ask.Duration)
        when {
            a is Answer.NotSure && q is Ask.Change -> { interview = iv.copy(asking = Ask.Range(q.changeId)); say(Ask.Range(q.changeId).question(iv)) }
            a is Answer.NotSure -> say("I need a figure for this one to calculate anything. Your best guess is fine, or a range like “1,000 to 1,500”.")
            a == null -> say("I didn't hear an amount. ${q.question(iv)}")
            q is Ask.Duration -> {
                val n = (a as? Answer.Value)?.amount?.let { (it as? Amount.Known)?.value?.wholeUnits }
                if (n == null || n < 1) say("How many months? One number, like “6”.") else ask(iv.copy(duration = n, asking = null))
            }
            q is Ask.Profile -> {
                val amount = (a as Answer.Value).amount
                if (q.field == ProfileField.CASH && amount is Amount.Between) { say("For money today I need one figure. If you're unsure, the lower one is safer."); return }
                setFact(q.field, amount, via, propagate = false)
                ask(iv.copy(asking = null))
            }
            q is Ask.Change || q is Ask.Range -> {
                val id = if (q is Ask.Change) q.changeId else (q as Ask.Range).changeId
                val amount = (a as Answer.Value).amount
                if (q is Ask.Range && amount !is Amount.Between) { say("A range needs two amounts, like “1,300 to 1,700”."); return }
                ask(iv.copy(changes = iv.changes.map { if (it.id == id) it.copy(amount = amount) else it }, asking = null))
            }
        }
    }

    private fun finishInterview(iv: Interview) {
        interview = null
        val p = profile!!
        val situation = Situation(
            cash = p.facts.getValue(ProfileField.CASH).amount.smallest,
            monthlyIncome = p.facts.getValue(ProfileField.INCOME).amount,
            monthlySpending = p.facts.getValue(ProfileField.SPENDING).amount,
            plannedBills = p.plannedBills, lowerPay = p.lowerPay,
        )
        val spec = iv.draft.toSpec(situation, iv.changes, durationMonths = iv.duration, cushion = p.facts[ProfileField.CUSHION]?.amount?.smallest)
            .copy(personName = p.name, fictional = p.fictional)
        val problems = spec.problems()
        if (problems.isNotEmpty()) { say("I can't calculate this yet: ${problems.joinToString(" ")}"); return }
        val t = now()
        val d = RoutesDecision("d$t-${decisions.size}", spec, t, iv.via, emptyList(),
            assumedInputs = if (iv.draft.startMonth == null) setOf(DecisionSpec.START) else emptySet())
        decisions = decisions + d
        activeDecisionId = d.id
        turns = turns + Turn.Finding(d.id, d.graph.activeNodeId, findingText(d, keyMoment(d.graph.briefing())) +
            if (d.isAssumed(DecisionSpec.START)) " (Assuming it starts now, in ${monthName(spec.startMonth)}.)" else "")
        say("Saved to My Routes as “${spec.title}”. You can keep asking here, or open it to explore.")
        save()
    }

    /** A request about the decision in front of the person. False = not about it. */
    private fun actOn(d: RoutesDecision, text: String, via: Via): Boolean {
        val cmd = interpretFor(d, text) ?: return false
        when (cmd) {
            is TwinCommand.Explore -> explore(d.id, cmd.hypothesis, via)
            is TwinCommand.Correct -> propose(d.id, cmd.correction, via)
            TwinCommand.Explain -> say(explainText(d))
            TwinCommand.ShowTradeOffs -> say(compareText(d))
            else -> return false
        }
        return true
    }

    fun propose(decisionId: String, c: Correction, via: Via) {
        val d = decision(decisionId)
        val head = d.confirmedHead.session
        val before = keyMoment(brief(head))
        val after = keyMoment(brief(head.apply(c)))
        pending = Pending(decisionId, c, describe(d, c), before, after, via)
    }

    /** "That's real": saved as the person's confirmed situation, as a new version. */
    fun confirmPending() {
        val p = pending ?: return
        pending = null
        val d = decision(p.decisionId)
        val u = d.graph.correct(p.correction, d.confirmedHead.id)
        replace(d.with(Step.Correct(d.confirmedHead.id, now(), p.via, p.correction), u.graph))
        turns = turns + Turn.Finding(d.id, u.node.id, "Saved: ${p.summary}. ${changeText(p.before, p.after)}")
        save()
    }

    /** "Just trying": the same value as a what-if; confirmed facts stay as they were. */
    fun tryPending() {
        val p = pending ?: return
        pending = null
        val h = (p.correction as? Correction.AnswerInput)?.let { a -> (a.value as? InputState.Exact)?.let { Hypothesis.WhatIfInput(a.inputId, it.value) } }
        if (h == null) { say("That one can only be saved, not tried."); return }
        explore(p.decisionId, h, p.via)
    }

    fun dismissPending() { pending = null }

    // ------------------------------------------------------------ actions used by every space

    fun open(decisionId: String) { activeDecisionId = decisionId; save() }

    fun explore(decisionId: String, h: Hypothesis, via: Via, from: String? = null) {
        val d = decision(decisionId)
        val start = from ?: d.graph.activeNodeId
        val before = keyMoment(d.graph.briefing(start))
        val u = d.graph.explore(h, start)
        replace(d.with(Step.Explore(start, now(), via, h), u.graph))
        activeDecisionId = decisionId
        turns = turns + Turn.Finding(d.id, u.node.id, "What-if, not saved as fact: ${describe(d, h)}. ${changeText(before, keyMoment(u.briefing))}")
        save()
    }

    /** Applies a confirmed correction directly (a deliberate tap in My Routes or My Twin). */
    fun confirm(decisionId: String, c: Correction, via: Via) {
        val d = decision(decisionId)
        val u = d.graph.correct(c, d.confirmedHead.id)
        replace(d.with(Step.Correct(d.confirmedHead.id, now(), via, c), u.graph))
        save()
    }

    fun select(decisionId: String, nodeId: String) {
        val d = decision(decisionId)
        replace(d.with(Step.Select(d.graph.activeNodeId, now(), Via.TAP, nodeId), d.graph.select(nodeId)))
        save()
    }

    fun deleteDecision(decisionId: String) {
        decisions = decisions.filterNot { it.id == decisionId }
        if (activeDecisionId == decisionId) activeDecisionId = decisions.lastOrNull()?.id
        turns = turns.filterNot { it is Turn.Finding && it.decisionId == decisionId }
        save()
    }

    /**
     * Records or removes a fact about the person. Pay, spending and cushion changes reach every
     * decision as engine corrections (earlier versions stay). Cash today has no correction in the
     * engine, so each decision is rebuilt from its own steps with the new figure.
     */
    fun setFact(field: ProfileField, amount: Amount?, via: Via, propagate: Boolean = true) {
        val p = profile ?: TwinProfile("You", fictional = false)
        val before = p.facts[field]?.amount
        val facts = if (amount == null) p.facts - field else p.facts + (field to Fact(amount, via, now()))
        profile = p.copy(facts = facts, edits = p.edits + ProfileEdit(field, before, amount, via, now()))
        if (propagate && amount != null) decisions = decisions.map { d -> propagateFact(d, field, amount, via) }
        save()
    }

    private fun propagateFact(d: RoutesDecision, field: ProfileField, amount: Amount, via: Via): RoutesDecision {
        val c = when (field) {
            ProfileField.INCOME -> Correction.AnswerInput(DecisionSpec.INCOME, amount.asState())
            ProfileField.SPENDING -> Correction.AnswerInput(DecisionSpec.SPENDING, amount.asState())
            ProfileField.CUSHION -> Correction.SetMinCashBuffer(amount.smallest)
            ProfileField.CASH -> return runCatching {
                RoutesDecision(d.id, d.spec.copy(situation = d.spec.situation.copy(cash = amount.smallest)), d.createdAt, d.via, d.steps, assumedInputs = d.assumedInputs).also { it.graph }
            }.getOrElse { say("“${d.title}” couldn't be recalculated with the new cash amount, so it keeps the old one."); d }
        }
        val u = d.graph.correct(c, d.confirmedHead.id)
        return d.with(Step.Correct(d.confirmedHead.id, now(), via, c), u.graph)
    }

    private fun replace(d: RoutesDecision) { decisions = decisions.map { if (it.id == d.id) d else it } }

    fun importForTest(d: RoutesDecision) { decisions = decisions + d; activeDecisionId = d.id; save() }
}

fun Amount.asState(): InputState = when (this) {
    is Amount.Known -> InputState.Exact(value)
    is Amount.Estimate -> InputState.Range(value, value)
    is Amount.Between -> InputState.Range(low, high)
}

/** "What if I moved out?" -> "move out": the core matcher knows present-tense phrases only. */
private val PAST = mapOf("moved" to "move", "studied" to "study", "bought" to "buy", "took" to "take", "changed" to "change",
    "reduced" to "reduce", "worked" to "work", "supported" to "support", "helped" to "help", "stopped" to "stop",
    "switched" to "switch", "paid" to "pay", "went" to "go", "started" to "start", "relocated" to "relocate")

fun presentTense(t: String): String = Regex("""[A-Za-z]+""").replace(t) { m -> PAST[m.value.lowercase()] ?: m.value }

// ------------------------------------------------------------ the short interview

sealed interface Ask {
    data class Profile(val field: ProfileField) : Ask
    data class Change(val changeId: String) : Ask
    data class Range(val changeId: String) : Ask
    data object Duration : Ask
}

data class Interview(
    val draft: DecisionDraft,
    val changes: List<FinancialChange>,
    val duration: Int?,
    val via: Via,
    val asking: Ask? = null,
) {
    /** Only what is missing: the situation (once per person), each amount, and a duration if needed. */
    fun next(p: TwinProfile): Ask? =
        listOf(ProfileField.CASH, ProfileField.INCOME, ProfileField.SPENDING).firstOrNull { it !in p.facts }?.let { Ask.Profile(it) }
            ?: changes.firstOrNull { it.amount == null }?.let { Ask.Change(it.id) }
            ?: Ask.Duration.takeIf { draft.needsDuration && duration == null }

    private fun change(id: String) = changes.first { it.id == id }
}

fun Ask.question(iv: Interview): String = when (this) {
    is Ask.Profile -> when (field) {
        ProfileField.CASH -> "First, how much money do you have available today?"
        ProfileField.INCOME -> "How much pay comes in each month, after tax? A range is fine."
        ProfileField.SPENDING -> "And how much do you usually spend in a month, everything included?"
        ProfileField.CUSHION -> "Is there an amount you always like to keep?"
    }
    is Ask.Change -> {
        val c = iv.changes.first { it.id == changeId }
        val how = when (c.kind) {
            ChangeKind.LESS_INCOME -> "how much less per month?"
            ChangeKind.MORE_INCOME -> "how much more per month?"
            ChangeKind.MORE_SPENDING -> "how much more per month?"
            ChangeKind.LESS_SPENDING -> "how much less per month?"
            ChangeKind.ONE_OFF_COST -> "how much, once?"
            ChangeKind.REFUNDABLE_DEPOSIT -> "how much? You get it back later, but it's not spendable meanwhile."
        }
        "${c.label}: $how Say an amount, a range like “1,300 to 1,700”, or “not sure”."
    }
    is Ask.Range -> "No problem. Roughly between what and what? I'll test every amount in between."
    Ask.Duration -> "How many months would it last?"
}

sealed interface Answer {
    data class Value(val amount: Amount) : Answer
    data object NotSure : Answer
}

private val WORD_NUMBERS = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
    "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12)

/** Amounts only as the person said them: one figure, "about" a figure, or two figures as a range. */
fun parseAnswer(text: String, months: Boolean = false): Answer? {
    val t = text.lowercase().replace('’', '\'')
    if (Regex("""\b(not sure|don't know|do not know|no idea|unsure)\b""").containsMatchIn(t)) return Answer.NotSure
    val nums = numbersIn(t, words = months)
    return when (nums.size) {
        0 -> null
        1 -> Answer.Value(if (Regex("""\b(about|around|roughly|approximately|maybe)\b""").containsMatchIn(t)) Amount.Estimate(nums[0]) else Amount.Known(nums[0]))
        else -> Answer.Value(Amount.Between(minOf(nums[0], nums[1]), maxOf(nums[0], nums[1])))
    }
}

/** Plain figures ("1450", "1,450", "1'450", "1 450", "1,450.50"); number words only for counts. */
fun numbersIn(t: String, words: Boolean = false): List<Chf> {
    val digits = Regex("""(\d{1,3}(?:[,' ]\d{3})+|\d+)(?:\.(\d{1,2}))?""").findAll(t).map { m ->
        Chf(m.groupValues[1].filter(Char::isDigit).toLong() * 100 + m.groupValues[2].padEnd(2, '0').ifEmpty { "00" }.toLong())
    }.toList()
    if (digits.isNotEmpty() || !words) return digits
    return Regex("""\b(\w+)\b""").findAll(t).mapNotNull { WORD_NUMBERS[it.value] }.map { Chf.francs(it.toLong()) }.toList()
}
