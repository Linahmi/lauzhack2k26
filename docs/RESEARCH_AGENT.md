# Reality Check: context-aware research (core + research proxy)

**What the user knows → what credible external evidence suggests → what Ripple calculates.**
Research never writes to a session. A researched figure reaches the financial model only when the
user picks it as a what-if (`Hypothesis.UseResearchedEstimate`), which creates a new graph branch.
The deterministic engine runs without the research service.

## Architecture

```
 App (Android / desktop, composeApp)                       research-server (JVM, holds the API key)
 ───────────────────────────────────                      ────────────────────────────────────────
 core/commonMain (no network)                              POST /v1/research  (ResearchJson)
   planResearch(context, session) ── ResearchRequest ──►    ClaudeProposer: claude-opus-5-5 with
     • COMPUTED priority from Skeptic sensitivity            server-side web_search + web_fetch,
     • HEURISTIC catalogue per decision kind                 restricted to allowlisted official hosts
   runResearch(request, provider, cache)                     → PROPOSED findings (untrusted JSON)
     • live attempt  ◄── ProviderResponse ────────────      SourceFetcher: fetches each cited URL
     • validateFinding() on every proposal                   itself (HTML / PDF / XLSX → text)
     • cached snapshot if nothing verified                   → SourceDocument(full text, sha256)
   Hypothesis.UseResearchedEstimate  (user action)
     → new DecisionGraph node, brief() recomputes
 core/jvmMain: HttpResearchProvider (desktop client)
```

- **Kotlin `core` (commonMain):**
  - planning, the typed evidence model, deterministic validation and conflict detection;
  - the fallback logic, the cached snapshot and the wire format;
  - the confirmation boundary, graph provenance and `brief()` integration.
  Same code and tests on Android and JVM.
- **`research-server/`:** the only place with an API key or web access. It runs the LLM and search, and fetches sources.
- **Default provider: free and local** (`RIPPLE_RESEARCH_LLM=ollama`, used automatically when no Anthropic key is set):
  - **Model:** `qwen2.5:7b` (Apache 2.0) in [Ollama](https://ollama.com).
    - About 4.7 GB on disk, and runs on CPU with 32 GB RAM; the 4 GB GPU accelerates part of it.
    - Strong at French and German sources, and has a JSON output mode.
    - `temperature 0`, fixed `seed`, 16k context.
    - Use `RIPPLE_OLLAMA_MODEL=qwen2.5:3b` if 7B is too slow.
  - **Search:** opendata.swiss's public CKAN API (`package_search`, no key), plus a short list of known official documents per
    category (`LocalProposer.SEED_DOCUMENTS`). Only allowlisted XLSX/PDF/CSV downloads are used.
  - **How it reads documents:** the model gets ONE fetched document at a time, trimmed to the titles, footnotes and lines that name
    the requested area. It proposes findings. **It cannot choose a URL:** every finding is pinned to the document it was given.
- **Optional hosted provider** (`RIPPLE_RESEARCH_LLM=anthropic`):
  - Anthropic Claude (`claude-opus-5-5`) through the official Java SDK `com.anthropic:anthropic-java:3.3.0`.
  - Anthropic's server-side `web_search_20260209` and `web_fetch_20260209` tools for search and retrieval. They are restricted with `allowed_domains` to the allowlist in `ResearchCatalog.sourceHosts`.
  - Effort is `high`. Server-side refusal fallback is enabled (`fallbacks: "default"`, beta `server-side-fallback-2026-07-01`).
  - PDFBox 3.0.3 extracts text from fetched PDFs.
  - One provider covers both the LLM and search, so there is one key and no custom crawler.

## Verification: an LLM citation is never enough

`validateFinding` (deterministic) requires **every** check to pass before a finding becomes a `MarketEstimate`:

| Check | Rule |
|---|---|
| `REQUEST_MATCHES` | the finding's request id and category match the request (the server forces the id) |
| `SOURCE_FIELDS_PRESENT` | URL, organization, quote, period end and publication date are all present |
| `AUTHORITATIVE_HOST` | `https` URL on the allowlist: official statistics, then public institutions, then official service providers |
| `DOCUMENT_RETRIEVED` | the server fetched that URL itself (or the cache holds it) |
| `QUOTE_IN_DOCUMENT` | the 8–500 character quote occurs in the retrieved text (whitespace, case and apostrophes normalized) |
| `NUMBER_PARSES` / `VALUE_IN_QUOTE` / `INTERVAL_IN_QUOTE` | Swiss formats (`1'432`, `1’432`, `1 432`, `.–`). The value and any ± or bounds must appear in the quote. An inconsistent interval is rejected |
| `TABLE_COLUMN_MATCHES` | spreadsheet rows only: the value must sit under a column header that matches the claim (for housing, the stated number of rooms, or "Total" for all sizes), never under a ± / interval header. A stated ± must be in the next column. The estimate's `locator` is then built from the document (row, column, header), not taken from the extractor |
| `CURRENCY_CHF`, `UNIT_RECOGNIZED` | CHF only. Unit is one of per month, per m² per month, per semester, per year or one-off |
| `BASIS_DEFINED` | housing must say net vs. charges. Categories that need it (salary) must say gross vs. net. A field claiming both is rejected |
| `HOUSING_DEFINED` | tenancy type is stated (all tenancies vs. ≤ 1 year) |
| `GEOGRAPHY_RECOGNIZED` | the area must be in a small gazetteer (Lausanne, Vaud, Switzerland). Anything else is never guessed |
| `PERIOD_VALID` | start ≤ end ≤ the decision year, and published no earlier than the period ends |
| `COMPARABLE_TO_REQUEST` | `assess()` reports no hard mismatch: category, unit, charges basis, gross/net, size or geography |

- Soft caveats do not block a figure but are listed with it: stale (> 3 years), broader area, other tenancy type, no interval.
- **Conflicts:** two verified figures with the same definition, overlapping periods and non-overlapping intervals are listed as a `ResearchConflict`.
- **Untrusted content:** pages are data.
  - The system prompt tells Claude to ignore instructions inside content.
  - Nothing an extractor returns can execute an action or touch a session.
  - XML parsing is hardened against XXE, and downloads are capped in size.
  - The final URL after redirects must still be on the allowlist.

## Fallback (offline / demo)

`ResearchCache.snapshot` (`research-cache-2026-11-21`) holds **real passages retrieved on 2026-11-21**. It is always labelled
`RetrievalMode.CACHED_RESEARCH`, and `brief()` adds `disclosure.cached_research_not_live_search` when it is used.

| Source | URL | SHA-256 of the file | Passage used |
|---|---|---|---|
| FSO table T 09.03.03.42 (2022–2024 pooled), published 2026-03-26 | `https://dam-api.bfs.admin.ch/hub/api/dam/assets/36506661/master` | `9e941be0…07663ae` | Lausanne row: 1 room **899 ± 16** (net, excluding charges and heating) |
| Statistique Vaud, Numerus 8-2026 | `https://www.vd.ch/…/Numerus-08-2026_Loyers.pdf` | `e0b8b77f…a8e1f` | [T1] 1 room 2024 **896 ± 15**. 3 rooms occupied ≤ 1 year **1673**, kept on purpose and **rejected** for a 1-room query as `DIFFERENT_SIZE` |

- **Re-verified live on 2026-11-21:** `RIPPLE_LIVE_TESTS=1 ./gradlew :research-server:test --tests "*LiveResearchTest"` downloaded both files.
  - The SHA-256 hashes match the snapshot.
  - Every cached quote is found in the full text extracted by the server: XLSX through the server extractor, PDF through PDFBox.
- **Failure handling:** network, timeout, missing key, rate limit, refusal, malformed reply or "no verified finding" leads to the cache.
  If the cache has nothing for that category, the result is `UnresolvedReason.NO_VERIFIED_FINDINGS`. No figure is ever substituted.

## Supported research contexts

`ResearchCatalog.targets` is a team-authored heuristic, used when sensitivity cannot be computed:

| Decision | Research targets (category, unit, basis) |
|---|---|
| `MOVE_OUT` | net rent / month, deposit (one-off), utilities / month |
| `STUDY_VS_WORK` | tuition / semester, entry salary / year **gross** |
| `RELOCATION` | net rent, health-insurance premium, commute cost (per month) |
| `JOB_CHANGE` | salary / year gross, commute cost / month |
| `START_FAMILY` | childcare fee, family allowance (per month) |

**Prioritization:**
1. Market-linked inputs the user has not confirmed and whose value the engine shows **can change the
   verdict** (`COMPUTED_SENSITIVITY`, sorted by swing).
2. Catalogue targets that no input covers (`HEURISTIC`, `decisionChanging = null`, context only, cannot be applied).

Confirmed inputs are skipped (`USER_CONFIRMED`), as are inputs shown not to matter (`NOT_DECISION_CHANGING`). At most 3 requests are made by default.

**Implemented end to end:** moving out (rent). **Planning only:** the other kinds. Nothing beyond rent is modelled in the simulation,
and nothing is cached for them, so research for them only counts if live research verifies a figure.

## Configuration and internet access

| Component | Needs internet | Secrets |
|---|---|---|
| Financial engine, `brief()`, graph, cached research | **no** | none |
| `research-server` (local model) | yes: `ckan.opendata.swiss` and the allowlisted source hosts. The model itself runs offline in Ollama | none |
| `research-server` (hosted, optional) | yes: `api.anthropic.com` and the allowlisted source hosts | `ANTHROPIC_API_KEY` in the server's environment only |
| App → server | local network to the server | optional `RIPPLE_RESEARCH_TOKEN` (shared secret, not an LLM key) |

Run it like this:

```
ollama pull qwen2.5:7b                                    # once (free, local; Ollama must be running)
./gradlew :research-server:run                            # http://127.0.0.1:8787  (GET /health)
# hosted alternative: RIPPLE_RESEARCH_LLM=anthropic ANTHROPIC_API_KEY=… ./gradlew :research-server:run
```

The local setup needs no key at all. Internet is used only for opendata.swiss and the allowlisted source downloads; the model runs on this machine.

Optional settings: `RIPPLE_RESEARCH_LLM` (`ollama` | `anthropic`), `RIPPLE_OLLAMA_URL` (http://127.0.0.1:11434), `RIPPLE_OLLAMA_MODEL` (qwen2.5:7b), `RIPPLE_RESEARCH_BIND` (default 127.0.0.1; a token is required for any other address), `RIPPLE_RESEARCH_PORT` (8787),
`RIPPLE_RESEARCH_TOKEN`, `RIPPLE_RESEARCH_MAX_PER_HOUR` (20), `RIPPLE_RESEARCH_MODEL` (claude-opus-5-5).

**Cost controls:**
- At most 20 runs per hour and one run at a time.
- At most 3 searches and 4 fetches per run, up to 16k output tokens, a 150 s timeout and 1 retry.
- At most 5 documents per run, 8 MB each.

**What is sent:** a request carries only category, unit, area, dwelling type and year. It never includes amounts, input values or names (checked by a test).

Without a key, the server answers `NOT_CONFIGURED` and apps fall back to the cache.

## API for Developer B (`ch.ripple.core`)

```kotlin
val session = SampleData.alexResearchSession                    // Alex, rent unknown, no market data loaded
val plan: ResearchPlan = planResearch(SampleData.alexContext, session)   // requests, skipped (with reasons)
val request: ResearchRequest = plan.requests.first()            // id, query, inputId, priorityBasis, decisionChanging, whyFacts

// Off the UI thread. Desktop: HttpResearchProvider(); Android: copy core/jvmMain/HttpResearchProvider.kt, or pass null.
val outcome: ResearchOutcome = runResearch(request, provider = HttpResearchProvider(), cache = ResearchCache.snapshot)
outcome.mode            // LIVE / CACHED / null (unresolved)
outcome.verified        // List<FindingAssessment>: estimate (source, quote, locator, checks), issues (caveats)
outcome.attempts        // every attempt, including rejected findings and their failed CheckIds
outcome.conflicts; outcome.unresolved; outcome.cacheVersion

// "Use this figure" (user confirms) -> new hypothetical branch, recomputed
val update: GraphUpdate = graph.explore(Hypothesis.UseResearchedEstimate(request.inputId!!, outcome.verified[0].estimate!!))
update.researchChange()  // ResearchChange: statusBefore/After, worstMinimumCashBefore/After, changed fields

// "Use my amount" / "Not for me": unchanged from the market API
graph.correct(Correction.AnswerInput("rent", InputState.Exact(amount)))   // replaces the researched what-if
graph.correct(Correction.RejectMarketEstimate(estimateId))                // removes it, input back to the user's state
```

Display it in three groups:
1. **What you told us:** `InputState` and facts with `USER_*` or `UNKNOWN` provenance.
2. **What evidence suggests:** `outcome.verified[i].estimate` (`source.organization`, `source.url`, `observed`, `retrievedOn`, `quote`, `retrieval`) and `issues`.
3. **What Ripple calculated:** `request.whyFacts` (e.g. `passes_up_to`), `update.after`, `briefing.marketChecks`.

`retrieval == CACHED_RESEARCH` must be shown as "cached research from {retrievedOn}". It is not a live search.

**New items:** keys `node.use_researched_estimate` and `disclosure.cached_research_not_live_search`; `InsightType` and the enum types above;
`Hypothesis.UseResearchedEstimate`. Any exhaustive `when` needs a branch for each.

## Reproducible example

Run `./gradlew :core:jvmTest --tests "ch.ripple.core.ResearchTest.endToEndDecisionToUpdatedGraph"`:

1. **Decision:** Alex is moving out (Lausanne, 1 room, new lease). The rent is unknown and no market data is loaded.
2. **Important unknown:** `planResearch` puts `rent` first, as `COMPUTED_SENSITIVITY`. The engine shows the buffer holds only up to CHF 950.
3. **Research:** the live provider returns `NOT_CONFIGURED`, so the cache is used. The FSO Lausanne figure CHF 899 ± 16 passes every check.
   The 3-room new-lease figure is rejected as `DIFFERENT_SIZE`.
4. **User confirmation:** `explore(UseResearchedEstimate("rent", estimate))` creates node `n1`. The rent fact carries `EXTERNAL_SOURCE`
   and `market:<id>`, and the disclosure says cached research.
5. **Recompute:** the status goes from `NO_REPAIR_IN_SCOPE` to `PASSES`. `marketCheck("rent").needsExactValue` stays `true`,
   because the figure describes existing tenancies, not Alex's lease.
6. **Updated graph:** Alex enters CHF 1,050, creating `n2` with status `REPAIRABLE`. `n1` still stores the estimate, with its URL, quote and checks.

The same flow through real HTTP (stand-in LLM, real server and client) is `ResearchServerTest.httpRoundTripThroughTheAppClientVerifiesLive`.

## Tests

| Kind | Where | Data |
|---|---|---|
| Deterministic (always run) | `core/.../ResearchTest.kt` (16, JVM + Android host) | genuine cached passages, plus fictional fixtures (`bfs.admin.ch/test-fixture.html` text and `example.com`) for the failure paths |
| Deterministic server | `research-server/.../ResearchServerTest.kt` (7) | stand-in LLM, local HTTP, generated XLSX/HTML |
| Live (opt-in `RIPPLE_LIVE_TESTS=1`) | `LiveResearchTest` | real downloads. The real Claude call also needs `ANTHROPIC_API_KEY` |

## Limitations and open items

- **Live local run (2026-11-21, Ollama 0.40.16, `qwen2.5:7b`, this laptop: i7-1360P, 32 GB, RTX A500 4 GB):**
  - **The pipeline works end to end:** opendata.swiss found 15 official FSO tables, 3 documents were fetched and read, and it took **487 s**.
  - **No figure was verified.** Both proposals were wrong and **both were rejected**:
    - **CHF 1,369 "Lausanne, 1 room":** actually the *all-sizes total* column of the FSO row (1 room is 899). It was rejected because
      the quote was not copied exactly, did not parse and had no tenancy, **not** because the column was wrong.
    - **CHF 1,465 Vaud:** the all-sizes average, with no net/gross basis or tenancy stated. Rejected (`BASIS_DEFINED`, `HOUSING_DEFINED`).
  - **Conclusion:** the local 7B model is too slow and too inaccurate at reading tables for the demo. Use the cached research, or Claude.
    The column misread led to `TABLE_COLUMN_MATCHES`: that exact misread is now a test case and is rejected even with a perfectly copied row.
- **Not yet run against Claude:** no API key was available while building, so the live Claude call (`claudeResearchEndToEnd`) has
  never run. Everything up to it is tested: the server, real HTTP, and real fetching and validation of live documents.
  `ClaudeProposer` compiles against the SDK, but its prompt and replies have not been seen in practice.
- **Table cells:**
  - **Spreadsheets:** columns are checked. Header rows are taken to be the rows above the value whose first cell is empty, which fits
    the FSO and cantonal layout; other publishers' merged headers may need real merged-cell parsing.
  - **PDF tables (no columns in the text):** still unchecked. In "1 pièce 716 13 896 15" a model could report the 2015 value (716)
    as 2024, and validation would not catch it.
- **Rights:** most allowlisted sources have unassessed terms (`CITATION_ONLY_TERMS_UNVERIFIED`). Only short quotes are stored.
- **Other categories:** deposit practices, utilities, tuition and salaries are research targets only. There are no models or cached figures for them.
- **Android:** live research on Android needs the transport copied to `androidMain` (Developer B), or the cache alone.
