[README.md](https://github.com/user-attachments/files/35231065/README.md)
# <img width="442" height="182" alt="image" src="https://github.com/user-attachments/assets/d8411f0d-a640-4fe3-ac5c-e3be095fb74e" />
**Don't predict the future. Make it explorable.**

Routes is a cross-platform financial decision explorer. It helps people ask *“What happens if I move out, work fewer hours, or study abroad?”* and investigate how those choices may affect their cash flow, uncertainty, and personal commitments. Rather than issuing an affordability verdict, routes exposes the assumptions, compares possible paths, and lets the person decide what matters.

Built at **LauzHack 2026** for the **UBS AI Financial Twin**, **IMC Tech for Good**, and **JetBrains Kotlin Multiplatform** challenges.

> **Responsible scope:** The included profiles are fictional. Outputs are deterministic, conditional calculations under explicitly modelled assumptions—not predictions, financial advice, or guarantees. A cash cushion is a user-defined comparison point, not permission or prohibition to make a life decision.

## The product

routes approaches a financial decision from three complementary perspectives:

- **Guardian — What could go wrong?** Stress-tests the plan against stated adverse conditions and identifies where a cash constraint is breached.
- **Skeptic — What are we assuming?** Surfaces uncertain inputs and asks which missing information could actually change the conclusion.
- **Explorer — What else is possible?** Compares counterfactual decisions, timing, and feasible adjustments while respecting the user's protected commitments and limits.

These are **deterministic analytical roles**, not three LLM personalities. The user can explore a hypothetical path without silently rewriting confirmed information, compare branches, and inspect why an outcome changed.

### Two platforms, one financial model

| Android | Desktop |
| --- | --- |
| Mobile-oriented exploration, including conversational and touch-based interactions | Larger-screen interactive exploration and visual comparison |
| Device-specific microphone, permissions, and voice transport where enabled | Desktop-specific window, input, and visualization behavior |
| Uses the shared Kotlin financial engine | Uses the same shared Kotlin financial engine |

Both applications are intended as independent ways to investigate financial decisions. **Sharing Kotlin code does not itself mean live sessions synchronize between devices.**

## Architecture at a glance

```text
                ┌─────────────────────────────┐
                │       User decision         │
                │  question / edit / what-if  │
                └──────────────┬──────────────┘
                               │
                ┌──────────────▼──────────────┐
                │  Structured financial input │
                │  + explicit confirmation    │
                └──────────────┬──────────────┘
                               │
             ┌─────────────────▼─────────────────┐
             │         Kotlin KMP core           │
             │  models · exact CHF arithmetic    │
             │  simulation · scenario comparison │
             │  stress tests · constraints       │
             │  decision graph · explanations    │
             └───────────┬───────────┬───────────┘
                         │           │
                ┌────────▼──────┐ ┌──▼──────────────┐
                │ Android app   │ │ Desktop app     │
                │ Voice / touch │ │ Visual explore  │
                └───────────────┘ └─────────────────┘

    Optional AI/research services interpret or contextualize inputs;
    they do not author the numerical results of the financial engine.
```

### Why we made these architectural choices

**1. One deterministic source of financial truth.** Financial arithmetic lives in `core`, not in Compose screens or LLM prompts. `Chf` represents money in integer rappen (`Long`) to avoid floating-point discrepancies. The same model can evaluate a baseline, alternatives, and stresses on supported targets. Negative cash is shown as an unfunded shortfall; it is not silently clamped or converted into an invented loan.

**2. AI interpretation is separated from computation.** Natural-language interfaces may help turn a question into a proposed structured action. Corrections to confirmed inputs go through an explicit confirmation boundary. Financial outcomes, constraints, and comparisons come from Kotlin functions—not from generated numbers. AI capabilities and availability differ by platform and deployment; see the related service documentation.

**3. Shared domain logic, platform-appropriate experience.** Kotlin Multiplatform lets the team share the financial model and its tests while keeping Android microphone/permission behavior and desktop visual interaction platform-specific. We prioritize native-feeling interaction over making both screens identical.

**4. Uncertainty is part of the model.** A value can be exact, a bounded range, or unknown. Guardian tests declared stress cases; Skeptic checks whether changing an uncertain input changes an outcome; Explorer searches a bounded action space. Terms such as *minimal repair* or *latest feasible start* apply **only to that declared search scope**, never to every possible future.

**5. Personal constraints are not optimization targets to erase.** Protected commitments and user-rejected actions remain meaningful when evaluating alternatives. A financially feasible adjustment can still be marked personally unacceptable. Confirmed changes and hypothetical branches remain distinct, with provenance and inspectable history.

### Repository map

| Path | Responsibility |
| --- | --- |
| [`core/`](core/) | Shared financial models, `Chf`, simulation, comparisons, stress tests, constraints, decision graph, explanations, and core tests |
| [`composeApp/`](composeApp/) | Compose Multiplatform application code and desktop entry point |
| [`androidApp/`](androidApp/) | Android application, lifecycle, permissions, and phone-specific functionality |
| [`research-server/`](research-server/) | Separate research/backend service; optional runtime integration depends on configuration |
| [`speech-token-service/`](speech-token-service/) | Development-time Gemini ephemeral-token service, when using the supported Android voice flow |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Detailed architecture, contracts, invariants and modeling scope |
| [`docs/DECISIONS.md`](docs/DECISIONS.md) | Technical/product decision records |
| [`CLAUDE.md`](CLAUDE.md) | Engineering context and guardrails for AI-assisted development |

The Android app, Compose module, and core are distinct Gradle modules. This keeps Android packaging separate from the reusable Kotlin implementation.

## Financial model and trust boundaries

The engine models month-by-month cash evolution:

```text
closing cash = opening cash + income − expenses − one-off costs − restricted transfers
next month's opening cash = this month's closing cash
```

Restricted asset transfers (for example, a refundable deposit) reduce **available cash** without being misclassified as consumption; they can be tracked separately from liquidity. The engine compares cases over an explicitly specified horizon and reports modeled constraints and shortfalls.

Supported reasoning includes baseline-versus-alternative cash flow, declared stress cases, bounded uncertainty, timing and adjustment searches, personal constraints, hypothetical branches, and explanations of changed results. It does **not** constitute a full tax, debt, pension, investment, inflation, or benefits planning model. Those unmodeled domains must not be represented as evaluated.

The AI boundary is deliberately strict: **language can suggest an input; the user confirms it; the Kotlin engine calculates the result.** A live conversational exchange alone is not proof that a particular Gemini response invoked the financial engine.

## Build and run

### Prerequisites

- JDK **17+**; the project specifies a JDK 17 toolchain.
- Android SDK, including platform **36**, for the Android build.
- Android Studio is useful for an emulator or connected physical device, but the Gradle wrapper is included.
- If necessary, set `sdk.dir` in a local, uncommitted `local.properties` file.

### From the repository root

```bash
# Run shared core tests
./gradlew :core:allTests

# Launch the Compose Desktop application
./gradlew :composeApp:run

# Build the Android debug APK
./gradlew :androidApp:assembleDebug

# Install on an authorized connected Android device/emulator
./gradlew :androidApp:installDebug
```

On Windows PowerShell, replace `./gradlew` with `./gradlew.bat`. For Android, launch the installed app from the device, or through Android Studio. A running emulator and an installed APK are separate steps.

### Optional voice and research services

The Android Gemini Live experiment uses a local token-issuing service instead of embedding a long-lived Gemini API key in the APK. Consult [`speech-token-service/README.md`](speech-token-service/README.md) for the exact current setup, model selection, USB port forwarding, and limitations. Never commit API credentials.

See [`docs/RESEARCH_AGENT.md`](docs/RESEARCH_AGENT.md) and [`docs/MARKET_DATA.md`](docs/MARKET_DATA.md) for the research architecture and provenance. Networked AI or research services are not required to explain or test the deterministic financial formulas.

## Testing and reproducibility

The most important invariant is **the same structured financial assumptions must produce the same result through the shared engine**, regardless of whether the user explored them from a phone or a desktop.

The repository contains shared core tests, including independently checked reference-scenario calculations and tests for cash-flow accounting, stress cases, uncertainty, constraints, and decision history. Run the commands above to reproduce results; a passing build is not by itself a physical-device end-to-end test.

We distinguish the following evidence:

- **Core tests:** prove deterministic finance and domain contracts on configured test targets.
- **Desktop launch:** verifies the actual desktop app, not just compilation.
- **Android installation and interaction:** verifies mobile UI, permissions, and device compatibility.
- **Live AI/research:** requires credentials, connectivity, available models and separately configured services.

No production banking data or real client credentials are required for the fictional demonstrations.

## Limitations and responsible use

- routes explores **conditional scenarios**, not probabilistically calibrated forecasts.
- Search results are bounded by their documented horizon, grids, candidate limits and stress cases.
- Tax, pension, debt, investment, inflation and benefit effects may be outside the implemented model.
- A scenario passing a cash-buffer check does not mean the decision is advisable.
- Exact/range/unknown inputs are labeled differently; speculative exploration must not silently overwrite confirmed facts.
- Voice and external research depend on their configured platform services; the deterministic engine is a separate component.
- Shared Kotlin logic does not imply cross-device account or session synchronization.

## Technical and project documentation

- [Detailed architecture and core API](docs/ARCHITECTURE.md)
- [Engineering decision records](docs/DECISIONS.md)
- [Research agent and data provenance](docs/RESEARCH_AGENT.md)
- [UBS reference and financial validation notes](docs/UBS_REFERENCE_NOTES.md)
- [Sponsor criteria](docs/SPONSOR_CRITERIA.md)
- [Team and milestone context](docs/TASK_SPLIT.md)
- [AI coding-agent onboarding](CLAUDE.md)

---

**routes's core principle:** make the consequences of a life decision visible, keep uncertainty honest, and leave the decision with the person.
