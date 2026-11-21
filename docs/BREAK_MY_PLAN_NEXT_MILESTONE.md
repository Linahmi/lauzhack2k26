# Ready-to-paste Claude Code prompt — Implement Break My Plan incrementally

You are working in the **existing Ripple Kotlin Multiplatform repository** (Android + JVM desktop). We are **exactly two CS students**. Read the repository's `CLAUDE.md`, sponsor criteria, current architecture, and the attached `RESEARCH_TO_IMPLEMENTATION.md` and `BREAK_MY_PLAN_FIXTURE.json` before coding. Inspect working code and do not replace working modules unnecessarily.

## Goal

Turn the existing financial twin foundation into a **deterministic, independently testable 'Break My Plan' engine**, which searches for bounded counterexamples, a feasible minimal repair, and a decision-changing unknown. Keep the LLM out of financial calculations. This milestone is primarily shared Kotlin `core` work and an extremely thin UI integration, NOT full voice or agent orchestration.

## Important scientific boundaries

- These are finite stress cases, not probability forecasts.
- The Bertsimas–Sim Gamma idea only inspires our cardinality-constrained stress set; do not claim to implement their exact LP counterpart.
- 'Minimum repair' is only minimum within the explicitly fixed action class, bounds, granularity, and horizon.
- 'Latest intervention' is only a modeled start date, not a promise about real-world action availability.
- Deposit is a restricted asset transfer, not total net-worth loss.
- All calculations must be reproducible in integer CHF cents. Never let the LLM generate or correct the numbers.

## Step 1: inspect and plan

1. Inspect the current core APIs, Compose screens, tests, and Gradle builds.
2. Show a concise plan with proposed public Kotlin types, tests, and file ownership.
3. Identify possible conflicts with existing UBS reference golden tests and ensure they remain passing.
4. Freeze a minimal public interface that Developer B can consume in `composeApp`.

## Step 2: Developer A — core engine

1. Model a fictional 6-month move with CHF 4,000 initial liquid cash, CHF 2,600 normal net income, CHF 1,800 low income, CHF 2,350 monthly post-move expense, CHF 900 obligation in month 4, upfront CHF 500 non-refundable moving fee + refundable deposit CHF 1,300–1,700, and CHF 500 liquidity floor.
2. Enumerate every subset of up to 2 low-income months among the 6 months: exactly 22 cases.
3. Implement pure `simulate`, `findCounterexample`, `findMinimalMonthlyRepair`, `findLatestFeasibleStart`, and decision-changing-input analysis (names may adapt to existing conventions).
4. Only allow a user-approved reduction of non-essential spending at most CHF 100/month, starting in a specified month, lasting through month 6. Compare integer CHF amounts or exact cent granularity consistently.
5. Return structured evidence: scenario parameters, cash by month, minimum cash, violation month, stress witness months, repair candidate, and search scope.
6. Cash must reconcile each month; negative balances must not be silently changed or financed.
7. If representing net worth, maintain refundable deposit as a restricted asset separate from cash rather than treating it as a consumed expense.

## Step 3: tests MUST pass before any advanced UI

Test against `BREAK_MY_PLAN_FIXTURE.json` and the detailed vectors in `RESEARCH_TO_IMPLEMENTATION.md`.

Crucial expected values:
- Nominal cash at F=2200: 2050,2300,2550,1900,2150,2400 CHF.
- Stress witness low months 1 and 2: 1250,700,950,300,550,800 CHF.
- Worst minimum cash across the complete 22-case stress set: CHF 300.
- Upfront total F=1800 -> worst cash CHF 700; F=2000 -> CHF 500; F=2200 -> CHF 300.
- Minimum integer CHF monthly reduction starting month 1: CHF 50; starting month 2: CHF 67; starting month 3: CHF 100; starting month 4 not feasible at cap CHF 100.
- Latest feasible start for cap CHF 100/month: month 3.
- Deposit sensitivity: with fee 500, exact feasible threshold deposit <=CHF 1500 (without repair, within specified stress set).

Add tests for off-by-one month timing, exact floor equality, input validation, changes in the stress budget, zero-income boundary, and deterministic repeatability. Test the complete finite search; don't assert arbitrary 'AI confidence' values.

## Step 4: Developer B — minimal live consumer of core

Create the smallest end-to-end screen using the real core outputs:
- Show nominal trajectory and the actual stress witness.
- Show the one unknown deposit and the constraint-flip threshold.
- Show the modeled repair amount and latest feasible start date.
- Give every result a tappable/expandable explanation linking to exact inputs and calculations.
- Android and JVM desktop must compile and, where environments permit, run. Do not hardcode mathematical results in Composables.
- Avoid UI/layout merge conflicts: Developer A owns `core`, Developer B owns `composeApp`; agree on public interfaces before parallel work.

## Step 5: integration report and stop

Run actual shared core tests, desktop build and Android compilation, and available runtime smoke tests. If an emulator or SDK is absent, report it truthfully. Produce a concise report showing exact tests run, failures fixed, file changes, sample outputs, unresolved risks, and the next independent task split. Commit on a scoped feature branch; do not push to `main` without reviewing existing team workflow.

**Do not implement yet:** voice, Koog, autonomous agents, realtime sync, bank integration, login, benefits eligibility, full social services rules, personalized financial advice, or an elaborate percentage mixer.

The target demo is a 90-second **Break → Question → Repair → Retest** experience grounded in UBS financial correctness, IMC's low-information financial inclusion, and JetBrains' genuinely shared KMP engine.
