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

### Insight layer (UX contract — `Insight.kt` types + `Brief.kt` logic, implemented)

Principle: maximum intelligence in the engine, minimum cognitive load in the UI. Guardian /
Skeptic / Explorer are internal analyses, not personalities. The engine turns their
evidence into **one** primary insight and at most **one** question; the UI only renders.

| UX level | Shown | Comes from |
|---|---|---|
| 1 Insight | headline + one action, plus mandatory `disclosureKeys` / `limitationKeys` | `Briefing.primary`, `followUp`, `actions` |
| 2 Explore | simple timeline, baseline vs. alternative, the event causing the difference, one adjustable input | `scenarioIds` → `SimulationResult`, `facts` |
| 3 Inspect | all inputs with `Provenance`, bounds, calculation steps, witness, constraint checks, search scope | `facts.evidenceRef` → `StressTest` / `RepairSearch` / `UnknownImpact` / `MonthRecord` |

#### API for Developer B (the only calls the UI needs)

```kotlin
var session = SampleData.alexSession          // deposit starts Unknown (searched over CHF 1,300–1,700)
var b: Briefing = brief(session)              // pure + deterministic; JVM ~1 s for this session → call off the UI thread
session = session.answer("deposit", InputState.Exact(Chf.francs(1_400)))   // or Range(low, high) / InputState.Unknown ("I'm not sure")
session = session.markSeen(listOf(b.primary.id))
b = brief(session)                            // recompute after every change
```

`Briefing` (Insight.kt):
- `primary: FinancialInsight` — render `headlineKey` with its `facts`; always show `limitationKeys`.
- `followUp: FollowUpQuestion?` — `promptKey`, `answer` = `AnswerSpec.MoneyRange(low, high, step)`,
  `whyFacts`, `allowsUnsure` (always true). Null = nothing worth asking.
- `actions: List<UserAction>` — in display order: `answer:<input>`, `explore:repair` (scenario
  `decision-repaired`), `explore:baseline` (scenario `baseline`), `correct:<input>`, `inspect`.
- `moreInsights` — Explore/Inspect only, already ordered.
- `disclosureKeys` — **always render at level 1.**
- `scenarios: Map<String, SimulationResult>` — `baseline`, `decision-nominal`, `decision-worst`,
  and `decision-repaired` (only when a repair exists). Use `.cashByMonth` / `.months` for charts.
- `evidence: BriefingEvidence` — `worstCaseInputs`, `valueCombinationsChecked`,
  `stressCasesPerCombination`, `worstStressTest` (witness, `firstViolationMonth`, …),
  `passesForAllTestedValues`, `failsForAllTestedValues`, `unknownImpacts`, `repairs` (per start month).

Keys the UI must have text for (EN first):

| Kind | Keys |
|---|---|
| Headlines | `insight.fragile_plan`, `insight.fragile_plan.depends_on_input`, `insight.liquidity_breach`, `insight.liquidity_breach.depends_on_input`, `insight.robust`, `insight.unknown_changes_decision`, `insight.feasible_repair`, `insight.action_deadline`, `insight.no_repair_in_scope` |
| Facts | `worst_min_cash`, `floor`, `cases_checked`, `low_income_months`, `first_violation_month`, `floor_shortfall`, `nominal_min_cash`, `margin`, `input:<id>`, `passes_up_to`, `swing`, `values_checked`, `monthly_reduction`, `start_month`, `repair_cap`, `candidates_checked`, `latest_start_month`, `monthly_reduction_at_latest` |
| Questions | `question.deposit` (the `DecisionInput.questionKey`) |
| Actions | `action.answer_question`, `action.explore_repair`, `action.compare_baseline`, `action.correct_input`, `action.inspect` |
| Disclosures / limitations | the constants in `Keys` (`disclosure.*`, `limitation.*`) |

Months in facts are zero-based → display `index + 1`. `FactValue.Money` is exact `Chf`; format
with `Chf.format()`. Never compute a number in the UI; everything shown is a fact or a scenario.

#### How `brief()` decides (fixed rules, no ML, no LLM)

1. **Verdict over everything uncertain:** every combination of uncertain input values (Unknown
   → declared bounds, Range → the range, Exact → one value; max 5,000 combinations) × every
   stress case. Pass for all → `ROBUST_CONCLUSION`; fail anywhere → `FRAGILE_PLAN` (nominal
   plan passes) or `LIQUIDITY_BREACH` (nominal fails), severity CRITICAL if cash goes negative.
   If some values pass and others fail, the headline uses `.depends_on_input` and the
   limitation `limitation.depends_on_unknown_input` — never a flat "will".
2. **Question:** `rankUnknowns` over uncertain inputs (others held at the worst-case values);
   the first one that flips the verdict and was not asked before becomes `followUp`.
   "I'm not sure" keeps it Unknown and it is not asked again.
3. **Repair:** for each start month, the smallest CHF-grid cut ≤ cap that passes for **every**
   tested input value and stress case (search starts at the worst combination's own minimum,
   which is a valid lower bound). Latest feasible start → `ACTION_DEADLINE`; none →
   `NO_REPAIR_IN_SCOPE`.
4. **Ranking:** tier (verdict → question-worthy unknowns → repair → deadline), then unseen before
   seen, then severity, then id. The verdict is always primary, so "seen" never demotes a
   safety finding.
5. **Disclosures** (fictional, hypothetical, tested cases only, cash only, plus "unknown inputs
   searched within bounds" when relevant) are attached to every `Briefing`, outside ranking.

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
