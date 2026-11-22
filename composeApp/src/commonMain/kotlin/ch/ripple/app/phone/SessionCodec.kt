package ch.ripple.app.phone

import ch.ripple.core.*
import kotlinx.serialization.json.*

/*
 * Versioned, plain-JSON form of a Routes session. It stores only what the person said and did
 * (their situation, each decision's structure, and the ordered corrections / what-ifs). Every
 * number shown is recomputed by the engine on load by replaying those steps, so the file never
 * holds results that could drift from the engine. No audio and no conversation text is stored.
 * The same decision format is what the laptop would import (see exportDecision).
 */

const val SESSION_FORMAT = "routes-session"
const val DECISION_FORMAT = "routes-decision"
const val FORMAT_VERSION = 1

class UnsupportedSessionData(message: String) : Exception(message)

private fun chf(v: Chf) = JsonPrimitive(v.rappen)
private fun JsonObject.chf(k: String) = Chf(getValue(k).jsonPrimitive.long)
private fun JsonObject.chfOrNull(k: String) = get(k)?.takeUnless { it is JsonNull }?.let { Chf(it.jsonPrimitive.long) }
private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content
private fun JsonObject.strOrNull(k: String) = get(k)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
private fun JsonObject.int(k: String) = getValue(k).jsonPrimitive.int
private fun JsonObject.intOrNull(k: String) = get(k)?.takeUnless { it is JsonNull }?.jsonPrimitive?.int
private fun JsonObject.obj(k: String) = getValue(k).jsonObject
private fun JsonObject.arr(k: String) = get(k)?.jsonArray ?: JsonArray(emptyList())

// ---- amounts and input states ----

fun Amount.toJson(): JsonObject = when (this) {
    is Amount.Known -> buildJsonObject { put("t", "known"); put("v", chf(value)) }
    is Amount.Estimate -> buildJsonObject { put("t", "estimate"); put("v", chf(value)) }
    is Amount.Between -> buildJsonObject { put("t", "between"); put("lo", chf(low)); put("hi", chf(high)) }
}

fun amountFromJson(o: JsonObject): Amount = when (val t = o.str("t")) {
    "known" -> Amount.Known(o.chf("v"))
    "estimate" -> Amount.Estimate(o.chf("v"))
    "between" -> Amount.Between(o.chf("lo"), o.chf("hi"))
    else -> throw UnsupportedSessionData("amount type '$t'")
}

private fun InputState.toJson(): JsonObject = when (this) {
    is InputState.Exact -> buildJsonObject { put("t", "exact"); put("v", chf(value)) }
    is InputState.Range -> buildJsonObject { put("t", "range"); put("lo", chf(low)); put("hi", chf(high)) }
    InputState.Unknown -> buildJsonObject { put("t", "unknown") }
}

private fun inputStateFromJson(o: JsonObject): InputState = when (val t = o.str("t")) {
    "exact" -> InputState.Exact(o.chf("v"))
    "range" -> InputState.Range(o.chf("lo"), o.chf("hi"))
    "unknown" -> InputState.Unknown
    else -> throw UnsupportedSessionData("input state '$t'")
}

// ---- decision structure ----

private fun OneOffCost.toJson() = buildJsonObject {
    put("month", month); put("amount", chf(amount)); put("label", label); put("provenance", provenance.name)
}
private fun oneOffFromJson(o: JsonObject) = OneOffCost(o.int("month"), o.chf("amount"), o.str("label"), Provenance.valueOf(o.str("provenance")))

private fun StressSet.toJson() = buildJsonObject { put("months", maxLowIncomeMonths); put("reduction", chf(lowIncomeReduction)) }
private fun stressFromJson(o: JsonObject) = StressSet(o.int("months"), o.chf("reduction"))

fun Situation.toJson() = buildJsonObject {
    put("cash", chf(cash)); put("income", monthlyIncome.toJson()); put("spending", monthlySpending.toJson())
    put("cannotCut", chf(cannotCut)); put("cannotCutLabel", cannotCutLabel)
    put("plannedBills", JsonArray(plannedBills.map { it.toJson() })); put("lowerPay", lowerPay.toJson())
}

fun situationFromJson(o: JsonObject) = Situation(
    cash = o.chf("cash"), monthlyIncome = amountFromJson(o.obj("income")), monthlySpending = amountFromJson(o.obj("spending")),
    cannotCut = o.chf("cannotCut"), cannotCutLabel = o.str("cannotCutLabel"),
    plannedBills = o.arr("plannedBills").map { oneOffFromJson(it.jsonObject) }, lowerPay = stressFromJson(o.obj("lowerPay")),
)

private fun FinancialChange.toJson() = buildJsonObject {
    put("id", id); put("label", label); put("kind", kind.name); amount?.let { put("amount", it.toJson()) }
    put("timing", timing.name); put("monthOffset", monthOffset)
    source?.let { put("source", it) }; sourceRef?.let { put("sourceRef", it) } // optional: researched figures (laptop)
}
private fun changeFromJson(o: JsonObject) = FinancialChange(
    o.str("id"), o.str("label"), ChangeKind.valueOf(o.str("kind")), o["amount"]?.jsonObject?.let(::amountFromJson),
    ChangeTiming.valueOf(o.str("timing")), o.int("monthOffset"), o.strOrNull("source"), o.strOrNull("sourceRef"),
)

fun DecisionSpec.toJson() = buildJsonObject {
    put("intention", intention); put("title", title); put("situation", situation.toJson())
    put("changes", JsonArray(changes.map { it.toJson() })); put("startMonth", startMonth)
    durationMonths?.let { put("durationMonths", it) }; put("horizonMonths", horizonMonths)
    cushion?.let { put("cushion", chf(it)) }; put("laterBy", laterBy); category?.let { put("category", it) }
    put("domains", JsonArray(coverage.requiredDomains.map { JsonPrimitive(it.name) }))
    put("personName", personName); put("fictional", fictional)
}

fun specFromJson(o: JsonObject) = DecisionSpec(
    intention = o.str("intention"), title = o.str("title"), situation = situationFromJson(o.obj("situation")),
    changes = o.arr("changes").map { changeFromJson(it.jsonObject) }, startMonth = o.int("startMonth"),
    durationMonths = o.intOrNull("durationMonths"), horizonMonths = o.int("horizonMonths"), cushion = o.chfOrNull("cushion"),
    laterBy = o.int("laterBy"), category = o.strOrNull("category"),
    coverage = ModelCoverage(o.arr("domains").map { FinancialDomain.valueOf(it.jsonPrimitive.content) }.toSet()),
    personName = o.str("personName"), fictional = o.getValue("fictional").jsonPrimitive.boolean,
)

// ---- corrections and what-ifs ----

fun Correction.toJson(): JsonObject = when (this) {
    is Correction.AnswerInput -> buildJsonObject { put("t", "answer"); put("input", inputId); put("value", value.toJson()) }
    is Correction.SetMaxMonthlyReduction -> buildJsonObject { put("t", "maxCut"); put("v", chf(amount)) }
    is Correction.SetMinCashBuffer -> buildJsonObject { put("t", "cushion"); put("v", chf(amount)) }
    is Correction.RejectRepair -> buildJsonObject {
        put("t", "reject"); put("start", action.startMonth); put("monthly", chf(action.monthly)); put("scope", scope.name)
        reason?.let { put("reason", it) }
    }
    Correction.ClearMaxMonthlyReduction -> buildJsonObject { put("t", "clearMaxCut") }
    Correction.ClearMinCashBuffer -> buildJsonObject { put("t", "clearCushion") }
    is Correction.RemoveRejection -> buildJsonObject { put("t", "removeRejection"); put("id", rejectionId) }
    is Correction.SetCommitment -> buildJsonObject {
        put("t", "commitment"); put("id", commitment.id); put("label", commitment.labelKey); put("monthly", chf(commitment.monthly))
        put("start", commitment.startMonth); commitment.endMonth?.let { put("end", it) }; put("provenance", commitment.provenance.name)
    }
    is Correction.RemoveCommitment -> buildJsonObject { put("t", "removeCommitment"); put("id", commitmentId) }
    is Correction.RejectMarketEstimate -> buildJsonObject { put("t", "rejectEstimate"); put("id", estimateId) }
}

fun correctionFromJson(o: JsonObject): Correction = when (val t = o.str("t")) {
    "answer" -> Correction.AnswerInput(o.str("input"), inputStateFromJson(o.obj("value")))
    "maxCut" -> Correction.SetMaxMonthlyReduction(o.chf("v"))
    "cushion" -> Correction.SetMinCashBuffer(o.chf("v"))
    "reject" -> Correction.RejectRepair(ExpenseReduction(o.int("start"), o.chf("monthly")), RejectionScope.valueOf(o.str("scope")), o.strOrNull("reason"))
    "clearMaxCut" -> Correction.ClearMaxMonthlyReduction
    "clearCushion" -> Correction.ClearMinCashBuffer
    "removeRejection" -> Correction.RemoveRejection(o.str("id"))
    "commitment" -> Correction.SetCommitment(PersonalCommitment(o.str("id"), o.str("label"), o.chf("monthly"), o.int("start"),
        o.intOrNull("end"), Provenance.valueOf(o.str("provenance"))))
    "removeCommitment" -> Correction.RemoveCommitment(o.str("id"))
    "rejectEstimate" -> Correction.RejectMarketEstimate(o.str("id"))
    else -> throw UnsupportedSessionData("correction '$t'")
}

fun Hypothesis.toJson(): JsonObject = when (this) {
    is Hypothesis.WhatIfInput -> buildJsonObject { put("t", "whatIf"); put("input", inputId); put("v", chf(value)) }
    is Hypothesis.TryOption -> buildJsonObject { put("t", "option"); put("id", optionId) }
    is Hypothesis.ApplyCut -> buildJsonObject { put("t", "cut"); put("start", cut.startMonth); put("monthly", chf(cut.monthly)) }
    is Hypothesis.UseMarketEstimate -> buildJsonObject { put("t", "market"); put("input", inputId); put("id", estimateId) }
    // The phone never creates these; refusing beats silently storing half of one.
    is Hypothesis.UseResearchedEstimate -> throw UnsupportedSessionData("researched estimates are not stored on the phone yet")
}

fun hypothesisFromJson(o: JsonObject): Hypothesis = when (val t = o.str("t")) {
    "whatIf" -> Hypothesis.WhatIfInput(o.str("input"), o.chf("v"))
    "option" -> Hypothesis.TryOption(o.str("id"))
    "cut" -> Hypothesis.ApplyCut(ExpenseReduction(o.int("start"), o.chf("monthly")))
    "market" -> Hypothesis.UseMarketEstimate(o.str("input"), o.str("id"))
    else -> throw UnsupportedSessionData("what-if '$t'")
}

// ---- steps, profile, whole session ----

fun Step.toJson() = buildJsonObject {
    put("from", from); put("at", at); put("via", via.name)
    when (this@toJson) {
        is Step.Correct -> put("correct", correction.toJson())
        is Step.Explore -> put("explore", hypothesis.toJson())
        is Step.Select -> put("select", nodeId)
    }
}

fun stepFromJson(o: JsonObject): Step {
    val from = o.str("from"); val at = o.getValue("at").jsonPrimitive.long; val via = Via.valueOf(o.str("via"))
    return when {
        "correct" in o -> Step.Correct(from, at, via, correctionFromJson(o.obj("correct")))
        "explore" in o -> Step.Explore(from, at, via, hypothesisFromJson(o.obj("explore")))
        "select" in o -> Step.Select(from, at, via, o.str("select"))
        else -> throw UnsupportedSessionData("step without action")
    }
}

private fun Fact.toJson() = buildJsonObject { put("amount", amount.toJson()); put("via", via.name); put("at", at) }
private fun factFromJson(o: JsonObject) = Fact(amountFromJson(o.obj("amount")), Via.valueOf(o.str("via")), o.getValue("at").jsonPrimitive.long)

private fun ProfileEdit.toJson() = buildJsonObject {
    put("field", field.name); before?.let { put("before", it.toJson()) }; after?.let { put("after", it.toJson()) }
    put("via", via.name); put("at", at)
}
private fun editFromJson(o: JsonObject) = ProfileEdit(
    ProfileField.valueOf(o.str("field")), o["before"]?.jsonObject?.let(::amountFromJson), o["after"]?.jsonObject?.let(::amountFromJson),
    Via.valueOf(o.str("via")), o.getValue("at").jsonPrimitive.long,
)

fun TwinProfile.toJson() = buildJsonObject {
    put("name", name); put("fictional", fictional)
    put("facts", buildJsonObject { facts.forEach { (k, v) -> put(k.name, v.toJson()) } })
    put("plannedBills", JsonArray(plannedBills.map { it.toJson() })); put("lowerPay", lowerPay.toJson())
    put("edits", JsonArray(edits.map { it.toJson() }))
}

fun profileFromJson(o: JsonObject) = TwinProfile(
    name = o.str("name"), fictional = o.getValue("fictional").jsonPrimitive.boolean,
    facts = o.obj("facts").mapKeys { ProfileField.valueOf(it.key) }.mapValues { factFromJson(it.value.jsonObject) },
    plannedBills = o.arr("plannedBills").map { oneOffFromJson(it.jsonObject) }, lowerPay = stressFromJson(o.obj("lowerPay")),
    edits = o.arr("edits").map { editFromJson(it.jsonObject) },
)

fun RoutesDecision.toJson(format: String? = null) = buildJsonObject {
    format?.let { put("format", it); put("version", FORMAT_VERSION) }
    put("id", id); put("createdAt", createdAt); put("via", via.name); put("spec", spec.toJson())
    put("steps", JsonArray(steps.map { it.toJson() }))
    put("assumed", JsonArray(assumedInputs.map { JsonPrimitive(it) }))
}

fun decisionFromJson(o: JsonObject) = RoutesDecision(
    id = o.str("id"), spec = specFromJson(o.obj("spec")), createdAt = o.getValue("createdAt").jsonPrimitive.long,
    via = Via.valueOf(o.str("via")), steps = o.arr("steps").map { stepFromJson(it.jsonObject) },
    assumedInputs = o.arr("assumed").map { it.jsonPrimitive.content }.toSet(),
)

data class StoredSession(val profile: TwinProfile?, val decisions: List<RoutesDecision>, val activeDecisionId: String?)

fun StoredSession.encode(): String = buildJsonObject {
    put("format", SESSION_FORMAT); put("version", FORMAT_VERSION)
    profile?.let { put("profile", it.toJson()) }
    put("decisions", JsonArray(decisions.map { it.toJson() }))
    activeDecisionId?.let { put("active", it) }
}.toString()

/** Throws on anything it does not fully understand; the caller keeps the file and reports it. */
fun decodeSession(text: String): StoredSession {
    val o = Json.parseToJsonElement(text).jsonObject
    if (o.strOrNull("format") != SESSION_FORMAT) throw UnsupportedSessionData("not a Routes session file")
    val v = o.int("version")
    if (v != FORMAT_VERSION) throw UnsupportedSessionData("saved with format version $v; this app reads version $FORMAT_VERSION")
    val decisions = o.arr("decisions").map { decisionFromJson(it.jsonObject) }
    decisions.forEach { it.graph } // replay now so a broken step fails loading, not a later screen
    return StoredSession(o["profile"]?.jsonObject?.let(::profileFromJson), decisions, o.strOrNull("active"))
}

/** One decision as a self-contained structured document (for the laptop, once it can import). */
fun RoutesDecision.export(): String = toJson(DECISION_FORMAT).toString()

fun importDecision(text: String): RoutesDecision {
    val o = Json.parseToJsonElement(text).jsonObject
    if (o.strOrNull("format") != DECISION_FORMAT || o.int("version") != FORMAT_VERSION) throw UnsupportedSessionData("not a Routes decision (version $FORMAT_VERSION)")
    return decisionFromJson(o).also { it.graph }
}
