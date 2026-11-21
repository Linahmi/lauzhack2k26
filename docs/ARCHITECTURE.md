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

Asset transfers (e.g. a refundable deposit) lower cash but are not costs:
`SimulationResult.restrictedAssets` tracks them and `closingNetWorth = cash + restricted`
(no liabilities modelled).

Deliberately not modelled yet: taxes, investments, debt, inflation, benefits.
When transfers/assets/debt arrive, they must follow the UBS rules in
[UBS_REFERENCE_NOTES.md](UBS_REFERENCE_NOTES.md) (transfers are not expenses, principal vs
interest, cash ≠ net worth).

## Public core API (contract between developers)

```kotlin
fun simulate(profile: FinancialProfile, scenario: Scenario): SimulationResult
fun compare(profile: FinancialProfile, baseline: Scenario, alternative: Scenario): ScenarioComparison
```

### Break My Plan (M2, frozen for the UI — `core/.../BreakMyPlan.kt`)

Break (Guardian) → Question (Skeptic) → Repair (Explorer) → Retest. Every function is an
exhaustive search over a declared finite set and reuses `simulate()` for each case.

```kotlin
// Inputs
data class StressSet(maxLowIncomeMonths: Int, lowIncomeReduction: Chf) // fun cases(horizon): List<List<Int>>
data class ExpenseReduction(startMonth: Int, monthly: Chf)            // user-approved action, to horizon end
class UnknownInput(name, low: Chf, high: Chf, step: Chf, scenarioFor: (Chf) -> Scenario)
data class AssetTransfer(month, amount, label)                         // in Scenario.transfers: cash -> restricted asset

// Guardian — Break
fun stressTest(profile, scenario, stress: StressSet, floor: Chf): StressTest
//   StressTest: cases: List<StressWitness>, worst, worstMinimumCash, passes, counterexample?,
//               firstViolationMonth?, floorShortfall
//   StressWitness: lowIncomeMonths: List<Int>, result: SimulationResult (full monthly trace)

// Explorer — Repair + Retest
fun findMinimalMonthlyRepair(profile, scenario, stress, floor, startMonth, cap: Chf, step = CHF 1): RepairSearch
//   RepairSearch: startMonth, cap, step, candidatesChecked, minimalMonthlyReduction?, retest: StressTest?, feasible
fun findLatestFeasibleStart(profile, scenario, stress, floor, cap, step = CHF 1): StartWindow
//   StartWindow: byStartMonth: List<RepairSearch>, latestFeasibleStart?
fun Scenario.withExpenseReduction(horizonMonths, ExpenseReduction): Scenario

// Skeptic — Question
fun analyzeUnknown(profile, unknown: UnknownInput, stress, floor): UnknownImpact
//   UnknownImpact: worstMinimumCashByValue, passingValues, changesDecision, passesUpTo?, swing
fun rankUnknowns(profile, unknowns: List<UnknownInput>, stress, floor): List<UnknownImpact> // flips first, then swing
```

Demo inputs live in `SampleData` (`alex`, `alexMove(deposit)`, `alexStay`, `alexStress`,
`alexFloor`, `alexRepairCap`, `alexDepositUnknown`). The team fixture is
`core/src/commonTest/resources/BREAK_MY_PLAN_FIXTURE.json` (1-based months).

**Scope of every claim:** "passes", "minimal" and "latest" hold only for the enumerated
stress cases, the action's cap and CHF 1 grid, and the 6-month horizon. Not probabilities,
not forecasts, not advice. `rankUnknowns` is a heuristic, not proven-optimal question
selection. Months are zero-based; UIs display `month + 1`.

### Insight layer (UX contract — `core/.../Insight.kt`, types in place, logic next)

Principle: maximum intelligence in the engine, minimum cognitive load in the UI. Guardian /
Skeptic / Explorer are internal analyses, not personalities. The engine turns their
evidence into **one** primary insight and at most **one** question; the UI only renders.

| UX level | Shown | Comes from |
|---|---|---|
| 1 Insight | headline + one action, plus mandatory `disclosureKeys` / `limitationKeys` | `Briefing.primary`, `followUp`, `actions` |
| 2 Explore | simple timeline, baseline vs. alternative, the event causing the difference, one adjustable input | `scenarioIds` → `SimulationResult`, `facts` |
| 3 Inspect | all inputs with `Provenance`, bounds, calculation steps, witness, constraint checks, search scope | `facts.evidenceRef` → `StressTest` / `RepairSearch` / `UnknownImpact` / `MonthRecord` |

Planned API (Developer A, next milestone):

```kotlin
sealed interface InputState { Known(value) ; Range(low, high) ; Unknown(low, high) }  // + Provenance
data class DecisionSession(profile, baseline: Scenario, decision: (inputs) -> Scenario,
                           inputs: Map<String, InputState>, stress, floor, repairCap,
                           seenInsightIds: Set<String>)
fun brief(session: DecisionSession): Briefing                 // pure, deterministic
fun DecisionSession.answer(inputId: String, value: InputState): DecisionSession  // "I'm not sure" = Unknown
```

`brief()` builds candidate insights from `stressTest`, `rankUnknowns`,
`findMinimalMonthlyRepair` and `findLatestFeasibleStart`, then ranks them with fixed,
documented rules (no ML, no LLM):

1. A supported floor breach (`LIQUIDITY_BREACH` / `FRAGILE_PLAN`) is the primary insight —
   unless an unknown can flip it, in which case it stays primary but is worded
   "could" and the unknown becomes the `followUp`.
2. Otherwise `ROBUST_CONCLUSION` (reassuring) is primary.
3. `followUp` = highest-ranked `DECISION_CHANGING_UNKNOWN` (flips first, then swing),
   skipped if already answered or answered "unsure" with no new information.
4. Actions: `FEASIBLE_REPAIR` / alternative scenario first, then `ACTION_DEADLINE`;
   `NO_REPAIR_IN_SCOPE` is stated, never hidden.
5. Tie-breaks: unseen before seen, then severity, then insight id (stable ordering).
6. `disclosureKeys` (fictional, hypothetical, tested cases only, cash only) are attached to
   every `Briefing` and are never subject to ranking.

LLM boundary (later, optional): it may only turn a `Briefing`'s typed facts into sentences;
it never adds numbers or claims. Without it the UI uses localized templates per key.

Change rules: additive changes to `core` are free; renaming/removing a public type needs a
heads-up to the UI developer. The perspective mixer may only reorder/prioritise analyses —
it never alters computed numbers.

## Integration risks

1. Core API churn breaking the UI → agree signatures first, UI can mock with `SampleData`.
2. AGP 9 + KMP is new; if a dependency misbehaves, check plugin compatibility before
   debugging code.
3. LLM layer must only call core functions and narrate their outputs (Milestone 5).
4. Money stays `Chf`; never convert to Double inside the engine.
