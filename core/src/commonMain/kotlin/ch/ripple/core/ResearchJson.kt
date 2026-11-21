package ch.ripple.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Wire format between the apps and the research proxy (research-server/). A request carries only
 * the research query (category, unit, area, dwelling type, year) — never amounts, input values,
 * names or other personal data. Responses are decoded defensively: anything malformed becomes
 * ProviderResponse.Failure(BAD_RESPONSE), and every field is still validated by validateFinding().
 */
object ResearchJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun encodeRequest(r: ResearchRequest): String = buildJsonObject {
        put("id", r.id)
        put("decision", r.decision.name)
        put("category", r.query.category.name)
        put("unit", r.query.unit.name)
        put("basis", r.query.basis.name)
        put("scope", geo(r.query.scope))
        put("housing", r.query.housing?.let(::housing) ?: JsonNull)
        put("asOfYear", r.query.asOfYear)
        put("maxAgeYears", r.query.maxAgeYears)
        putJsonArray("preferredHosts") { r.preferredHosts.forEach { add(JsonPrimitive(it)) } }
    }.toString()

    /** Server side. Throws IllegalArgumentException on malformed input. */
    fun decodeRequest(s: String): ResearchRequest = runCatching {
        val o = json.parseToJsonElement(s).jsonObject
        ResearchRequest(
            id = o.str("id")!!,
            decision = DecisionKind.valueOf(o.str("decision")!!),
            query = MarketQuery(
                category = MarketCategory.valueOf(o.str("category")!!),
                unit = MarketUnit.valueOf(o.str("unit")!!),
                scope = geo(o["scope"]!!.jsonObject),
                housing = (o["housing"] as? JsonObject)?.let(::housing),
                asOfYear = o.str("asOfYear")!!.toInt(),
                maxAgeYears = o.str("maxAgeYears")?.toInt() ?: 3,
                basis = AmountBasis.valueOf(o.str("basis") ?: "NOT_STATED"),
            ),
            inputId = null,
            priorityBasis = PriorityBasis.HEURISTIC,
            decisionChanging = null,
            whyFacts = emptyList(),
            preferredHosts = (o["preferredHosts"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.filter { sourceTier("https://$it/") != null }
                ?: ResearchCatalog.allHosts,
        )
    }.getOrElse { throw IllegalArgumentException("malformed research request", it) }

    fun encodeResponse(r: ProviderResponse): String = when (r) {
        is ProviderResponse.Success -> buildJsonObject {
            put("status", "ok")
            put("provider", r.provider)
            put("findings", JsonArray(r.findings.map(::finding)))
            put("documents", JsonArray(r.documents.map(::document)))
        }
        is ProviderResponse.Failure -> buildJsonObject {
            put("status", "error")
            put("reason", r.reason.name)
            r.message?.let { put("message", it.take(300)) }
        }
    }.toString()

    /** Client side. Never throws. */
    fun decodeResponse(s: String): ProviderResponse = runCatching {
        val o = json.parseToJsonElement(s).jsonObject
        when (o.str("status")) {
            "ok" -> ProviderResponse.Success(
                findings = o["findings"]!!.jsonArray.map { findingFrom(it.jsonObject, null) },
                documents = o["documents"]!!.jsonArray.map { d ->
                    val x = d.jsonObject
                    SourceDocument(x.str("url")!!, x.str("retrievedOn")!!, x.str("mediaType")!!, x.str("text")!!,
                        DocumentCoverage.valueOf(x.str("coverage")!!), x.str("sha256"))
                },
                provider = o.str("provider") ?: "unknown",
            )
            "error" -> ProviderResponse.Failure(runCatching { ProviderFailure.valueOf(o.str("reason")!!) }.getOrDefault(ProviderFailure.PROVIDER_ERROR), o.str("message"))
            else -> ProviderResponse.Failure(ProviderFailure.BAD_RESPONSE, "unknown status")
        }
    }.getOrElse { ProviderResponse.Failure(ProviderFailure.BAD_RESPONSE, it.message?.take(200)) }

    /**
     * Server side: findings from an extractor's JSON reply `{"findings":[...]}`. Lenient about
     * numbers vs strings; [requestId] is forced so an extractor cannot retarget another request.
     */
    fun decodeFindings(text: String, requestId: String): List<ProposedFinding> {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        require(start in 0 until end) { "no JSON object in extractor reply" }
        val o = json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
        return (o["findings"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { f -> findingFrom(f, requestId) } }.take(10)
    }

    // --- helpers ----------------------------------------------------------------------------

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(k: String): Int? = str(k)?.trim()?.toIntOrNull()

    private fun geo(g: GeoScope): JsonObject = buildJsonObject {
        put("level", g.level.name)
        put("code", g.code)
        put("within", g.within?.let(::geo) ?: JsonNull)
    }

    private fun geo(o: JsonObject): GeoScope =
        GeoScope(GeoLevel.valueOf(o.str("level")!!), o.str("code")!!, (o["within"] as? JsonObject)?.let(::geo))

    private fun housing(h: HousingCharacteristics): JsonObject = buildJsonObject {
        put("rooms", h.rooms?.let(::JsonPrimitive) ?: JsonNull)
        put("roomsOrMore", h.roomsOrMore)
        put("tenancy", h.tenancy.name)
        put("includesCharges", h.includesCharges)
    }

    private fun housing(o: JsonObject) = HousingCharacteristics(
        rooms = o.int("rooms"),
        roomsOrMore = o.str("roomsOrMore") == "true",
        tenancy = TenancyDuration.valueOf(o.str("tenancy")!!),
        includesCharges = o.str("includesCharges") == "true",
    )

    private fun finding(f: ProposedFinding): JsonObject = buildJsonObject {
        put("requestId", f.requestId)
        listOf("category" to f.category, "value" to f.value, "plusMinus" to f.plusMinus, "low" to f.low, "high" to f.high,
            "statistic" to f.statistic, "currency" to f.currency, "unit" to f.unit, "basis" to f.basis, "geography" to f.geography,
            "tenancy" to f.tenancy, "publishedOn" to f.publishedOn, "sourceUrl" to f.sourceUrl, "organization" to f.organization,
            "title" to f.title, "quote" to f.quote, "locator" to f.locator).forEach { (k, v) -> v?.let { put(k, it) } }
        f.rooms?.let { put("rooms", it) }
        f.periodStart?.let { put("periodStart", it) }
        f.periodEnd?.let { put("periodEnd", it) }
        putJsonArray("limitations") { f.limitations.forEach { add(JsonPrimitive(it)) } }
    }

    private fun findingFrom(o: JsonObject, forcedRequestId: String?) = ProposedFinding(
        requestId = forcedRequestId ?: o.str("requestId").orEmpty(),
        category = o.str("category"), value = o.str("value"), plusMinus = o.str("plusMinus"), low = o.str("low"), high = o.str("high"),
        statistic = o.str("statistic"), currency = o.str("currency"), unit = o.str("unit"), basis = o.str("basis"),
        geography = o.str("geography"), rooms = o.int("rooms"), tenancy = o.str("tenancy"),
        periodStart = o.int("periodStart"), periodEnd = o.int("periodEnd"), publishedOn = o.str("publishedOn"),
        sourceUrl = o.str("sourceUrl"), organization = o.str("organization"), title = o.str("title"),
        quote = o.str("quote"), locator = o.str("locator"),
        limitations = (o["limitations"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.take(300) }.orEmpty().take(5),
    )

    private fun document(d: SourceDocument): JsonObject = buildJsonObject {
        put("url", d.url)
        put("retrievedOn", d.retrievedOn)
        put("mediaType", d.mediaType)
        put("text", d.text)
        put("coverage", d.coverage.name)
        d.sha256?.let { put("sha256", it) }
    }
}
