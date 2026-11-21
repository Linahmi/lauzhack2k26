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
- [ ] Android app **actually launched** on device/emulator; screenshot/recording in `docs/evidence/`.
- [ ] Desktop app **visually checked**; screenshot in `docs/evidence/`.
- [ ] UI labels: amounts are **net** monthly income, **cash only — not net worth**, fictional,
      CHF, 120-month horizon.

## M2 — Decision insight (Developer A: core · Developer B: UI groundwork)

Core, in priority order, each with tests:
1. **Intervention window** — latest month a given monthly change still avoids the shortfall.
2. **Skeptic** — bounded sensitivity: each assumption gets an explicit low/high bound;
   report which bound flips "shortfall / no shortfall". Outcomes are *conditional on the
   bounds*, never probabilities.
3. **Guardian** — shortfall months, deepest point, funding gap; negative cash reported as
   unfunded/infeasible, never as an assumed loan.
4. **Explorer** — compare user-approved candidate scenarios vs. baseline; flag constraint violations.

Acceptance:
- [ ] Public signatures + result types + a shared **fixture JSON** agreed and in ARCHITECTURE.md first.
- [ ] Every analysis traces to the `MonthRecord`s it used (needed for the inspector).
- [ ] Hand-checked tests (e.g. Alexia master's funding gap = CHF 18,600).
- [ ] Desktop: cash-over-time chart, baseline vs. alternative, shortfall highlighted.
- [ ] Results reach both Android and desktop from the same `commonMain` code.

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
