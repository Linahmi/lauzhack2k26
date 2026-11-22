# Decisions — challenge gate notes

One entry per substantial feature (see the challenge gate in [CLAUDE.md](../CLAUDE.md)).

## 2026-11-21 — Open-ended “What happens if…?” decisions

- **Problem and beneficiary:** closed life-event labels and yes/no affordability language can
  turn a financial twin into a gatekeeper. People need to see consequences and trade-offs for
  the life they describe, even when it does not fit a predefined category.
- **UBS:** a free-form intention and optional category now survive on every decision branch.
  Reusable, provenance-carrying income/expense changes, one-off costs, restricted assets,
  timing, duration and protected commitments construct any cash-flow-supported scenario.
  `ModelCoverage` names pension, debt, tax or investment gaps instead of overclaiming.
- **IMC:** the engine explains change relative to the person's current path and own cash cushion;
  a status reports tested consequences, not permission or a recommendation.
- **JetBrains:** all new decision representation, calculations, limitations and tests are in
  `core/commonMain`; no desktop or Android UI changed.
- **Demo proof:** study abroad uses six-month income/expense changes plus relocation, deposit and
  continuing family support. Early retirement shows its cash path while explicitly flagging the
  missing pension, tax and investment analysis. Both use `DecisionKind.OTHER`.
- **Feasibility:** additive fields preserve Alex and the existing graph. Two focused tests cover
  construction, provenance, exact baseline deltas, open categories and incomplete coverage.
  Cut: no tax, pension, debt or investment calculators and no natural-language parser.

## 2026-11-21 — Explicit evolving-twin state and protected commitments

- **Problem and beneficiary:** an opaque scenario builder looked like budgeting rather than a
  twin, and Explorer could not distinguish a family obligation from adjustable spending.
- **UBS:** decision branches now identify the life decision and assumptions; confirmed facts
  replace bounded uncertainty without rewriting history; every update exposes calculated
  before/after outcomes, evidence and limitations. Exact CHF accounting is unchanged.
- **IMC:** a user can record recurring support or another essential obligation, and repair
  searches protect it. The UI can explain that the constraint remains active even when new
  information makes an earlier repair unnecessary.
- **JetBrains:** all types, recalculation and explanations live in `core/commonMain`; the same
  focused journey runs in JVM and Android host tests. No platform UI changed.
- **Demo proof:** Alex rejects CHF 50/month, then confirms a CHF 1,450 deposit. Worst tested cash
  changes from CHF 300 to CHF 550, the repair disappears, the rejection and old nodes remain,
  and Move can be compared with Stay or a two-month delay.
- **Feasibility:** additive core contract plus four focused tests. Reused `DecisionSession`,
  `PersonalConstraints`, `Scenario`, `Correction`, `Hypothesis` and `DecisionGraph`; no new
  finance domain, persistence layer or probability model.

## 2026-11-21 — Break My Plan engine (core)

- **Problem and beneficiary:** a person without complete records or a precise budget can't
  tell whether a plan (here: moving) survives a few bad months, which input matters most, or
  what modest change would fix it. A nominal forecast hides the failure.
- **UBS:** baseline (stay) vs. alternative (move); exhaustive stress search with a witness
  trace; every month reconciles; negative cash kept; deposit modelled as a restricted asset
  transfer, not consumption; decision-changing threshold (deposit ≤ CHF 1,500) shown as a
  bound-conditional result, no probabilities.
- **IMC:** supports a counsellor-style workflow for low-information users: one targeted
  question instead of a full questionnaire. Hypothesis only; Caritas Vaud provisional,
  nothing validated (see CHARITY_VALIDATION.md).
- **JetBrains:** all search logic in `core/commonMain`; the same tests run on JVM and Android
  host. Android runtime not yet demonstrated (emulator did not boot on 2026-11-21).
- **Demo proof:** Break → Question → Repair → Retest on Alex's fixture; every number traceable
  to `StressWitness`/`MonthRecord`. Fallback: run `./gradlew :core:allTests` live.
- **Feasibility:** owner Developer A; interface in ARCHITECTURE.md; 18 new tests incl. a
  fixture-driven test; cost: replaces the earlier generic `analyzeRisks`/`analyzeSensitivity`
  plan (now superseded).
- **Prior art (not our invention):** budgeted uncertainty (Bertsimas & Sim 2004), actionable
  recourse (Ustun et al. 2019), active feature acquisition (Li & Oliva 2021), time-aware
  recourse (De Toni et al. 2025). See RESEARCH_TO_IMPLEMENTATION.md.

## 2026-11-21 — Guided insight layer (core contract + UX direction)

- **Problem and beneficiary:** people with limited financial knowledge or incomplete records
  are overwhelmed by dashboards and questionnaires; they need one finding they understand
  and one question that actually matters.
- **UBS:** no weakening of the engine. Every displayed number is an engine fact or a labelled
  input (`Provenance`); full evidence stays reachable in the Inspect level. Ranking is
  deterministic and rule-based, and each insight records its `RankReason`s.
- **IMC:** low-literacy, low-information access: exact / range / "I'm not sure" answers, one
  question at a time. Counsellor workflow remains a hypothesis (CHARITY_VALIDATION.md).
- **JetBrains:** insight selection lives in `commonMain`, so Android and desktop show the same
  primary insight; platforms differ only in how much they reveal on request.
- **Demo proof:** housing story → one insight → one question → recompute → alternative.
  Fallback: Inspect panel + `:core:allTests`.
- **Feasibility:** types only now (no logic); `brief()` is Developer A's next task. Cut: the
  perspective mixer, LLM wording, calendar month names, free-text extraction.
- **Guardrail:** disclosures (fictional, hypothetical, tested cases only, cash only) are
  outside the ranking and attached to every `Briefing`.

## 2026-11-21 — Self-correcting twin with personal constraints and a decision graph

- **Problem and beneficiary:** a plan that is "financially feasible" can still be impossible for
  the person (e.g. they cannot cut CHF 50/month). A twin that ignores this gives useless advice.
- **UBS:** corrections rerun the real analyses (stress, unknowns, repair search); financially
  feasible vs. personally acceptable are reported separately; if nothing acceptable remains the
  engine says so (`NO_ACCEPTABLE_REPAIR` / `NO_REPAIR_IN_SCOPE`) instead of inventing an option.
  What-if branches never overwrite confirmed inputs; provenance distinguishes them.
- **IMC:** the user can say "I can't do that" and keep exploring; reasons stay in their own words.
  No personality scores, no inferred background or obligations. Impact still a hypothesis.
- **JetBrains:** graph, constraints and recomputation all in `commonMain`; the UI only renders.
- **Demo proof:** reject the CHF 50 cut → status changes, repair disappears, "works financially at
  CHF 50 but outside your limits" appears; switch to the "stay" branch and compare.
- **Feasibility:** 11 new tests incl. a mutation check (ignoring constraints breaks 5 tests).
  A later evolving-twin addition now models and compares a two-month delay explicitly.

## 2026-11-21 — Interactive twin loop (trade-offs + safe command layer)

- **Problem and beneficiary:** UBS feedback — "make it explorable". Users must be able to say
  "I can't do that" or "what if I stayed?" and see the twin's actual reasoning change.
- **UBS:** every option gets objective engine results (worst cash, buffer kept, required cut,
  timing) next to the user's own limits; no scores or probabilities. Spending cuts and their
  timing are explorable branches; old offers are shown as invalidated, with the constraint.
- **IMC:** plain phrases instead of forms; the twin confirms its interpretation before changing
  anything; unparseable text falls back to buttons, never to a guess.
- **JetBrains:** parser, dispatch, trade-offs and graph are `commonMain`; UI only renders.
- **Safety:** confirmed-state changes require `confirmedByUser`; a future LLM may only emit a
  `TwinCommand` through the same gate. No LLM dependency added.
- **Feasibility:** 9 new tests incl. a scripted end-to-end demo; mutation checks (dropping the
  confirmation gate or the planned cut) each fail 2 tests.

## 2026-11-21 — Exact fast path for brief() (performance)

- **Why:** measured on the Android emulator (ART), `brief()` with the deposit unknown took
  0.8–3 s warm and up to ~10 s on the first call; too slow for a live demo.
- **What:** stress only lowers income, so month t's worst case over all stress cases is
  nominal[t] − reduction × min(k, t + 1), and a cut r from month s adds r × (t − s + 1). Both are
  exact integer formulas, so brief() screens all input values and computes the minimal cut
  directly, running the full 22-case stressTest only for the worst combination (the witness).
- **Proof:** `FastPathEquivalenceTest` compares against the original brute force on 96
  configurations (buffers, stress budgets 0–3, stay/move, planned cut, personal limit) and on
  the UBS Alexia scenarios; an off-by-one in the formula is caught only by this test.
- **Result (warm / first call):** JVM 97 → 8 ms / 455 → 229 ms; Android emulator ART
  0.8–3.2 s → 12–13 ms / 1.4–9.9 s → 180–193 ms. Same answers, all 132 tests pass.
- **Unchanged:** public `stressTest`, `findMinimalMonthlyRepair`, `findLatestFeasibleStart` keep
  the brute-force algorithm (they are the reference). `RepairOption.candidatesChecked` now
  counts grid values covered (all smaller values proven insufficient), same numbers as before.

## 2026-11-21 — Market-grounded rent benchmarks (core)

- **Problem and beneficiary:** someone moving out often does not yet know their future rent. A
  plausible, sourced reference helps them explore the decision without inventing a number.
- **UBS:** a sourced external assumption is kept separate from user facts and computed results
  (`Provenance.EXTERNAL_SOURCE`, separate what-if branch). The engine checks whether the assumption
  changes the cash-buffer verdict (threshold verified by stress test). Mismatches are claimed only
  against fully comparable figures. Details: MARKET_DATA.md.
- **IMC:** this lowers the barrier for users without a lease or a budget yet ("Help me estimate").
  It is a hypothesis until a nonprofit validates it. No impact is claimed.
- **JetBrains:** all matching, checks and revisions live in `core/commonMain`. The same 17 tests
  pass on JVM and Android host.
- **Demo proof:** Alex Lausanne. Rent unknown → try the FSO figure CHF 899 → passes → "still need the
  real lease: breaks above CHF 950" → enter CHF 1,050 → repairable. Fallback: `MarketTest.endToEndAlexLausanne`.
- **Feasibility:** owner Developer A. No backend, no scraping, a static snapshot. Cut: salary and tax
  estimators, listing data, live updates. Open item: Statistique Vaud reuse terms (FSO figures are OPEN-BY).

## 2026-11-21 — Reality Check research agent (core + research-server)

- **Problem and beneficiary:** people deciding without complete figures (here: future rent)
  can ask Ripple to look up credible public evidence instead of guessing.
- **UBS:** external figures enter only as user-confirmed what-ifs, labelled `EXTERNAL_SOURCE`.
  Every figure must pass deterministic validation against the retrieved source text. The engine
  recomputes, and the graph keeps the evidence per branch. Cached research is disclosed as
  cached. Details: RESEARCH_AGENT.md.
- **IMC:** no bank data, no personal data sent (only category, area and year). It works offline
  from the cache. This is a hypothesis until a nonprofit validates it.
- **JetBrains:** planning, validation, fallback and the wire format are in `core/commonMain`
  (tests on JVM and Android host). The key-holding proxy is a separate JVM module, and the APK
  contains no SDK or key.
- **Demo proof:** Alex: rent unknown → research (cached FSO 899 ± 16, verified) → try it →
  passes → enter the real offer → repairable. Fallback: `ResearchTest.endToEndDecisionToUpdatedGraph`.
- **Feasibility:** owner Developer A. One provider (Anthropic: LLM + web search/fetch). Cut:
  multi-agent orchestration, crawling, more category models. Blocker: an API key is needed for the live Claude run.

## 2026-11-22 — Visual identity: Routes × UBS (implemented on desktop)

- **Problem and beneficiary:** people with little financial knowledge abandon screens that look
  like dashboards or alarms. The desktop opens on one decision, one plain-language finding and one
  action. Detail (months, causes, the sum, other paths) appears only when the person asks for it.
- **UBS:** the look borrows public principles from UBS's web publishing guidelines (near-black
  text, white and Chalk surfaces, red used sparingly with one meaning, underlined links, 4.5:1
  contrast, WCAG 2.1 AA). No UBS logo, typeface, image or banking terminology. Every screen
  carries: "Independent prototype for the UBS AI Financial Twin challenge. Not made or endorsed
  by UBS."
- **IMC:** a tight month is shown in calm bronze, not alarm red. Every text colour was measured
  at 4.5:1 or more. Plain sentences, large type, one emphasis per screen.
- **JetBrains:** desktop implementation in `composeApp/jvmMain` (`RoutesApp.kt`, `RoutesTheme.kt`). The
  journey helpers that select key months, causes and trial previews from engine results live in
  `composeApp/commonMain/RoutesJourney.kt`, so Android can reuse them. Android UI unchanged.
- **Demo proof:** the seven-step journey (open → finding → April → what led here → deposit →
  confirm CHF 1,450 → back, other paths) is drawn with these tokens in the review prototype
  "Routes Visual Identity". Numbers follow the Alex sample: April CHF 300 → CHF 550.
- **Feasibility:** owner Developer B. Replaces the earlier beige and green desktop styling. Cut:
  decorative shadows, gradients, pill shapes, bright yellow.

Tokens and rules:

| Role | Value | Rule |
|---|---|---|
| Ink | `#1C1C1C` | All main text |
| Secondary text | `#5A5D5C` | Never lighter for text (6.7:1) |
| Surface | `#FFFFFF`; Chalk `#F9F9F7`; track `#ECEAE3`; lines `#E0DED6` | No shadows; 1px lines mark edges |
| Action red | `#E60000`, white label | Main action and the Routes mark only |
| Link red | `#DA0000`, underlined | Back, "Check it", "Show the math" |
| Below the cushion | bronze text `#86671D`, bar `#AF8626` | Only for amounts under the cushion (5.3:1) |
| Fine | bar `#1C1C1C` | Neutral, so only problems get colour |
| Routes highlighter | pale gold `#F1E2BC` | Marks the one thing that matters per screen; confirmed facts |
| Type | Source Sans 3 (free licence); headings Light 300, large | Never UBS Headline or Frutiger |
| Shape | radius 3–4px; meter 6px, square ends | |
| Secondary button | white, 1px `#1C1C1C` outline | |

## 2026-11-22 — Generic decisions + interactive desktop canvas

**What:** `core/DecisionSpec.kt` describes any cash-flow life decision as data — a situation (cash, pay, spending, what can't be cut, planned bills, optional lower-pay months) plus `FinancialChange`s (less/more pay, more/less spending, one-off cost, refundable deposit) with start, duration, offsets and `Amount.Known / Estimate / Between`. `toSession()` builds an ordinary `DecisionSession`, so moving, studying abroad, fewer hours, a car… all run through the same `simulate()`, stress search, repairs, corrections and decision graph. No new simulator per decision type.

Engine additions (small, backward compatible): `UnknownImpact.passesFrom` (boundary when more is better, e.g. savings or a later start); `DecisionInput.unit` (month-valued inputs reuse the exhaustive search — documented shortcut: values ride on `Chf` as whole units); `DecisionSession.baselineFor` (the "keep things as they are" path follows corrected pay/spending); `DecisionSession.whereItTurns(id)` (engine-computed boundary for any input).

`interpretDecision(text)` is a deterministic offline pattern matcher: it proposes change *kinds* only, fills an amount only when the person typed it, flags pension/tax/investment/debt as not modelled, and leaves everything else to the person. **No LLM is wired in yet**; an LLM may later produce the same `DecisionDraft`, which must still pass `DecisionSpec.problems()` and user confirmation.

Desktop: start screen ("What happens if…?" + three fictional examples), a short setup form, then the twin with a route canvas (selected route = hardest tested case, one comparison route, cushion, before-line after a confirmed change), route switcher, and levers for every input. Dragging a lever is a debounced what-if recomputed by the engine off the UI thread; "That's the real figure" is a correction (new version); "Keep it as a what-if" is a hypothesis node. Boundaries are drawn only when the engine computed one.

- **Problem/beneficiary:** people with partial figures (no bank link, rough ranges) can describe their own decision instead of only watching Alex.
- **UBS:** scenario + baseline + counterfactuals for any supported decision; hand-derived tests for two new decisions (`DecisionSpecTest`), Alex unchanged.
- **IMC:** ranges/estimates and "can't cut" spending are first-class; still a hypothesis until a nonprofit validates the workflow.
- **JetBrains:** spec, interpreter, boundaries and journey helpers are in `commonMain`; Android can consume `ExampleDecisions` / `DecisionSpec.toSession()` unchanged. Android UI not modified.
- **Demo proof:** `RoutesUiTest` drives Sam, Noor and Alex through the real UI; fallback = the three examples.
- **Cut:** LLM interpretation, persistence of custom plans, editing opening cash in place (it starts a new plan), duration×start combined search beyond 5,000 combinations.

## 2026-11-22 — Progressive desktop workspace, local AI and verified research in the flow

- **One workspace:** question → one finding → a month strip (one mark per month on a stem from the cushion; exact, same scale). Clicking a month opens it in place; clicking again closes it. Causes inside a month are the controls for the figures behind them. "Worth trying" (≤ 2 figures: engine-ranked uncertain inputs, then timing) and "Other routes" appear only after the first month is opened; the full chart and every figure stay one click away.
- **Setup:** one question at a time; answers stay as clickable lines; fictional situations (Sam, Noor) can be reused.
- **Local AI (Ollama `qwen2.5:7b`, already used by research-server):** `OllamaDecisionReader` (core/jvmMain) reads the question in the background (≈ 30–60 s here). `readAiProposal` keeps only structure — title, a place that appears in the question, change kinds the built-in reading lacked — and drops every number. Verified live on 2026-11-22. Offline: the built-in reading is used and the UI says so.
- **Research:** only where the validator can verify (official Swiss statistics; Lausanne / Vaud / Switzerland; rents). Live via research-server when running, else the dated cached snapshot, labelled as such. A researched amount is marked "from <source>, not confirmed" and carries `EXTERNAL_SOURCE` provenance in the scenario. Elsewhere (e.g. Amsterdam) the person is told to enter their own figure.
- **Not done:** AI-phrased explanations (too slow locally to stay responsive, and every sentence is already built from engine values).

## 2026-11-22 — "Find a way": decision-centred discovery

`core/FindAWay.kt` — `findWays(session)` answers "what would need to change for this to keep my cushion?" using only existing engine operations: exhaustive input grids (`whereItTurns`, others held at their hardest tested values), timing inputs, modelled alternatives (`tradeOffs`), and the constraint-respecting spending trim (`offeredRepair`, never touching protected spending or rejected cuts). Generic over any `DecisionSpec`/session; no scenario names. Order: uncertain figures (engine-ranked) → timing → other decision figures → alternative routes → trim → "keep things as they are". `noneWork` reports plainly when no tested option keeps the cushion; results are always "within the N months shown and the ranges given", never long-term affordability. The person's existing pay and spending are not treated as levers.

Desktop: the workspace leads with the decision, one finding and one "Find a way" question with its live experiment; the month strip below shows the consequence (plan before a try as hollow marks; results dim while recomputing so a new value never sits next to an old result). Timing choices say "Plan it for …", figures say "That's the real figure"; both can instead be saved as a what-if. Verified for Alex, Sam, Noor and a custom "buy a car" decision built through setup (`RoutesUiTest`, `FindAWayTest`).

## 2026-11-22 — One active investigation

The desktop workspace is decision → one finding → the route → ONE investigation area, driven by a typed `Focus` (way, tested ways, month, figure, calculation, all figures, versions, monthly chart). Clicking a month, cause, amount, cushion, threshold or route changes that same area; choosing the item already in focus (or Esc) returns to the default "Find a way". Nothing nests: causes and calculation lines link to a figure instead of opening sliders inside them; a "‹ August" back link keeps the trail. Repeated statements were removed (the area answers "does it keep the cushion?" instead of restating the finding); arithmetic, sources and tested-case counts live in the calculation view; the one horizon caveat stays under the route. Engine and Find-a-way search unchanged. The window opens maximized (a fixed 1320×900 dp window overflowed at 150 % scaling).
