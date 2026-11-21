package ch.ripple.core

/*
 * Market grounding. Three kinds of information are kept apart, everywhere:
 *  - what the user told us   -> InputState (Exact / Range / Unknown). Never overwritten here.
 *  - what the market suggests -> MarketEstimate: a dated, sourced statistic about a POPULATION of
 *    dwellings. Never a fact about this person, never a calculated result.
 *  - what Ripple calculated   -> simulate()/stress results at the values the user chose to test.
 * A market estimate enters a calculation only when the user explicitly tries it as a what-if
 * (Hypothesis.UseMarketEstimate). It is then labelled EXTERNAL_SOURCE in a hypothetical branch.
 * Nothing here reads a clock, the network or a file: same snapshot + inputs -> same output.
 */

enum class MarketCategory { HOUSING_NET_RENT }

enum class MarketUnit { CHF_PER_MONTH, CHF_PER_M2_PER_MONTH }

enum class GeoLevel { COUNTRY, CANTON, MUNICIPALITY }

/** A geographic area; [within] is the enclosing area (Lausanne within VD within CH). */
data class GeoScope(val level: GeoLevel, val code: String, val within: GeoScope? = null) {
    /** This area, then every enclosing one. */
    val chain: List<GeoScope> get() = generateSequence(this) { it.within }.toList()

    companion object {
        val CH = GeoScope(GeoLevel.COUNTRY, "CH")
        val VD = GeoScope(GeoLevel.CANTON, "VD", CH)
        /** FSO commune number 5586. */
        val LAUSANNE = GeoScope(GeoLevel.MUNICIPALITY, "5586-Lausanne", VD)
    }
}

/** How long the household has lived in the dwelling (Statistique Vaud / FSO "durée de location"). */
enum class TenancyDuration { ALL_DURATIONS, UP_TO_ONE_YEAR }

data class HousingCharacteristics(
    /** Number of rooms (FSO definition: kitchen, bathroom, half-rooms not counted); null = all sizes. */
    val rooms: Int?,
    /** True for open-ended classes such as "5 rooms and more". */
    val roomsOrMore: Boolean = false,
    val tenancy: TenancyDuration,
    /** False = net rent, without charges (heating, hot water…) and parking. */
    val includesCharges: Boolean,
)

enum class MarketStatistic { MEAN, MEDIAN }

/**
 * A published value. [ciLow]..[ciHigh] is the PUBLISHED confidence interval of the statistic
 * (e.g. of the mean), not the spread of individual rents; null when the source prints none.
 */
data class MarketValue(
    val point: Chf,
    val unit: MarketUnit,
    val statistic: MarketStatistic,
    val ciLow: Chf? = null,
    val ciHigh: Chf? = null,
    val confidenceLevelPercent: Int? = null,
) {
    init {
        require(!point.isNegative) { "market value cannot be negative" }
        require((ciLow == null) == (ciHigh == null) && (ciLow == null) == (confidenceLevelPercent == null)) {
            "give both interval bounds and the confidence level, or none"
        }
        if (ciLow != null) require(ciLow <= point && point <= ciHigh!!) { "interval must contain the point value" }
    }
}

enum class EvidenceKind {
    /** A figure printed by an official statistics office. */
    OBSERVED_OFFICIAL_STATISTIC,
    /** A value chosen by the team (not observed). Never used for the shipped snapshot. */
    ASSUMPTION,
}

enum class RightsStatus {
    /** Explicit open terms (e.g. FSO "OPEN-BY: open use, must provide the source"). */
    OPEN_WITH_ATTRIBUTION,
    /** No reuse licence found; only a few published figures are cited, with attribution. */
    CITATION_ONLY_TERMS_UNVERIFIED,
}

data class MarketSource(
    val publisher: String,
    val title: String,
    val tableId: String?,
    val url: String,
    /** ISO date (yyyy-mm-dd or yyyy-mm when only the month is printed). */
    val publishedOn: String,
    val termsOfUse: String,
    val rights: RightsStatus,
)

data class ObservationPeriod(val startYear: Int, val endYear: Int) {
    init { require(startYear <= endYear) { "startYear must be <= endYear" } }
}

data class MarketEstimate(
    val id: String,
    val category: MarketCategory,
    val scope: GeoScope,
    val housing: HousingCharacteristics,
    val value: MarketValue,
    val source: MarketSource,
    val observed: ObservationPeriod,
    /** ISO date the team retrieved and checked the figure. */
    val retrievedOn: String,
    val kind: EvidenceKind,
    /** Localization keys describing how the figure was produced and its limits. */
    val methodologyKeys: List<String>,
)

/** An immutable, versioned set of estimates. A new version is a new object, never a mutation. */
data class MarketSnapshot(val version: String, val estimates: List<MarketEstimate>) {
    init { require(estimates.map { it.id }.toSet().size == estimates.size) { "duplicate estimate ids" } }
    fun estimate(id: String): MarketEstimate? = estimates.firstOrNull { it.id == id }
}

/** What a decision input means in market terms, so estimates can be matched to it. */
data class MarketQuery(
    val category: MarketCategory,
    val unit: MarketUnit,
    val scope: GeoScope,
    val housing: HousingCharacteristics,
    /** Year the decision is evaluated in, supplied by the caller (core never reads a clock). */
    val asOfYear: Int,
    /** Observations ending more than this many years before [asOfYear] are STALE. */
    val maxAgeYears: Int = 3,
)

enum class ComparabilityIssue {
    // Hard: not a benchmark for this input at all (never offered, never compared).
    DIFFERENT_CATEGORY, INCOMPATIBLE_UNIT, DIFFERENT_CHARGES_BASIS, DIFFERENT_SIZE, DIFFERENT_GEOGRAPHY,
    // Soft: shown as context with its caveat, but no mismatch may be claimed.
    BROADER_GEOGRAPHY, DIFFERENT_TENANCY_DURATION, STALE, NO_PUBLISHED_INTERVAL,
}

private val HARD_ISSUES = setOf(
    ComparabilityIssue.DIFFERENT_CATEGORY, ComparabilityIssue.INCOMPATIBLE_UNIT, ComparabilityIssue.DIFFERENT_CHARGES_BASIS,
    ComparabilityIssue.DIFFERENT_SIZE, ComparabilityIssue.DIFFERENT_GEOGRAPHY,
)

data class AssessedEstimate(val estimate: MarketEstimate, val issues: List<ComparabilityIssue>) {
    /** Can be offered as a what-if and shown as context. */
    val relevant: Boolean get() = issues.none { it in HARD_ISSUES }
    /** Close enough to the user's situation that a difference may be pointed out. */
    val comparable: Boolean get() = issues.isEmpty()
}

/** Deterministic comparability rules (category, unit, charges, size, geography, tenancy, age, interval). */
fun assess(query: MarketQuery, e: MarketEstimate): AssessedEstimate {
    val q = query.housing
    val h = e.housing
    val geoIndex = query.scope.chain.indexOf(e.scope)
    val sizeMatches = q.rooms != null && h.rooms != null &&
        (q.rooms == h.rooms || (h.roomsOrMore && q.rooms >= h.rooms))
    return AssessedEstimate(e, listOfNotNull(
        ComparabilityIssue.DIFFERENT_CATEGORY.takeIf { e.category != query.category },
        ComparabilityIssue.INCOMPATIBLE_UNIT.takeIf { e.value.unit != query.unit },
        ComparabilityIssue.DIFFERENT_CHARGES_BASIS.takeIf { h.includesCharges != q.includesCharges },
        ComparabilityIssue.DIFFERENT_SIZE.takeIf { !sizeMatches && !(q.rooms == null && h.rooms == null) },
        ComparabilityIssue.DIFFERENT_GEOGRAPHY.takeIf { geoIndex < 0 },
        ComparabilityIssue.BROADER_GEOGRAPHY.takeIf { geoIndex > 0 },
        ComparabilityIssue.DIFFERENT_TENANCY_DURATION.takeIf { h.tenancy != q.tenancy },
        ComparabilityIssue.STALE.takeIf { query.asOfYear - e.observed.endYear > query.maxAgeYears },
        ComparabilityIssue.NO_PUBLISHED_INTERVAL.takeIf { e.value.ciLow == null },
    ))
}

/** Relevant estimates for [query], best first: fewest caveats, then narrowest geography, then id. */
fun MarketSnapshot.benchmarksFor(query: MarketQuery): List<AssessedEstimate> =
    estimates.map { assess(query, it) }.filter { it.relevant }
        .sortedWith(compareBy({ it.issues.size }, { query.scope.chain.indexOf(it.estimate.scope) }, { it.estimate.id }))

/** What the user did with an estimate. Stored per estimate id in the session. */
enum class MarketReview { TRIED_AS_ASSUMPTION, REPLACED_BY_USER_VALUE, REJECTED }

// ---------------------------------------------------------------------------------------------
// Market Reality Check
// ---------------------------------------------------------------------------------------------

enum class MarketCheckStatus {
    /** No relevant estimate in the snapshot (or all were rejected): nothing to say about the market. */
    NO_MARKET_DATA,
    /** The user has not stated a value: nothing to compare yet; estimates may be offered. */
    VALUE_NOT_STATED,
    /** Only estimates with caveats (broader area, other tenancy, stale…): no mismatch is claimed. */
    NOT_DIRECTLY_COMPARABLE,
    /** Compared with a fully comparable estimate's published interval (an average, not a range of rents). */
    BELOW_AVERAGE_INTERVAL, WITHIN_AVERAGE_INTERVAL, ABOVE_AVERAGE_INTERVAL,
}

/** Engine results with the input set to an estimate's point value and interval bounds. */
data class BenchmarkOutcome(
    val estimateId: String,
    val worstMinimumCashAtPoint: Chf,
    val passesAtPoint: Boolean,
    /** Null when the estimate has no published interval. */
    val passesAtIntervalLow: Boolean?,
    val passesAtIntervalHigh: Boolean?,
)

data class MarketRealityCheck(
    val inputId: String,
    /** The user's own information (never replaced by market data). */
    val state: InputState,
    /** Estimate this branch is trying as a what-if, if any. */
    val assumedEstimateId: String?,
    /** Relevant, non-rejected estimates, best first. */
    val benchmarks: List<AssessedEstimate>,
    val rejectedEstimateIds: List<String>,
    val status: MarketCheckStatus,
    /** The fully comparable estimate the status compares against (null unless BELOW/WITHIN/ABOVE). */
    val comparedWith: String?,
    /**
     * Computed: worst-case cash over every stress case for each value of the input on its grid
     * (the user's range, or the declared bounds when the value is unknown or exact), with the
     * other inputs at the briefing's worst case. `passesUpTo` is the threshold.
     */
    val sensitivity: UnknownImpact,
    val benchmarkOutcomes: List<BenchmarkOutcome>,
    /** True when the user's own information still allows both a passing and a failing value. */
    val needsExactValue: Boolean,
    val facts: List<ExplanationFact>,
    val limitationKeys: List<String>,
) {
    /** Could a different value of this input change whether the cash buffer holds? */
    val decisionSensitive: Boolean get() = sensitivity.changesDecision

    /** "Help me estimate" is available: estimates exist, the user has no exact value, none is being tried. */
    val offersEstimate: Boolean get() = benchmarks.isNotEmpty() && state !is InputState.Exact && assumedEstimateId == null
}

object MarketKeys {
    const val DISCLOSURE_MARKET_ASSUMPTION = "disclosure.market_estimate_is_not_your_value"
    const val LIMIT_NOT_ABOUT_YOU = "limitation.market.population_statistic_not_about_you"
    const val LIMIT_AVERAGE_NOT_RANGE = "limitation.market.interval_is_for_the_average_not_individual_rents"
    const val LIMIT_CHARGES_EXCLUDED = "limitation.market.net_rent_excludes_charges"
    const val LIMIT_TENANCY = "limitation.market.existing_tenancies_differ_from_new_leases"
    const val LIMIT_BROADER_AREA = "limitation.market.broader_area"
    const val LIMIT_STALE = "limitation.market.stale_observation"
    const val LIMIT_NO_INTERVAL = "limitation.market.no_published_interval"
    const val LIMIT_NO_COMPARABLE = "limitation.market.no_directly_comparable_benchmark"
    const val LIMIT_NO_DATA = "limitation.market.no_market_data"
}

private fun ComparabilityIssue.limitationKey(): String? = when (this) {
    ComparabilityIssue.BROADER_GEOGRAPHY -> MarketKeys.LIMIT_BROADER_AREA
    ComparabilityIssue.DIFFERENT_TENANCY_DURATION -> MarketKeys.LIMIT_TENANCY
    ComparabilityIssue.STALE -> MarketKeys.LIMIT_STALE
    ComparabilityIssue.NO_PUBLISHED_INTERVAL -> MarketKeys.LIMIT_NO_INTERVAL
    else -> null // hard issues never reach a check
}

/**
 * The reality check for one market-linked input. Called by brief() with its worst-case inputs.
 * Every Chf here is either copied from an estimate (EXTERNAL_SOURCE) or computed by the engine.
 */
internal fun DecisionSession.marketCheck(input: DecisionInput, worstInputs: Map<String, Chf>): MarketRealityCheck? {
    val query = input.market ?: return null
    val floor = effectiveFloor
    val relevant = marketData?.benchmarksFor(query).orEmpty()
    val rejected = relevant.map { it.estimate.id }.filter { marketReviews[it] == MarketReview.REJECTED }
    val benchmarks = relevant.filterNot { it.estimate.id in rejected }
    val state = state(input.id)
    val scenarioAt = { v: Chf -> scenarioFor(worstInputs + (input.id to v)) }
    val worstAt = { v: Chf -> stressProfile(profile, scenarioAt(v), stress).worstMinimum }

    // The user's own information decides the grid; an exact value is still probed over the declared bounds.
    val grid = input.candidates(if (state is InputState.Exact) InputState.Unknown else state)
    val sensitivity = analyzeUnknown(profile, UnknownInput(input.id, grid.first(), grid.last(), input.step, scenarioAt), stress, floor)

    val outcomes = benchmarks.map { b ->
        val v = b.estimate.value
        val atPoint = worstAt(v.point)
        BenchmarkOutcome(b.estimate.id, atPoint, atPoint >= floor,
            v.ciLow?.let { worstAt(it) >= floor }, v.ciHigh?.let { worstAt(it) >= floor })
    }

    val reference = benchmarks.firstOrNull { it.comparable }
    val status = when {
        benchmarks.isEmpty() -> MarketCheckStatus.NO_MARKET_DATA
        state is InputState.Unknown -> MarketCheckStatus.VALUE_NOT_STATED
        reference == null -> MarketCheckStatus.NOT_DIRECTLY_COMPARABLE
        else -> {
            val (lo, hi) = reference.estimate.value.let { it.ciLow!! to it.ciHigh!! }
            val (vLo, vHi) = when (state) {
                is InputState.Exact -> state.value to state.value
                is InputState.Range -> state.low to state.high
                InputState.Unknown -> error("unreachable")
            }
            when {
                vHi < lo -> MarketCheckStatus.BELOW_AVERAGE_INTERVAL
                vLo > hi -> MarketCheckStatus.ABOVE_AVERAGE_INTERVAL
                else -> MarketCheckStatus.WITHIN_AVERAGE_INTERVAL
            }
        }
    }

    val ref = "market-check:${input.id}"
    val top = benchmarks.firstOrNull()
    val topOutcome = outcomes.firstOrNull()
    val facts = listOfNotNull(
        inputFact(input, state),
        ExplanationFact("floor", FactValue.Money(floor), if (constraints.minCashBuffer != null) Provenance.USER_STATED else Provenance.ASSUMED, "session:floor"),
        sensitivity.passesUpTo?.let { ExplanationFact("passes_up_to", FactValue.Money(it), Provenance.COMPUTED, ref) },
        ExplanationFact("swing", FactValue.Money(sensitivity.swing), Provenance.COMPUTED, ref),
        ExplanationFact("values_checked", FactValue.Count(sensitivity.worstMinimumCashByValue.size), Provenance.COMPUTED, ref),
        top?.let { ExplanationFact("benchmark", FactValue.Money(it.estimate.value.point), Provenance.EXTERNAL_SOURCE, "market:${it.estimate.id}") },
        top?.estimate?.value?.ciLow?.let { lo ->
            ExplanationFact("benchmark_interval", FactValue.MoneyRange(lo, top.estimate.value.ciHigh!!), Provenance.EXTERNAL_SOURCE, "market:${top.estimate.id}")
        },
        topOutcome?.let { ExplanationFact("worst_min_cash_at_benchmark", FactValue.Money(it.worstMinimumCashAtPoint), Provenance.COMPUTED, "$ref:${it.estimateId}") },
    )
    val limitations = buildList {
        add(MarketKeys.LIMIT_NOT_ABOUT_YOU)
        if (top == null) add(MarketKeys.LIMIT_NO_DATA) else {
            add(MarketKeys.LIMIT_AVERAGE_NOT_RANGE)
            if (!query.housing.includesCharges) add(MarketKeys.LIMIT_CHARGES_EXCLUDED)
            top.issues.mapNotNullTo(this) { it.limitationKey() }
            if (reference == null) add(MarketKeys.LIMIT_NO_COMPARABLE)
        }
    }.distinct()

    return MarketRealityCheck(
        inputId = input.id,
        state = state,
        assumedEstimateId = marketAssumptions[input.id],
        benchmarks = benchmarks,
        rejectedEstimateIds = rejected,
        status = status,
        comparedWith = reference?.estimate?.id?.takeIf { status in COMPARED },
        sensitivity = sensitivity,
        benchmarkOutcomes = outcomes,
        needsExactValue = state !is InputState.Exact && sensitivity.changesDecision,
        facts = facts,
        limitationKeys = limitations,
    )
}

private val COMPARED = setOf(
    MarketCheckStatus.BELOW_AVERAGE_INTERVAL, MarketCheckStatus.WITHIN_AVERAGE_INTERVAL, MarketCheckStatus.ABOVE_AVERAGE_INTERVAL,
)

/** Reality check for [inputId] in the active node (null if the input has no market link). */
fun DecisionGraph.marketCheck(inputId: String, id: String = activeNodeId): MarketRealityCheck? =
    briefing(id).marketChecks.firstOrNull { it.inputId == inputId }

/**
 * Attach a new market snapshot as a NEW child node of [fromId]; older nodes keep the snapshot
 * they were computed with. A what-if that used an estimate follows it by id: if the new snapshot
 * still has it (and it is still relevant), the branch uses the new value; otherwise the what-if
 * is dropped and listed in the change. Nothing is rewritten in place.
 */
fun DecisionGraph.updateMarketData(snapshot: MarketSnapshot, fromId: String = activeNodeId): GraphUpdate {
    val s = node(fromId).session
    val kept = s.marketAssumptions.filter { (inputId, estimateId) ->
        val q = s.inputs.first { it.id == inputId }.market!!
        snapshot.estimate(estimateId)?.let { assess(q, it).relevant } == true
    }
    val dropped = (s.marketAssumptions - kept.keys)
    val session = s.copy(
        marketData = snapshot,
        marketAssumptions = kept,
        hypotheticalValues = s.hypotheticalValues - dropped.keys +
            kept.mapValues { (_, estimateId) -> snapshot.estimate(estimateId)!!.value.point },
    )
    return addChild(fromId, GraphChange.MarketDataUpdated(s.marketData?.version, snapshot.version, dropped.keys.toList()), session, node(fromId).hypothetical)
}

// ---------------------------------------------------------------------------------------------
// Versioned snapshot (sourced figures only). See docs/MARKET_DATA.md for rights and limits.
// ---------------------------------------------------------------------------------------------

object MarketData {
    private val FSO_CITY_RENTS = MarketSource(
        publisher = "Swiss Federal Statistical Office (FSO/BFS)",
        title = "Durchschnittlicher Mietpreis in Franken nach Zimmerzahl für die 10 grössten Städte, 2022-2024 kumuliert",
        tableId = "je-d-09.03.03.42_2022-2024",
        url = "https://www.bfs.admin.ch/asset/de/36506661",
        publishedOn = "2026-03-26",
        termsOfUse = "OPEN-BY: open use, must provide the source",
        rights = RightsStatus.OPEN_WITH_ATTRIBUTION,
    )
    private val STATVD_NUMERUS = MarketSource(
        publisher = "Statistique Vaud",
        title = "Numerus 8-2026, Loyers vaudois: une hausse proche de la tendance suisse",
        tableId = "Numerus 8-2026 [T1]",
        url = "https://www.vd.ch/fileadmin/user_upload/organisation/dfin/statvd/Publications/Numerus/Numerus-08-2026_Loyers.pdf",
        publishedOn = "2026-11",
        termsOfUse = "© Statistique Vaud; no reuse licence found in the document",
        rights = RightsStatus.CITATION_ONLY_TERMS_UNVERIFIED,
    )
    private const val RETRIEVED = "2026-11-21"
    private val FSO_METHOD = listOf(
        "market.method.fso_structural_survey_3_years_pooled",
        "market.method.net_rent_excluding_charges_and_heating",
        "market.method.rented_and_cooperative_dwellings",
        "market.method.all_tenancy_durations",
        "market.method.mean_with_95_percent_interval",
    )
    private val STATVD_METHOD = listOf(
        "market.method.fso_structural_survey_single_year",
        "market.method.net_rent_excluding_charges_and_parking",
        "market.method.tenants_subtenants_and_cooperative_members",
    )

    private fun fsoLausanne(rooms: Int, mean: Long, ci: Long) = MarketEstimate(
        id = "fso-lausanne-${rooms}r-2022-2024",
        category = MarketCategory.HOUSING_NET_RENT,
        scope = GeoScope.LAUSANNE,
        housing = HousingCharacteristics(rooms, tenancy = TenancyDuration.ALL_DURATIONS, includesCharges = false),
        value = MarketValue(Chf.francs(mean), MarketUnit.CHF_PER_MONTH, MarketStatistic.MEAN, Chf.francs(mean - ci), Chf.francs(mean + ci), 95),
        source = FSO_CITY_RENTS,
        observed = ObservationPeriod(2022, 2024),
        retrievedOn = RETRIEVED,
        kind = EvidenceKind.OBSERVED_OFFICIAL_STATISTIC,
        methodologyKeys = FSO_METHOD,
    )

    private fun statVd(id: String, rooms: Int, tenancy: TenancyDuration, mean: Long, ci: Long?, table: String) = MarketEstimate(
        id = id,
        category = MarketCategory.HOUSING_NET_RENT,
        scope = GeoScope.VD,
        housing = HousingCharacteristics(rooms, tenancy = tenancy, includesCharges = false),
        value = MarketValue(Chf.francs(mean), MarketUnit.CHF_PER_MONTH, MarketStatistic.MEAN,
            ci?.let { Chf.francs(mean - it) }, ci?.let { Chf.francs(mean + it) }, ci?.let { 95 }),
        source = STATVD_NUMERUS.copy(tableId = table),
        observed = ObservationPeriod(2024, 2024),
        retrievedOn = RETRIEVED,
        kind = EvidenceKind.OBSERVED_OFFICIAL_STATISTIC,
        methodologyKeys = STATVD_METHOD + if (ci == null) listOf("market.method.interval_not_printed_in_text") else listOf("market.method.mean_with_95_percent_interval"),
    )

    /** Figures copied exactly as printed; ± is the published 95% interval of the mean. */
    val vaudHousing = MarketSnapshot(
        version = "vd-housing-2026-11-21",
        estimates = listOf(
            fsoLausanne(1, 899, 16),
            fsoLausanne(2, 1_177, 13),
            fsoLausanne(3, 1_432, 14),
            statVd("statvd-vd-1r-2024", 1, TenancyDuration.ALL_DURATIONS, 896, 15, "Numerus 8-2026 [T1]"),
            statVd("statvd-vd-2r-2024", 2, TenancyDuration.ALL_DURATIONS, 1_198, 12, "Numerus 8-2026 [T1]"),
            statVd("statvd-vd-3r-2024", 3, TenancyDuration.ALL_DURATIONS, 1_477, 11, "Numerus 8-2026 [T1]"),
            statVd("statvd-vd-3r-new-2024", 3, TenancyDuration.UP_TO_ONE_YEAR, 1_673, null, "Numerus 8-2026, text p. 3 [F6]"),
        ),
    )
}
