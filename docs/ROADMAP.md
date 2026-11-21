# Roadmap

Principle: one reliable end-to-end experience beats ten disconnected features.
Order follows [CLAUDE.md](../CLAUDE.md) "Implementation order & cut line". Every new feature
first passes the challenge gate (CLAUDE.md) with a note in its PR or `docs/DECISIONS.md`.
Sponsor matrix: [SPONSOR_CRITERIA.md](SPONSOR_CRITERIA.md). Charity gate:
[CHARITY_VALIDATION.md](CHARITY_VALIDATION.md).

## M1 — Proof · engine done, launch proof open

Done: KMP project (Android + JVM desktop), deterministic cash engine in `commonMain`,
`simulate`/`compare`, 14 tests (JVM + Android host) incl. verified UBS golden values,
minimal shared UI using real engine output.

Open acceptance criteria (JetBrains is mandatory, UBS gate):
- [x] Android app **actually launched** on emulator `Pixel_10a_B` (Android 37.1), 2026-11-21: `docs/evidence/android-m1-emulator.png`, no crash in logcat.
- [ ] Desktop app **visually checked**; screenshot in `docs/evidence/`.
- [ ] UI labels: amounts are **net** monthly income, **cash only — not net worth**, fictional,
      CHF, 120-month horizon.

## M2 — Break My Plan (decision insight)

Break (Guardian: exhaustive stress search → counterexample) → Question (Skeptic: which
bounded unknown flips the verdict) → Repair (Explorer: smallest user-approved monthly cut on
a CHF 1 grid, and latest start) → Retest. Spec: BREAK_MY_PLAN_NEXT_MILESTONE.md.

Acceptance:
- [x] Public API frozen in ARCHITECTURE.md; shared fixture JSON in `core/src/commonTest/resources`.
- [x] Engine in `commonMain`; every case is a full reconciled `SimulationResult` (traceable).
- [x] All fixture values reproduced by the engine and by an independent brute force.
- [x] Deposit modelled as restricted asset transfer; net worth unaffected by deposit size.
- [ ] Minimal Break My Plan screen on Android and desktop using real outputs (Developer B).
- [x] Android runtime proof (M1 screen, emulator, 2026-11-21). Re-capture once the Break My Plan screen exists.
- [ ] Explanation model (formula + input IDs per result) for the inspector (Developer A).

## M2b — Guided insight (NEXT milestone)

UX principle: the user sees one useful insight; the engine knows exactly why it matters.
Progressive disclosure: Insight → Explore → Inspect, never hiding uncertainty or the fact
that results are hypothetical. Contract types are in `core/.../Insight.kt`.

Essential:
- [x] (A) `InputState` (known / range / unknown + provenance) and `DecisionSession`.
- [ ] (A) Month-valued unknown, so "When will your first paycheck arrive?" can be modelled
      (`UnknownInput` is CHF-only today).
- [x] (A) `brief()` with the ranking rules in ARCHITECTURE.md; tests for each rule, incl.
      "disclosures always present" and "seen insights move down".
- [ ] (B) Android guided flow: "What's on your mind?" → example housing story → one insight
      → one question (with "I'm not sure") → recompute → "Explore another option".
- [ ] (B) Desktop: same flow + simple comparison chart + expandable Inspect panel.
- [ ] (B) Localized templates for every `headlineKey` / `promptKey` / limitation key (EN first).

Deferred: calendar month names ("April" needs a start date input), free-text extraction
(use example stories + structured answers first), LLM wording, perspective mixer, voice.

## M2c — Self-correcting twin (core done)

- [x] (A) Typed personal constraints (max monthly cut, cash buffer, rejected actions + reasons)
      that change the real repair search; financially feasible vs. personally acceptable.
- [x] (A) Corrections vs. what-if hypotheses; what-ifs never overwrite confirmed inputs.
- [x] (A) Immutable decision graph: branch, select/revert, compare, per-node summary + diff.
- [ ] (B) "That doesn't work for me" button on the offered repair → `RejectRepair`; show the
      `changed` list and the new briefing; simple branch list (label, status, parent).
- [ ] (B) Desktop: compare two branches (`compareBranches`) with the timeline view.
- [x] (A) Interactive loop: cut/timing what-ifs, invalidated offers, `reevaluate`, `tradeOffs`,
      typed `TwinCommand` + deterministic parser + confirmation-gated `dispatch`, demo script.
- [ ] (B) Text box + buttons → `parseCommand` / `dispatch`; confirmation sheet for `NeedsConfirmation`;
      trade-off cards; replay `SampleData.alexDemoScript` as a demo button.

## M3 — Accessible journey (main IMC + UBS-transparency milestone)

- Input provenance on every value: `user-stated`, `assumed`, `unknown`, `computed`,
  `external-source` (core model change — Developer A).
- Exact / range / unknown inputs; unknowns never silently filled; ranges produce bounded results.
- Text-first onboarding → proposed profile → **user confirmation** before any simulation.
- Skeptic asks only the few decision-relevant questions.
- Simplified phone results; desktop inspector tracing each result to inputs and math.
- Native-feeling layouts: phone single column, desktop multi-panel (not a stretched phone).

Acceptance:
- [ ] One synthetic beneficiary story runs end to end: incomplete inputs → key question →
      bounded scenario → counsellor-readable explanation.
- [ ] No number shown in UI that `core` did not compute.

## M4 — Cross-device handoff

- Session export/import (file or short code) Android → desktop. QR/cloud sync only if
  everything above is stable.

## M5 — Delight (optional, cut first)

Perspective mixer (reorders analyses, never changes numbers or hides risks), LLM parsing and
explanations strictly grounded in engine output, French, dark/light, voice, hypothetical
future-self narration (labelled). JetBrains Air tasks only if actually used, logged truthfully
in `docs/AGENT_REVIEW_LOG.md`.

## Continuous — validation and pitch

- Charity gate (CHARITY_VALIDATION.md): IMC booth answer on Caritas Vaud + a backup nonprofit.
- Shared-vs-platform code explanation for JetBrains judges.
- Demo path sized to the **confirmed** organiser time limit (currently TBC), with backup recording.
- Release/demo gate checklist in CLAUDE.md.

## Cut first

Bank integrations, real personal data, payments, trading, unverified legal/benefits rules,
agent swarms, backend/live sync, cosmetic animations. Never cut: UBS financial truthfulness,
IMC beneficiary clarity, JetBrains proof on both platforms.
