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
