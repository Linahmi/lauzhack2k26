package ch.ripple.core

/*
 * Reality Check research: what the user knows -> what credible external evidence suggests ->
 * what Ripple calculates.
 *
 *  planResearch()   decides WHAT is worth researching (Skeptic sensitivity when computable,
 *                   otherwise a labelled heuristic catalogue per decision kind).
 *  ResearchProvider fetches proposals + the actual source documents (an LLM with web search
 *                   behind a server proxy; see research-server/). Untrusted.
 *  validateFinding  deterministic checks: the figure must be in a quoted passage that really
 *                   occurs in the retrieved document, with currency, unit, basis, geography and
 *                   period all parsed and compatible. An LLM citation alone never verifies.
 *  runResearch()    live attempt, then the cached research snapshot; never invents a figure.
 *
 * Nothing here touches a DecisionSession. A verified finding reaches the model only through
 * Hypothesis.UseResearchedEstimate, i.e. an explicit user action that creates a graph branch.
 */

/** Legacy research hints only; OTHER plus an open intention/category supports any life decision. */
enum class DecisionKind {
    MOVE_OUT, STUDY_VS_WORK, RELOCATION, JOB_CHANGE, START_FAMILY,
    /** Explicit paths that need no separate research catalogue. */
    DELAY_DECISION, STAY_CURRENT, OTHER,
}

/** What the user explicitly told us about the decision (nothing inferred). */
data class DecisionContext(
    val kind: DecisionKind = DecisionKind.OTHER,
    val location: GeoScope,
    /** Year the decision is evaluated in (supplied by the caller; core never reads a clock). */
    val asOfYear: Int,
    /** Dwelling the user described, for housing targets; null if not described. */
    val housing: HousingCharacteristics? = null,
    /** Free-form context survives even when [kind] is OTHER. */
    val intention: String? = null,
    val category: String? = kind.takeUnless { it == DecisionKind.OTHER }?.name?.lowercase(),
) {
    init {
        require(intention == null || intention.isNotBlank()) { "decision intention cannot be blank" }
        require(category == null || category.isNotBlank()) { "decision category cannot be blank" }
    }
}

data class ResearchVariable(val category: MarketCategory, val unit: MarketUnit, val basis: AmountBasis = AmountBasis.NOT_STATED)

enum class SourceTier { OFFICIAL_STATISTICS, PUBLIC_INSTITUTION, OFFICIAL_SERVICE_PROVIDER }

object ResearchCatalog {
    /** Team-authored heuristic: what tends to matter for each decision kind (not a computed priority). */
    val targets: Map<DecisionKind, List<ResearchVariable>> = mapOf(
        DecisionKind.MOVE_OUT to listOf(
            ResearchVariable(MarketCategory.HOUSING_NET_RENT, MarketUnit.CHF_PER_MONTH),
            ResearchVariable(MarketCategory.HOUSING_DEPOSIT, MarketUnit.CHF_ONE_OFF),
            ResearchVariable(MarketCategory.HOUSING_UTILITIES, MarketUnit.CHF_PER_MONTH),
        ),
        DecisionKind.STUDY_VS_WORK to listOf(
            ResearchVariable(MarketCategory.TUITION_FEE, MarketUnit.CHF_PER_SEMESTER),
            ResearchVariable(MarketCategory.ENTRY_SALARY, MarketUnit.CHF_PER_YEAR, AmountBasis.GROSS),
        ),
        DecisionKind.RELOCATION to listOf(
            ResearchVariable(MarketCategory.HOUSING_NET_RENT, MarketUnit.CHF_PER_MONTH),
            ResearchVariable(MarketCategory.HEALTH_INSURANCE_PREMIUM, MarketUnit.CHF_PER_MONTH),
            ResearchVariable(MarketCategory.COMMUTE_COST, MarketUnit.CHF_PER_MONTH),
        ),
        DecisionKind.JOB_CHANGE to listOf(
            ResearchVariable(MarketCategory.SALARY, MarketUnit.CHF_PER_YEAR, AmountBasis.GROSS),
            ResearchVariable(MarketCategory.COMMUTE_COST, MarketUnit.CHF_PER_MONTH),
        ),
        DecisionKind.START_FAMILY to listOf(
            ResearchVariable(MarketCategory.CHILDCARE_FEE, MarketUnit.CHF_PER_MONTH),
            ResearchVariable(MarketCategory.FAMILY_ALLOWANCE, MarketUnit.CHF_PER_MONTH),
        ),
    )

    /** Allowlisted hosts, most authoritative tier first. A host matches itself and its subdomains. */
    val sourceHosts: List<Pair<SourceTier, List<String>>> = listOf(
        SourceTier.OFFICIAL_STATISTICS to listOf("bfs.admin.ch", "statistik.admin.ch", "dam-api.bfs.admin.ch", "opendata.swiss", "vd.ch"),
        SourceTier.PUBLIC_INSTITUTION to listOf("admin.ch", "lausanne.ch", "epfl.ch", "unil.ch", "ethz.ch", "unige.ch"),
        SourceTier.OFFICIAL_SERVICE_PROVIDER to listOf("sbb.ch", "t-l.ch"),
    )

    val allHosts: List<String> get() = sourceHosts.flatMap { it.second }.distinct()
}

/** Tier of an https URL's host, or null if the host is not allowlisted. */
fun sourceTier(url: String): SourceTier? {
    if (!url.startsWith("https://")) return null
    val host = url.removePrefix("https://").substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':').lowercase()
    return ResearchCatalog.sourceHosts.firstOrNull { (_, hosts) -> hosts.any { host == it || host.endsWith(".$it") } }?.first
}

enum class PriorityBasis {
    /** Ranked by the engine: changing this input was shown to change the cash-buffer verdict. */
    COMPUTED_SENSITIVITY,
    /** Ranked by the team-authored catalogue only: relevance NOT established by computation. */
    HEURISTIC,
}

data class ResearchRequest(
    /** Stable id: the model input id, or "<category>@<area>" for context-only targets. */
    val id: String,
    val decision: DecisionKind,
    val query: MarketQuery,
    /** Model input this could feed; null = context only (cannot be applied to the simulation). */
    val inputId: String?,
    val priorityBasis: PriorityBasis,
    /** Engine result; null when [priorityBasis] is HEURISTIC (not computed). */
    val decisionChanging: Boolean?,
    /** Why it matters (engine facts, e.g. passes_up_to). Shown to the user; never sent to a provider. */
    val whyFacts: List<ExplanationFact>,
    val preferredHosts: List<String> = ResearchCatalog.allHosts,
)

enum class SkipReason { USER_CONFIRMED, NOT_DECISION_CHANGING, LIMIT_REACHED }

data class SkippedTarget(val inputId: String?, val category: MarketCategory, val reason: SkipReason)

data class ResearchPlan(val context: DecisionContext, val requests: List<ResearchRequest>, val skipped: List<SkippedTarget>)

private val HOUSING_CATEGORIES = setOf(MarketCategory.HOUSING_NET_RENT, MarketCategory.HOUSING_UTILITIES, MarketCategory.HOUSING_DEPOSIT)

/**
 * What to research, best first: (1) market-linked inputs the user has not confirmed, ranked by
 * the engine's sensitivity (only those that can change the verdict); (2) catalogue targets for
 * the decision kind that no input covers, labelled HEURISTIC and context-only. At most [maxRequests].
 */
fun planResearch(context: DecisionContext, session: DecisionSession? = null, maxRequests: Int = 3): ResearchPlan {
    require(maxRequests >= 0) { "maxRequests cannot be negative" }
    val checks = session?.let { brief(it).marketChecks }.orEmpty()
    val linked = session?.inputs?.filter { it.market != null }.orEmpty()
    val skipped = mutableListOf<SkippedTarget>()

    val computed = linked.mapNotNull { input ->
        val q = input.market!!
        val check = checks.first { it.inputId == input.id }
        when {
            session!!.state(input.id) is InputState.Exact -> { skipped += SkippedTarget(input.id, q.category, SkipReason.USER_CONFIRMED); null }
            !check.decisionSensitive -> { skipped += SkippedTarget(input.id, q.category, SkipReason.NOT_DECISION_CHANGING); null }
            else -> ResearchRequest(input.id, context.kind, q, input.id, PriorityBasis.COMPUTED_SENSITIVITY, true,
                check.facts.filter { it.provenance == Provenance.COMPUTED })
        }
    }.sortedByDescending { r -> checks.first { it.inputId == r.inputId }.sensitivity.swing }

    val covered = linked.map { it.market!!.category }.toSet()
    val heuristic = ResearchCatalog.targets[context.kind].orEmpty().filter { it.category !in covered }.map { v ->
        ResearchRequest("${v.category.name.lowercase()}@${context.location.code}", context.kind,
            MarketQuery(v.category, v.unit, context.location, context.housing.takeIf { v.category in HOUSING_CATEGORIES }, context.asOfYear, basis = v.basis),
            null, PriorityBasis.HEURISTIC, null, emptyList())
    }

    val all = computed + heuristic
    all.drop(maxRequests).forEach { skipped += SkippedTarget(it.inputId, it.query.category, SkipReason.LIMIT_REACHED) }
    return ResearchPlan(context, all.take(maxRequests), skipped)
}

// ---------------------------------------------------------------------------------------------
// Evidence: untrusted proposals + the retrieved documents they cite
// ---------------------------------------------------------------------------------------------

enum class DocumentCoverage { FULL_TEXT, EXCERPT }

/** Text actually retrieved from a source (by the server's own fetch, or stored in the cache). Data, never instructions. */
data class SourceDocument(
    val url: String,
    val retrievedOn: String,
    val mediaType: String,
    val text: String,
    val coverage: DocumentCoverage,
    /** SHA-256 of the downloaded file, for reproducibility. */
    val sha256: String? = null,
)

/** A figure an extractor (LLM) PROPOSES. Every field is untrusted until [validateFinding] passes. */
data class ProposedFinding(
    val requestId: String,
    val category: String? = null,
    /** The number exactly as printed, e.g. "899" or "1'432". */
    val value: String? = null,
    /** Published ± half-width, as printed. */
    val plusMinus: String? = null,
    val low: String? = null,
    val high: String? = null,
    val statistic: String? = null,
    val currency: String? = null,
    val unit: String? = null,
    /** e.g. "net, excluding charges" / "gross". */
    val basis: String? = null,
    val geography: String? = null,
    val rooms: Int? = null,
    /** e.g. "all tenancies" / "new tenancies (<= 1 year)". */
    val tenancy: String? = null,
    val periodStart: Int? = null,
    val periodEnd: Int? = null,
    val publishedOn: String? = null,
    val sourceUrl: String? = null,
    val organization: String? = null,
    val title: String? = null,
    /** Verbatim passage containing the figure. */
    val quote: String? = null,
    val locator: String? = null,
    /** Extractor's own notes; shown as unverified text only. */
    val limitations: List<String> = emptyList(),
)

enum class CheckId {
    REQUEST_MATCHES, SOURCE_FIELDS_PRESENT, AUTHORITATIVE_HOST, DOCUMENT_RETRIEVED, QUOTE_IN_DOCUMENT,
    NUMBER_PARSES, VALUE_IN_QUOTE, INTERVAL_IN_QUOTE, TABLE_COLUMN_MATCHES, CURRENCY_CHF, UNIT_RECOGNIZED, BASIS_DEFINED,
    HOUSING_DEFINED, GEOGRAPHY_RECOGNIZED, PERIOD_VALID, COMPARABLE_TO_REQUEST,
}

/** One deterministic check; [detail] is a short machine-readable reason for the Inspect view. */
data class FindingCheck(val id: CheckId, val passed: Boolean, val detail: String? = null)

data class FindingAssessment(
    val finding: ProposedFinding,
    val checks: List<FindingCheck>,
    /** Built only when every check passed. */
    val estimate: MarketEstimate?,
    /** Soft caveats of a verified estimate vs the request (stale, broader area, other tenancy…). */
    val issues: List<ComparabilityIssue>,
) {
    val verified: Boolean get() = estimate != null
    val failed: List<FindingCheck> get() = checks.filterNot { it.passed }
}

/** Deterministic validation of one proposed finding against the request and the retrieved documents. */
fun validateFinding(request: ResearchRequest, f: ProposedFinding, documents: List<SourceDocument>, mode: RetrievalMode): FindingAssessment {
    val q = request.query
    val checks = mutableListOf<FindingCheck>()
    fun check(id: CheckId, ok: Boolean, detail: String? = null) = ok.also { checks += FindingCheck(id, it, detail.takeUnless { ok }) }

    check(CheckId.REQUEST_MATCHES, f.requestId == request.id && f.category == q.category.name, "request=${f.requestId} category=${f.category}")
    val missing = listOfNotNull("sourceUrl".takeIf { f.sourceUrl.isNullOrBlank() }, "organization".takeIf { f.organization.isNullOrBlank() },
        "quote".takeIf { f.quote.isNullOrBlank() }, "periodEnd".takeIf { f.periodEnd == null }, "publishedOn".takeIf { f.publishedOn.isNullOrBlank() })
    check(CheckId.SOURCE_FIELDS_PRESENT, missing.isEmpty(), "missing=${missing.joinToString(",")}")
    val tier = f.sourceUrl?.let(::sourceTier)
    check(CheckId.AUTHORITATIVE_HOST, tier != null, "host_not_allowlisted")
    val doc = f.sourceUrl?.let { url -> documents.firstOrNull { sameUrl(it.url, url) } }
    check(CheckId.DOCUMENT_RETRIEVED, doc != null, "no_retrieved_document")
    val quote = f.quote.orEmpty()
    check(CheckId.QUOTE_IN_DOCUMENT, doc != null && quote.length in 8..500 && normalizeText(doc.text).contains(normalizeText(quote)), "quote_not_found")

    val value = f.value?.let(::parseChfAmount)
    val pm = f.plusMinus?.let(::parseChfAmount)
    val low = f.low?.let(::parseChfAmount)
    val high = f.high?.let(::parseChfAmount)
    val numbersOk = value != null && (f.plusMinus == null || pm != null) && (f.low == null || low != null) && (f.high == null || high != null) &&
        ((low == null) == (high == null)) && !(pm != null && low != null)
    check(CheckId.NUMBER_PARSES, numbersOk, "value=${f.value} pm=${f.plusMinus} low=${f.low} high=${f.high}")
    val inQuote = numbersIn(quote)
    check(CheckId.VALUE_IN_QUOTE, value != null && value in inQuote, "value_not_in_quote")
    check(CheckId.INTERVAL_IN_QUOTE, listOfNotNull(pm, low, high).all { it in inQuote }, "interval_not_in_quote")
    // Spreadsheet rows: the value must sit under a column header that matches the claim (not "Total", not the ± column).
    val isHousing = q.category in HOUSING_CATEGORIES
    val tableRow = doc != null && "spreadsheet" in doc.mediaType && " | " in quote
    val cell = if (tableRow && value != null) tableCells(doc!!, quote, value, pm).firstOrNull { it.matches(isHousing, f.rooms) } else null
    check(CheckId.TABLE_COLUMN_MATCHES, !tableRow || cell != null, "value_not_under_a_matching_column_header")
    check(CheckId.CURRENCY_CHF, f.currency?.trim()?.lowercase() in CHF_NAMES, "currency=${f.currency}")
    val unit = f.unit?.let(::parseUnit)
    check(CheckId.UNIT_RECOGNIZED, unit != null, "unit=${f.unit}")
    val basis = parseBasis(f.basis)
    // Housing needs net-vs-charges stated; categories with a required basis need gross/net stated.
    check(CheckId.BASIS_DEFINED, (!isHousing && q.basis == AmountBasis.NOT_STATED) || basis != AmountBasis.NOT_STATED, "basis=${f.basis}")
    val tenancy = parseTenancy(f.tenancy)
    check(CheckId.HOUSING_DEFINED, !isHousing || tenancy != null, "tenancy=${f.tenancy}")
    val scope = f.geography?.let(::parseGeography)
    check(CheckId.GEOGRAPHY_RECOGNIZED, scope != null, "geography=${f.geography}")
    val start = f.periodStart ?: f.periodEnd
    val end = f.periodEnd
    check(CheckId.PERIOD_VALID, start != null && end != null && start in 1900..end && end <= q.asOfYear &&
        f.publishedOn.orEmpty().take(4).toIntOrNull()?.let { it >= end } == true, "period=$start-$end published=${f.publishedOn}")

    if (checks.any { !it.passed }) return FindingAssessment(f, checks, null, emptyList())

    val (ciLow, ciHigh) = when {
        pm != null -> (value!! - pm) to (value + pm)
        low != null -> low to high!!
        else -> null to null
    }
    val estimate = runCatching {
        MarketEstimate(
            id = "research:${request.id}:${f.sourceUrl!!.removePrefix("https://").substringBefore('/')}:${value!!.rappen}:$end",
            category = q.category,
            scope = scope!!,
            housing = if (isHousing) HousingCharacteristics(f.rooms, tenancy = tenancy!!, includesCharges = basis == AmountBasis.GROSS) else null,
            value = MarketValue(value, unit!!, if (f.statistic?.lowercase()?.contains("median") == true) MarketStatistic.MEDIAN else MarketStatistic.MEAN,
                ciLow, ciHigh, ciLow?.let { 95 }),
            source = MarketSource(f.organization!!, f.title ?: f.organization, f.locator, f.sourceUrl, f.publishedOn!!,
                "not assessed; figure cited with attribution", RightsStatus.CITATION_ONLY_TERMS_UNVERIFIED),
            observed = ObservationPeriod(start!!, end!!),
            retrievedOn = doc!!.retrievedOn,
            kind = if (tier == SourceTier.OFFICIAL_STATISTICS) EvidenceKind.OBSERVED_OFFICIAL_STATISTIC else EvidenceKind.PUBLISHED_FIGURE,
            methodologyKeys = listOf("market.method.research_figure_found_in_source_passage"),
            basis = if (isHousing) AmountBasis.NOT_STATED else basis,
            retrieval = mode,
            quote = quote,
            // Deterministic locator for spreadsheets (from the document, not the extractor).
            locator = cell?.let { "row '${it.row}', column ${it.index + 1}, header '${it.header.joinToString(" / ")}'" } ?: f.locator,
        )
    }.getOrNull()
    // An interval that does not contain the point (MarketValue.init) counts as a parse failure.
    if (estimate == null) return FindingAssessment(f, checks + FindingCheck(CheckId.NUMBER_PARSES, false, "inconsistent_interval"), null, emptyList())

    val assessed = assess(q, estimate)
    if (!check(CheckId.COMPARABLE_TO_REQUEST, assessed.relevant, "issues=${assessed.issues.joinToString(",")}")) {
        return FindingAssessment(f, checks, null, assessed.issues)
    }
    return FindingAssessment(f, checks, estimate.copy(checks = checks.toList()), assessed.issues)
}

// ---------------------------------------------------------------------------------------------
// Providers, cache and the research run
// ---------------------------------------------------------------------------------------------

enum class ProviderFailure { NOT_CONFIGURED, NETWORK, TIMEOUT, RATE_LIMITED, PROVIDER_ERROR, REFUSED, BAD_RESPONSE }

sealed interface ProviderResponse {
    data class Success(val findings: List<ProposedFinding>, val documents: List<SourceDocument>, val provider: String) : ProviderResponse
    data class Failure(val reason: ProviderFailure, val message: String? = null) : ProviderResponse
}

/** Live research (blocking; call off the UI thread). Implementations must not throw, but [runResearch] tolerates it. */
fun interface ResearchProvider {
    fun research(request: ResearchRequest): ProviderResponse
}

/** Previously retrieved and verified evidence, shipped for offline demos. Always labelled CACHED_RESEARCH. */
data class ResearchSnapshot(val version: String, val findings: List<ProposedFinding>, val documents: List<SourceDocument>)

enum class ResearchMode { LIVE, CACHED }

data class ResearchAttempt(
    val mode: ResearchMode,
    val provider: String?,
    val failure: ProviderFailure?,
    val assessments: List<FindingAssessment>,
)

/** Two verified figures with the same definition whose published intervals (or points) do not overlap. */
data class ResearchConflict(val estimateIdA: String, val estimateIdB: String)

enum class UnresolvedReason { NO_PROVIDER_OR_CACHE, NO_VERIFIED_FINDINGS }

data class ResearchOutcome(
    val request: ResearchRequest,
    /** Every attempt in order (live, then cache), including rejected findings and failures. */
    val attempts: List<ResearchAttempt>,
    /** Verified findings of the attempt used, best first. */
    val verified: List<FindingAssessment>,
    /** Which attempt [verified] came from; null when unresolved. */
    val mode: ResearchMode?,
    val conflicts: List<ResearchConflict>,
    val unresolved: UnresolvedReason?,
    val cacheVersion: String?,
)

/**
 * Live research first (if a provider is given), then the cache. Every proposal is validated the
 * same way; the cache is only used when live research produced no verified finding. Pure apart
 * from calling [provider]; never receives or changes a session.
 */
fun runResearch(request: ResearchRequest, provider: ResearchProvider?, cache: ResearchSnapshot?): ResearchOutcome {
    val attempts = mutableListOf<ResearchAttempt>()
    if (provider != null) {
        val response = try { provider.research(request) } catch (e: Exception) { ProviderResponse.Failure(ProviderFailure.PROVIDER_ERROR, e.message) }
        attempts += when (response) {
            is ProviderResponse.Failure -> ResearchAttempt(ResearchMode.LIVE, null, response.reason, emptyList())
            is ProviderResponse.Success -> ResearchAttempt(ResearchMode.LIVE, response.provider, null,
                response.findings.map { validateFinding(request, it, response.documents, RetrievalMode.LIVE_RESEARCH) })
        }
    }
    if (attempts.none { a -> a.assessments.any { it.verified } } && cache != null) {
        val forRequest = cache.findings.filter { it.category == request.query.category.name }.map { it.copy(requestId = request.id) }
        attempts += ResearchAttempt(ResearchMode.CACHED, "cache:${cache.version}", null,
            forRequest.map { validateFinding(request, it, cache.documents, RetrievalMode.CACHED_RESEARCH) })
    }
    val used = attempts.firstOrNull { a -> a.assessments.any { it.verified } }
    val verified = used?.assessments.orEmpty().filter { it.verified }.sortedWith(
        compareBy({ it.issues.size }, { request.query.scope.chain.indexOf(it.estimate!!.scope) }, { it.estimate!!.id }))
    return ResearchOutcome(
        request = request,
        attempts = attempts,
        verified = verified,
        mode = used?.mode,
        conflicts = conflictsAmong(verified.map { it.estimate!! }),
        unresolved = when {
            used != null -> null
            attempts.isEmpty() -> UnresolvedReason.NO_PROVIDER_OR_CACHE
            else -> UnresolvedReason.NO_VERIFIED_FINDINGS
        },
        cacheVersion = cache?.version?.takeIf { used?.mode == ResearchMode.CACHED },
    )
}

/** A spreadsheet cell holding the value, with the header cells of its column. */
internal data class TableCell(val row: String, val index: Int, val header: List<String>, val intervalInNextColumn: Boolean)

/**
 * Cells of the quoted row that hold [value]. Header rows = rows above it whose first cell is empty.
 * shortcut: fits the FSO/cantonal table layout; upgrade to merged-cell parsing if other publishers need it.
 */
internal fun tableCells(doc: SourceDocument, quote: String, value: Chf, pm: Chf?): List<TableCell> {
    val lines = doc.text.lines()
    val q = quote.split(" | ").map(::normalizeText)
    lines.forEachIndexed { li, line ->
        val cells = line.split(" | ").map(::normalizeText)
        val start = (0..cells.size - q.size).firstOrNull { k -> q.indices.all { cells[k + it] == q[it] } } ?: return@forEachIndexed
        val header = lines.take(li).map { it.split(" | ") }.filter { it.first().isBlank() }
        return (start until start + q.size).filter { parseChfAmount(cells[it]) == value }.map { c ->
            TableCell(lines[li].split(" | ").first().trim(), c,
                header.mapNotNull { h -> h.getOrNull(c)?.trim()?.takeIf(String::isNotEmpty) },
                pm == null || cells.getOrNull(c + 1)?.let(::parseChfAmount) == pm)
        }
    }
    return emptyList()
}

private val INTERVAL_LABEL = Regex("intervall|interval|±|marge d")

internal fun TableCell.matches(isHousing: Boolean, rooms: Int?): Boolean {
    val h = header.map(::normalizeText)
    if (h.isEmpty() || h.any { INTERVAL_LABEL.containsMatchIn(it) } || !intervalInNextColumn) return false
    if (!isHousing) return true
    return if (rooms == null) h.any { it in setOf("total", "ensemble", "alle", "tous", "toutes") }
    else h.any { it == "$rooms" || it == "$rooms+" || Regex("^$rooms\\s*(zimmer|pièces?|pieces?|rooms?)\\b").containsMatchIn(it) }
}

private fun conflictsAmong(es: List<MarketEstimate>): List<ResearchConflict> = es.flatMapIndexed { i, a ->
    es.drop(i + 1).filter { b ->
        val sameDefinition = a.category == b.category && a.scope == b.scope && a.housing == b.housing &&
            a.basis == b.basis && a.value.unit == b.value.unit && a.value.statistic == b.value.statistic
        val overlappingPeriod = a.observed.endYear >= b.observed.startYear && b.observed.endYear >= a.observed.startYear
        val disjoint = (a.value.ciHigh ?: a.value.point) < (b.value.ciLow ?: b.value.point) ||
            (b.value.ciHigh ?: b.value.point) < (a.value.ciLow ?: a.value.point)
        sameDefinition && overlappingPeriod && disjoint
    }.map { ResearchConflict(a.id, it.id) }
}

/** What a researched-estimate branch changed, for a short "what changed" message. */
data class ResearchChange(
    val inputId: String,
    val estimate: MarketEstimate,
    val statusBefore: NodeStatus,
    val statusAfter: NodeStatus,
    val worstMinimumCashBefore: Chf,
    val worstMinimumCashAfter: Chf,
    val changed: List<String>,
)

fun GraphUpdate.researchChange(): ResearchChange? {
    val h = ((node.change as? GraphChange.Explored)?.hypothesis as? Hypothesis.UseResearchedEstimate) ?: return null
    return ResearchChange(h.inputId, h.estimate, before.status, after.status, before.worstMinimumCash, after.worstMinimumCash, changed)
}

// ---------------------------------------------------------------------------------------------
// Deterministic parsing helpers
// ---------------------------------------------------------------------------------------------

private val CHF_NAMES = setOf("chf", "fr.", "fr", "francs", "franc", "franken", "sfr", "sfr.", "swiss francs")

private fun sameUrl(a: String, b: String) = a.trimEnd('/') == b.trimEnd('/')

/** Lowercase, unify apostrophes/dashes, collapse whitespace (so line breaks in PDFs don't matter). */
internal fun normalizeText(s: String): String = s.lowercase()
    .replace('’', '\'').replace('‘', '\'').replace('–', '-').replace('—', '-')
    .replace(Regex("[\\s  ]+"), " ").trim()

/** "899", "1'432", "1’432", "1 432", "1,432", "1432.50", "CHF 1'432.–" -> Chf; null if not a single amount. */
internal fun parseChfAmount(s: String): Chf? {
    val t = s.trim().replace('’', '\'').replace(' ', ' ').replace(' ', ' ')
        .removePrefix("CHF").removePrefix("Fr.").trim().removeSuffix(".–").removeSuffix(".-").trim()
    val m = Regex("""^(\d{1,3}(?:['  ,]\d{3})+|\d+)(?:\.(\d{1,2}))?$""").find(t) ?: return null
    val francs = m.groupValues[1].filter(Char::isDigit).toLongOrNull() ?: return null
    return Chf(francs * 100 + m.groupValues[2].padEnd(2, '0').ifEmpty { "00" }.toLong())
}

/**
 * Every amount a passage could be read as: each digit run on its own AND grouped readings
 * ("13 896" -> 13, 896 and 13'896), so table rows with space-separated columns still match.
 */
internal fun numbersIn(text: String): Set<Chf> {
    val t = text.replace('’', '\'').replace(' ', ' ').replace(' ', ' ')
    val grouped = Regex("""\d{1,3}(?:['  ,]\d{3})+(?:\.\d{1,2})?""").findAll(t).mapNotNull { parseChfAmount(it.value) }
    val plain = Regex("""\d+(?:\.\d{1,2})?""").findAll(t).mapNotNull { parseChfAmount(it.value) }
    return (grouped + plain).toSet()
}

internal fun parseUnit(s: String): MarketUnit? {
    val t = s.lowercase()
    return when {
        "m2" in t || "m²" in t -> MarketUnit.CHF_PER_M2_PER_MONTH
        "semest" in t -> MarketUnit.CHF_PER_SEMESTER
        listOf("month", "mois", "monat", "mensuel", "/mo").any { it in t } -> MarketUnit.CHF_PER_MONTH
        listOf("year", "annual", "jahr", "annuel", "/an", "par an", "/yr").any { it in t } -> MarketUnit.CHF_PER_YEAR
        listOf("one-off", "one off", "one-time", "one time", "unique", "einmalig").any { it in t } -> MarketUnit.CHF_ONE_OFF
        else -> null
    }
}

internal fun parseBasis(s: String?): AmountBasis {
    val t = s?.lowercase() ?: return AmountBasis.NOT_STATED
    val gross = Regex("""\b(gross|brut|brutto|charges comprises|inkl\. nebenkosten|including charges)\b""").containsMatchIn(t)
    val net = Regex("""\b(net|netto|nette|hors charges|ohne neben|excluding charges|excl\. charges)\b""").containsMatchIn(t)
    return when {
        gross && net -> AmountBasis.NOT_STATED // conflicting definitions in one field: not defined
        gross -> AmountBasis.GROSS
        net -> AmountBasis.NET
        else -> AmountBasis.NOT_STATED
    }
}

internal fun parseTenancy(s: String?): TenancyDuration? {
    val t = s?.lowercase() ?: return null
    return when {
        listOf("1 year", "one year", "≤1", "<= 1", "un an ou moins", "new", "nouveau").any { it in t } -> TenancyDuration.UP_TO_ONE_YEAR
        listOf("all", "existing", "tous", "toutes", "alle", "occupied").any { it in t } -> TenancyDuration.ALL_DURATIONS
        else -> null
    }
}

private val GAZETTEER: Map<String, GeoScope> = buildMap {
    listOf("lausanne", "city of lausanne", "ville de lausanne", "commune de lausanne", "stadt lausanne").forEach { put(it, GeoScope.LAUSANNE) }
    listOf("vaud", "vd", "canton of vaud", "canton de vaud", "kanton waadt", "waadt").forEach { put(it, GeoScope.VD) }
    listOf("switzerland", "suisse", "schweiz", "svizzera", "ch").forEach { put(it, GeoScope.CH) }
}

/** Known area names only; anything else is unrecognized (never guessed). */
internal fun parseGeography(s: String): GeoScope? = GAZETTEER[normalizeText(s)]

// ---------------------------------------------------------------------------------------------
// Cached research snapshot: real passages retrieved on 2026-11-21 (see docs/RESEARCH_AGENT.md)
// ---------------------------------------------------------------------------------------------

object ResearchCache {
    private const val FSO_XLSX = "https://dam-api.bfs.admin.ch/hub/api/dam/assets/36506661/master"
    private const val STATVD_PDF = "https://www.vd.ch/fileadmin/user_upload/organisation/dfin/statvd/Publications/Numerus/Numerus-08-2026_Loyers.pdf"

    val documents = listOf(
        SourceDocument(
            url = FSO_XLSX, retrievedOn = "2026-11-21",
            mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            // Rows 1-5, 10, 17, 18, 23, 24 of the XLSX, cells at their column position joined with " | " (same as the server extractor).
            text = listOf(
                "Durchschnittlicher Mietpreis1) in Franken nach Zimmerzahl für die 10 grössten Städte |  |  |  |  |  |  |  |  |  |  |  |  |  | T 09.03.03.42",
                "2022-2024 kumuliert",
                " | Bewohnte Mietwohnungen mit … Zimmer(n)",
                " | Total |  | 1 |  | 2 |  | 3 |  | 4 |  | 5 |  | 6+",
                " | " + List(7) { "Durch-schnittlicher Mietpreis | Vertrauens-intervall : ± (in Fr.)" }.joinToString(" | "),
                "Lausanne | 1369 | 9 | 899 | 16 | 1177 | 13 | 1432 | 14 | 1736 | 24 | 2192 | 61 | 3166 | 203",
                "1) Monatlicher Netto-Mietzins (ohne Neben- und Heizkosten) der Mieter- und Genossenschafterwohnungen.",
                "Die Ergebnisse basieren auf drei aufeinanderfolgenden jährlichen Strukturerhebungen.",
                "Quellen: BFS – SE, GWS",
                "© BFS 2026",
            ).joinToString("\n"),
            coverage = DocumentCoverage.EXCERPT,
            sha256 = "9e941be0048f61e46ac6cb80249a2c57cd1bc3c8dd8bcee3e4583f85807663ae",
        ),
        SourceDocument(
            url = STATVD_PDF, retrievedOn = "2026-11-21", mediaType = "application/pdf",
            text = listOf(
                "Loyer moyen 2015 ± en francs1 Loyer moyen 2024 ± en francs1 1 pièce 716 13 896 15 2 pièces 997 11 1198 12 3 pièces 1264 11 1477 11 4 pièces 1616 18 1807 17 5 pièces et + 2173 59 2359 45 1 Intervalle de confiance à 95 %.",
                "Le loyer mensuel net relevé correspond au loyer du logement, hors charges (chauffage, eau chaude, etc.) et hors location de garage ou de place de stationnement.",
                "En 2024, le loyer moyen d’un logement de trois pièces occupé depuis un an ou moins s’élève à 1673 francs.",
            ).joinToString("\n"),
            coverage = DocumentCoverage.EXCERPT,
            sha256 = "e0b8b77fb3057de7ecc1b00f845afdeb9c0892a727cbc0beb0362eb0a13a8e1f",
        ),
    )

    private val rent = MarketCategory.HOUSING_NET_RENT.name

    val findings = listOf(
        ProposedFinding("cache", rent, value = "899", plusMinus = "16", statistic = "mean", currency = "CHF", unit = "CHF per month",
            basis = "net (ohne Neben- und Heizkosten)", geography = "Lausanne", rooms = 1, tenancy = "all tenancies (occupied dwellings)",
            periodStart = 2022, periodEnd = 2024, publishedOn = "2026-03-26", sourceUrl = FSO_XLSX, organization = "Swiss Federal Statistical Office (FSO/BFS)",
            title = "T 09.03.03.42 Durchschnittlicher Mietpreis nach Zimmerzahl, 10 grösste Städte, 2022-2024 kumuliert",
            quote = "Lausanne | 1369 | 9 | 899 | 16 | 1177 | 13 | 1432 | 14", locator = "row Lausanne, column 1 room (mean, ±)"),
        ProposedFinding("cache", rent, value = "896", plusMinus = "15", statistic = "mean", currency = "CHF", unit = "francs par mois",
            basis = "net, hors charges", geography = "Vaud", rooms = 1, tenancy = "all tenancies (occupied dwellings)",
            periodStart = 2024, periodEnd = 2024, publishedOn = "2026-11", sourceUrl = STATVD_PDF, organization = "Statistique Vaud",
            title = "Numerus 8-2026, Loyers vaudois", quote = "1 pièce 716 13 896 15", locator = "[T1], row 1 pièce, 2024 (mean, ±)"),
        // Kept on purpose: a 3-room new-lease figure is NOT comparable to a 1-room query (rejected as DIFFERENT_SIZE).
        ProposedFinding("cache", rent, value = "1673", statistic = "mean", currency = "CHF", unit = "francs par mois",
            basis = "net, hors charges", geography = "Vaud", rooms = 3, tenancy = "occupé depuis un an ou moins",
            periodStart = 2024, periodEnd = 2024, publishedOn = "2026-11", sourceUrl = STATVD_PDF, organization = "Statistique Vaud",
            title = "Numerus 8-2026, Loyers vaudois",
            quote = "En 2024, le loyer moyen d’un logement de trois pièces occupé depuis un an ou moins s’élève à 1673 francs.", locator = "p. 3"),
    )

    val snapshot = ResearchSnapshot("research-cache-2026-11-21", findings, documents)
}
