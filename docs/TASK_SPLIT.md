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

## Next milestone: M2 — financial intelligence

A makes Guardian / Skeptic / Explorer real in `core`; B builds the screens that will show
them, so both halves meet early.

### Step 0 — together (~30 min, before splitting)

Agree on these public signatures and their result types, write them into
`docs/ARCHITECTURE.md`, and merge that small PR to `main`. This is the contract that keeps
both developers unblocked.

```kotlin
fun analyzeRisks(result: SimulationResult, minCashBuffer: Chf = Chf.ZERO): RiskReport               // Guardian
fun interventionWindow(profile, scenario, monthlySaving: Chf): Int?                                  // Guardian
fun analyzeSensitivity(profile, scenario, shiftPercent: Int = 10): SensitivityReport                 // Skeptic
fun explore(profile, baseline: Scenario, candidates: List<Scenario>): List<ScenarioComparison>       // Explorer
```

### Developer A — `core`

The basic simulator (`simulate`, `compare`) is **done** and mirrors the UBS starter kit —
every team has that. Priority now is what makes Ripple different, in this order:

1. **Intervention window** (our standout): latest month a given monthly saving can start and
   still avoid the shortfall ("act before month X"); null if already too late.
2. **Skeptic** `analyzeSensitivity`: shift income / expenses / one-off costs ±10% one at a
   time; report which assumption flips "shortfall vs. no shortfall" (robust vs.
   assumption-dependent conclusion).
3. **Guardian** `analyzeRisks`: shortfall months, months below a buffer, deepest shortfall,
   funding gap (extra CHF needed to never go negative).
4. **Explorer** `explore`: rank candidate scenarios vs. baseline and drop the ones that break
   a constraint (e.g. cash < 0).
5. **Tests for each**, checked against hand calculations on Alexia's master's scenario
   (e.g. its funding gap must be CHF 18,600).

### Developer B — `composeApp` / `androidApp` (M3–M4 groundwork)

1. Navigation shell + adaptive layout: single column on phone, side-by-side panels on desktop.
2. Scenario picker (baseline vs. alternative) with live recompute via `compare()`.
3. **Desktop chart**: cash over time for both scenarios from `SimulationResult.cashByMonth`,
   negative region highlighted — the core demo visual.
4. **Assumptions panel**: lists every input of the selected scenario (later the Skeptic's editor).
5. Run on a real Android device or emulator and note the result.
6. Plug in Guardian / Skeptic / Explorer cards as A merges each analysis; until then use
   dummy data matching the agreed types.

## After M2

- **M3** — Android onboarding: tell your story → structured profile → confirm.
- **M4** — desktop calculation inspector (per-month `MonthRecord`).
- **M5** — LLM layer (only calls `core`, narrates its outputs) + perspective mixer.

See [ROADMAP.md](ROADMAP.md).
