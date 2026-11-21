# Architecture

```
ripple/
├── core/          KMP library (jvm + android). Pure Kotlin, no UI, no I/O.
│   └── src/commonMain/kotlin/ch/ripple/core/
│       ├── Chf.kt          exact money (Long Rappen), Swiss formatting
│       ├── Model.kt        immutable inputs + outputs
│       ├── Simulation.kt   simulate(), compare()
│       └── SampleData.kt   fictional Alexia profile + 3 scenarios
├── composeApp/    KMP library (jvm + android): shared Compose UI, desktop main()
├── androidApp/    Android application: MainActivity -> App()
└── docs/
```

Dependency direction: `androidApp -> composeApp -> core`. The UI only uses `core`'s
public API; no financial arithmetic lives in Composables.

Why a separate `androidApp`: AGP 9 no longer supports `com.android.application` in a
Kotlin Multiplatform module, so shared modules use `com.android.kotlin.multiplatform.library`
and the APK is a thin separate module (the current JetBrains-recommended layout).

## Financial model (Milestone 1)

Monthly time step, CHF, zero-indexed months, half-open phase intervals `[start, end)`.

```
closing[m] = opening[m] + income[m] - expenses[m] - oneOffCosts[m]
opening[m+1] = closing[m];  opening[0] = profile.openingCash
```

Rules enforced by `simulate()` (throws `IllegalArgumentException`):
- horizon >= 1; phases non-empty, inside the horizon, no gaps, no overlaps
- income, expenses and one-off costs are non-negative
- one-off costs inside the horizon

Outputs: per-month `MonthRecord` (reconciles opening → flows → closing), `closingCash`,
`minimumCash` (includes the opening balance), `firstNegativeMonth` (zero-indexed, null if
never). Negative cash is kept as an **unfunded shortfall** — never clamped, never treated as
an assumed loan.

Money is `Chf(rappen: Long)` so results are exact and identical on every platform.

Deliberately not modelled yet: taxes, investments, debt, inflation, benefits, net worth.
When transfers/assets/debt arrive, they must follow the UBS rules in
[UBS_REFERENCE_NOTES.md](UBS_REFERENCE_NOTES.md) (transfers are not expenses, principal vs
interest, cash ≠ net worth).

## Public core API (contract between developers)

```kotlin
fun simulate(profile: FinancialProfile, scenario: Scenario): SimulationResult
fun compare(profile: FinancialProfile, baseline: Scenario, alternative: Scenario): ScenarioComparison
```

Planned (Milestone 2, signatures to be agreed before work starts):

```kotlin
fun analyzeRisks(result: SimulationResult, minCashBuffer: Chf = Chf.ZERO): List<Risk>          // Guardian
fun analyzeSensitivity(profile, scenario, assumptions: List<AssumptionShift>): SensitivityReport // Skeptic
fun explore(profile, baseline, candidates: List<Scenario>): List<ScenarioComparison>           // Explorer
```

Change rules: additive changes to `core` are free; renaming/removing a public type needs a
heads-up to the UI developer. The perspective mixer may only reorder/prioritise analyses —
it never alters computed numbers.

## Integration risks

1. Core API churn breaking the UI → agree signatures first, UI can mock with `SampleData`.
2. AGP 9 + KMP is new; if a dependency misbehaves, check plugin compatibility before
   debugging code.
3. LLM layer must only call core functions and narrate their outputs (Milestone 5).
4. Money stays `Chf`; never convert to Double inside the engine.
