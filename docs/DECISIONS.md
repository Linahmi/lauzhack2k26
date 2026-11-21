# Decisions — challenge gate notes

One entry per substantial feature (see the challenge gate in [CLAUDE.md](../CLAUDE.md)).

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
  Cut: delaying the move is not modelled, so it is not offered.

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
