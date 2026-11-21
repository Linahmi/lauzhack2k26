# Task split (2 developers)

| | Developer A — financial core | Developer B — UX & platforms |
|---|---|---|
| Owns | `core/**` | `composeApp/**`, `androidApp/**` |
| Branch prefix | `core/…` | `ui/…` |
| Tests | `core/src/commonTest` (`./gradlew :core:allTests`) | builds + manual smoke on desktop/Android |

Shared files (change only with a heads-up): `gradle/libs.versions.toml`,
`settings.gradle.kts`, root `build.gradle.kts`, `docs/ARCHITECTURE.md` (API section).

## Working agreement

1. A proposes new public `core` signatures in `ARCHITECTURE.md` first (small PR).
2. B builds against them using `SampleData` or a stub returning fixed objects in `composeApp`.
3. Merge to `main` at least twice a day; `main` must always pass
   `./gradlew :core:allTests :composeApp:compileKotlinJvm :androidApp:assembleDebug`.
4. No financial arithmetic in UI code — if the UI needs a number, add it to `core`.

## Next tasks

**Developer A (Milestone 2)**
- Guardian: `analyzeRisks` — shortfall months, months below a buffer, deepest shortfall,
  funding gap (CHF needed to stay ≥ buffer), with tests.
- Intervention window: latest month a given monthly saving/expense cut still avoids the
  shortfall; tests at the boundary month.
- Skeptic: one-at-a-time sensitivity on income/expenses/one-off costs (±x%), report which
  assumption flips "has shortfall"; tests.
- Explorer: compare N candidate scenarios against baseline; filter by constraints.

**Developer B (Milestone 3/4 prep)**
- Navigation shell + layout that adapts phone vs desktop width.
- Scenario picker (baseline/alternative from `SampleData`) and live recompute.
- Desktop cash-over-time line chart from `SimulationResult.cashByMonth` (baseline vs
  alternative), shortfall region highlighted.
- Assumptions panel listing every input of the selected scenario.
- Run the app on a real Android device/emulator and record results.
