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

### Developer A — `core` (engine done on branch `core/break-my-plan`)

Done: `stressTest`, `findMinimalMonthlyRepair`, `findLatestFeasibleStart`, `analyzeUnknown`,
`rankUnknowns`, asset transfers / restricted assets, Alex demo data, 18 new tests (incl.
fixture JSON test). Next:
1. Input provenance on every value (`user-stated`, `assumed`, `unknown`, `computed`,
   `external-source`) — needed for the M3 inspector and UBS transparency.
2. A small `explain` model per result: the formula and input IDs behind each number
   (e.g. `C4 = 2000 − deposit`), so the UI never rebuilds explanations itself.
3. Generic unknown builders (income level, expense level, one-off amount) so Skeptic works
   beyond the deposit example.
4. Fixture JSON → `Scenario` loader in common code if B needs it for session import (M4).

### Developer B — `composeApp` / `androidApp`

1. **Launch proof (JetBrains mandatory):** run on Android (device or emulator) and desktop;
   screenshots in `docs/evidence/`. The `Pixel_10a` emulator did not boot here on 2026-11-21.
2. Minimal Break My Plan screen using `SampleData.alex*` and the real functions:
   nominal trace vs. stress witness; deposit unknown with its CHF 1,500 threshold; repair
   amount + latest start table; each result expandable to its inputs and monthly records.
3. Desktop: side-by-side timeline (nominal / witness / repaired), floor line at CHF 500.
4. Labels everywhere: fictional, net income, month-end cash, "within the tested cases only".
5. No arithmetic in Composables — only display fields from `StressTest`, `RepairSearch`,
   `StartWindow`, `UnknownImpact`.

## After M2

- **M3** — accessible journey: input provenance + exact/range/unknown inputs (A), text
  onboarding → confirmation → simplified results + desktop inspector (B).
- **M4** — session export/import Android → desktop.
- **M5** — optional: LLM layer (only calls `core`), perspective mixer, French, themes.
- **Both, any time:** IMC booth question on Caritas Vaud + backup nonprofit
  ([CHARITY_VALIDATION.md](CHARITY_VALIDATION.md)).

See [ROADMAP.md](ROADMAP.md).
