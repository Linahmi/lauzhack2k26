# Market-grounded financial twin (core)

**Principle:** keep separate what the user told us (`InputState`), what the market suggests
(`MarketEstimate`), and what Ripple calculated (engine results). A market figure is a dated,
sourced statistic about a population of dwellings. It is never a fact about the user, and it
enters a calculation only when the user picks it as a what-if.

Code: `core/src/commonMain/kotlin/ch/ripple/core/Market.kt`. Tests: `core/src/commonTest/.../MarketTest.kt`.

## Integrated sources (snapshot `vd-housing-2026-11-21`)

I fetched and read both sources on **2026-11-21**. Every figure is copied exactly as printed. "±" is the
published 95 % confidence interval **of the mean**, not the spread of individual rents.

### 1. FSO / BFS, Structural Survey: average rent for the 10 largest cities, 2022-2024 pooled

- Table `je-d-09.03.03.42_2022-2024` (T 09.03.03.42), published **2026-03-26**, © BFS 2026.
  URL: https://www.bfs.admin.ch/asset/de/36506661 (XLSX: `dam-api.bfs.admin.ch/hub/api/dam/assets/36506661/master`).
- **Terms:** "OPEN-BY: open use, must provide the source". Commercial and non-commercial use are
  allowed with attribution, so bundling these figures is permitted (`RightsStatus.OPEN_WITH_ATTRIBUTION`).
- **Observation:** pooled across 3 annual Structural Surveys (2022, 2023, 2024). **Geography:** City of Lausanne.
- **What it measures:** mean *net* monthly rent, excluding ancillary and heating costs. It covers
  rented and cooperative dwellings currently occupied, for **all tenancy durations**, so it is not
  the price of new leases. Population: persons 15+ in private households of the permanent resident population.
- Bundled values (Lausanne): 1 room **CHF 899 ± 16**, 2 rooms **CHF 1,177 ± 13**, 3 rooms **CHF 1,432 ± 14**.

### 2. Statistique Vaud, *Numerus* 8-2026 "Loyers vaudois: une hausse proche de la tendance suisse"

- November 2026. URL: https://www.vd.ch/fileadmin/user_upload/organisation/dfin/statvd/Publications/Numerus/Numerus-08-2026_Loyers.pdf
- **Data source (as stated):** FSO Structural Survey (RS) plus the Buildings and Dwellings Statistic (StatBL).
  Each year about 6 % of the Vaud population is interviewed. Students who live in the canton as a secondary residence are not included.
- **Observation:** 2024. **Geography:** Canton of Vaud. Net monthly rent, excluding charges (heating, hot
  water…) and parking. Covers tenants, subtenants and cooperative members.
- Bundled values: Vaud 2024 by rooms [T1]: 1 room **CHF 896 ± 15**, 2 rooms **CHF 1,198 ± 12**, 3 rooms **CHF 1,477 ± 11**.
  3-room dwellings **occupied for one year or less: CHF 1,673** (text p. 3). The text prints no interval for this figure,
  so the code gives it no interval.
- **Rights:** the PDF says "© Statistique Vaud" and I found no reuse licence in it. We cite 4 printed figures
  with attribution and copy no table or graphic (`RightsStatus.CITATION_ONLY_TERMS_UNVERIFIED`).
  **Open item:** ask Statistique Vaud (info.stat@vd.ch) to confirm. If they refuse, delete the `statvd-*` entries. The FSO entries cover Lausanne by themselves.

### Limitations, which the engine also exposes as `limitationKeys`

- All of these figures describe **existing** tenancies. New leases are typically more expensive: Statistique Vaud reports
  CHF 1,673 for 3-room dwellings occupied ≤ 1 year, against a CHF 1,477 average for all 3-room dwellings. Our sources have no new-lease figure for 1-room Lausanne flats.
  Ripple therefore **never claims** a mismatch between a new-lease rent and these averages.
- The figures are means with an interval for the mean. They say nothing about how widely individual rents vary, and there are no quantiles or medians.
- They are net rents, so charges must be modelled separately. In the Alex scenario charges are a fictional CHF 150 (ASSUMED).
- One-time housing costs (deposit, moving) are not covered by any source. They stay user inputs or bounded unknowns.
- Not used: listing portals (their terms and methodology are not suitable), scraping, salary or tax data.

## What is calculated vs assumed vs sourced (Alex Lausanne scenario)

| Item | Kind |
|---|---|
| Rent benchmarks, intervals, periods, URLs | **External source** (above) |
| Income 2,600, other costs 1,200, charges 150, moving fee 500, annual obligation 900, opening cash 4,000, floor 500, stress set, repair cap | **Fictional / ASSUMED** (team-authored) |
| Rent bounds 600–1,500, deposit bounds 1,300–1,700 (CHF 10 grid) | **ASSUMED** search bounds |
| Matching rules (size, geography, tenancy, unit, charges, ≤ 3 years old) | **Team-defined** comparability rules |
| Threshold "passes up to CHF 950", worst cash at each benchmark, branch status, repairs | **Computed** by `simulate()` and the stress search |

## Market Reality Check (deterministic)

`brief(session).marketChecks` has one `MarketRealityCheck` per input that has a `DecisionInput.market` query:

- `benchmarks`: relevant estimates, best first. Hard mismatches are never offered: a different category, unit, charges basis,
  size or area. Soft caveats are listed in `issues`: broader area, other tenancy duration, stale, no interval.
- `status`: `NO_MARKET_DATA`, `VALUE_NOT_STATED`, `NOT_DIRECTLY_COMPARABLE`, or `BELOW/WITHIN/ABOVE_AVERAGE_INTERVAL`.
  The last three appear only against an estimate with **no** caveats.
- `sensitivity`: an `UnknownImpact` computed over the user's own range, or the declared bounds. `passesUpTo` is the threshold.
- `benchmarkOutcomes`: the engine run at each benchmark's point value and at both interval bounds.
- `needsExactValue`: true when the user's own information still allows both a pass and a fail.

`brief()` adds a `MARKET_SENSITIVE_ASSUMPTION` insight (id `market:<input>`) only when the input is
decision-sensitive. That insight is ranked **after** the safety verdict and after the Skeptic question.

## Revisions

Each node's session carries its `marketData` snapshot. `graph.updateMarketData(newSnapshot)` creates a
**new child node** (`GraphChange.MarketDataUpdated`). Older nodes keep their own snapshot and results.
A what-if that used an estimate follows that estimate's id: it is updated if the estimate still exists and is still relevant, and dropped and listed otherwise.

## API for Developer B (all in `ch.ripple.core`; nothing in `composeApp/` changed)

```kotlin
// Session with market data (fictional Alex, Lausanne 1-room, new lease)
val graph = startGraph(SampleData.alexLausanneSession)

// What Ripple calculated + what the market suggests, for one input
fun DecisionGraph.marketCheck(inputId: String, id: String = activeNodeId): MarketRealityCheck?
Briefing.marketChecks: List<MarketRealityCheck>
FollowUpQuestion.marketEstimateIds: List<String>       // ids to offer under "Help me estimate"

// Buttons -> structured updates (all recompute through brief())
"Use my amount"          -> graph.dispatch(TwinCommand.Correct(Correction.AnswerInput("rent", InputState.Exact(amount))), confirmedByUser = true)
"It's only an estimate"  -> Correction.AnswerInput("rent", InputState.Range(low, high))   // whole CHF 10 steps
"Help me estimate"       -> graph.dispatch(TwinCommand.ShowMarket("rent"))  // CommandResult.Market(check)
"Try this market assumption" -> graph.explore(Hypothesis.UseMarketEstimate("rent", estimateId)): GraphUpdate
"Why this estimate?"     -> check.benchmarks[i].estimate (source, observed, retrievedOn, methodologyKeys) + issues
"Correct this value"     -> Correction.AnswerInput(...)  (replaces the market what-if; review = REPLACED_BY_USER_VALUE)
"Doesn't apply to me"    -> graph.correct(Correction.RejectMarketEstimate(estimateId))   // restores the input's own state
New data version         -> graph.updateMarketData(snapshot: MarketSnapshot): GraphUpdate

// Display: three columns
// What you told us:      check.state (InputState) + facts with provenance USER_STATED / USER_ESTIMATE_RANGE / UNKNOWN
// What the market says:  check.benchmarks (AssessedEstimate: estimate + issues), facts with EXTERNAL_SOURCE
// What Ripple calculated: check.sensitivity.passesUpTo, check.benchmarkOutcomes, briefing.scenarios, node.summary
```

New enum entries that need a branch in an exhaustive `when`: `InsightType.MARKET_SENSITIVE_ASSUMPTION`,
`Hypothesis.UseMarketEstimate`, `Correction.RejectMarketEstimate`, `GraphChange.MarketDataUpdated`,
`TwinCommand.ShowMarket`, `CommandResult.Market`.

**Localization keys** (copy belongs to the UI. Core gives keys and typed facts):
`insight.market_check.{no_market_data|value_not_stated|not_directly_comparable|below_average_interval|within_average_interval|above_average_interval}`,
`action.try_market_assumption`, `action.why_this_estimate`, `question.rent`, `option.move_lausanne`,
`node.use_market_estimate`, `node.reject_market_estimate`, `node.market_data_updated`,
`disclosure.market_estimate_is_not_your_value`, `limitation.market.*` (see `MarketKeys`), `market.method.*` (see `MarketData`).
Suggested EN copy for `value_not_stated`: "Your future rent isn't confirmed. Under the modelled conditions,
your plan keeps your CHF {floor} buffer only up to CHF {passes_up_to}. Official Lausanne figure: CHF {benchmark}
(existing tenancies, 2022–2024)."

## Reproducible end-to-end example

Run it with `./gradlew :core:jvmTest --tests "ch.ripple.core.MarketTest.endToEndAlexLausanne"`:

1. `startGraph(alexLausanneSession)`: the rent is Unknown, the plan fails at worst-case inputs (`NO_REPAIR_IN_SCOPE`),
   and the follow-up asks about the rent with `marketEstimateIds = [fso-lausanne-1r-2022-2024, statvd-vd-1r-2024]`.
2. `explore(UseMarketEstimate("rent", "fso-lausanne-1r-2022-2024"))`: the rent becomes CHF 899 (EXTERNAL_SOURCE)
   and the result is recomputed. Status `PASSES`.
3. `marketCheck("rent")`: still `decisionSensitive` and `needsExactValue`. The buffer holds up to a rent of CHF 950,
   which is verified by a full stress test at 950 (pass) and 960 (fail).
4. `correct(AnswerInput("rent", Exact(1,050)))`: the user's own value replaces the what-if. Status `REPAIRABLE`
   (CHF 100/month from month 1). `compareBranches("n1","n2")` lists `market_assumptions` and `status`.

## Which tests use which data

- **Sourced observations** (`MarketData.vaudHousing`): geography and size matching, benchmark ordering, the comparison
  with the published interval, the reality-check facts, and the end-to-end example.
- **Fictional fixtures:** Alex's finances and bounds in `SampleData`, plus every estimate built by `fake(...)` in `MarketTest`.
  These cover per-m² units, Geneva, updated snapshots and stale data. They use `EvidenceKind.ASSUMPTION` and `example.invalid` URLs and are never shipped.
