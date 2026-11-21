# Task split (2 developers)

| | Developer A — financial core | Developer B — UX & platforms |
|---|---|---|
| Who | Linahmi | teammate |
| Owns | `core/**` | `composeApp/**`, `androidApp/**` |
| Branch prefix | `core/…` | `ui/…` |
| Tests | `core/src/commonTest` (`./gradlew :core:allTests`) | builds + manual smoke on desktop/Android |

Shared files (change only with a heads-up): `gradle/libs.versions.toml`,
`settings.gradle.kts`, root `build.gradle.kts`, `docs/ARCHITECTURE.md` (API section).

## Working agreement

- A only touches `core/`; B only touches `composeApp/` and `androidApp/`.
- Merge to `main` at least twice a day; `main` must always pass
  `./gradlew :core:allTests :composeApp:compileKotlinJvm :androidApp:assembleDebug`.
- No financial arithmetic in UI code — if the UI needs a number, A adds it to `core`.
- Additive `core` changes are free; renaming/removing a public type needs a heads-up.

## Current milestone: M2 — Break My Plan

Product direction: Break (Guardian) → Question (Skeptic) → Repair (Explorer) → Retest.
Spec: [BREAK_MY_PLAN_NEXT_MILESTONE.md](BREAK_MY_PLAN_NEXT_MILESTONE.md),
[RESEARCH_TO_IMPLEMENTATION.md](RESEARCH_TO_IMPLEMENTATION.md). Frozen public API:
[ARCHITECTURE.md](ARCHITECTURE.md#break-my-plan-m2-frozen-for-the-ui--corebreakmyplankt).

### Next: M2b — Guided insight (see ROADMAP.md)

UX rule: one insight, one question, details only on request. The engine decides what
matters (`Briefing`); the UI only renders it. Contract: `core/.../Insight.kt` + the
"Insight layer" section of ARCHITECTURE.md.

### Developer A — `core`

Done (branch `core/break-my-plan`): Break My Plan engine, asset transfers, Alex demo data,
`Insight.kt` contract, `brief()` / `answer()` / `markSeen()` with `InputState`
(exact / range / unknown), `SampleData.alexSession`, facts with `evidenceRef`, personal
constraints + corrections + hypotheses + decision graph (`startGraph`, `correct`, `explore`,
`select`, `compareBranches`). Next:
1. Month-valued unknown (first paycheck month) next to the CHF one (deposit).
2. Calculation-step facts for Inspect (e.g. threshold formula `C4 = 2000 − deposit`).
3. Speed: cache stress tests per input combination if `brief()` feels slow on Android.

### Developer B — `composeApp` / `androidApp`

1. **Launch proof:** Android ran on emulator `Pixel_10a_B` (M1 screen,
   `docs/evidence/android-m1-emulator.png`); re-capture Android + desktop with the new screen.
2. Guided screen calling `brief(session)` directly (API: ARCHITECTURE.md → "API for
   Developer B"): headline, one question with "I'm not sure", one action. Run `brief()` off
   the UI thread (~10 ms warm, ~200 ms first call, measured on JVM and the Android emulator).
3. Key → text templates (EN) for every `InsightType`, prompt and limitation key; disclosures
   always visible at level 1.
4. Explore level: timeline from `SimulationResult.cashByMonth`, baseline vs. alternative,
   floor line. Inspect level (desktop first): inputs with provenance, witness, search scope.
5. No arithmetic in Composables — only display engine fields.
6. Hold a `DecisionGraph` as UI state (start with `startGraph(SampleData.alexSession)`); every
   answer / "that doesn't work for me" / "what if" goes through `graph.correct(...)` or
   `graph.explore(...)` and the returned `GraphUpdate` (new graph + briefing + `changed`).
   Branch list: `graph.nodes` (labelKey, summary.status, parentId, hypothetical); switching =
   `graph.select(id)` then `graph.briefing()`. API: ARCHITECTURE.md → "Self-correcting twin".
7. Conversation box: `graph.parseCommand(text)` → `graph.dispatch(cmd)`; on `NeedsConfirmation`
   show the interpretation with Confirm / Cancel, then `dispatch(cmd, confirmedByUser = true)`;
   `null` from the parser → show buttons instead. "Show alternatives" → trade-off cards from
   `TradeOff` (objective fields and the user's limits in separate sections).
   API: ARCHITECTURE.md → "Interactive twin loop".

### Integration checkpoint

`brief(SampleData.alexSession)` renders on Android and desktop; answering the question
recomputes and changes the insight; Inspect shows the witness months behind the headline.

## After M2

- **M3** — accessible journey: input provenance + exact/range/unknown inputs (A), text
  onboarding → confirmation → simplified results + desktop inspector (B).
- **M4** — session export/import Android → desktop.
- **M5** — optional: LLM layer (only calls `core`), perspective mixer, French, themes.
- **Both, any time:** IMC booth question on Caritas Vaud + backup nonprofit
  ([CHARITY_VALIDATION.md](CHARITY_VALIDATION.md)).

See [ROADMAP.md](ROADMAP.md).
