# Ripple

**Ripple lets you explore how life's decisions could reshape your financial future—revealing
consequences, uncertainties, and trade-offs without deciding for you.**

It explores possible futures through three perspectives —
**Guardian** (what could go wrong?), **Skeptic** (what are we assuming?) and
**Explorer** (what else is possible?). Built for LauzHack 2026 (UBS AI Financial Twin,
IMC Tech for Good, JetBrains Kotlin Multiplatform).

> Don't predict the future. Make it explorable. All profiles are fictional and every
> output is a conditional result of stated assumptions, not a forecast, verdict, or advice.
> A cash cushion is a personal comparison point—not Ripple's permission to make a decision.

## Status: Milestone 1 (foundation)

- `core` — shared Kotlin financial engine: deterministic monthly cash simulation and
  baseline-vs-alternative comparison, with tests (incl. reproduced UBS starter-kit values).
- `composeApp` — shared Compose Multiplatform UI + desktop entry point.
- `androidApp` — Android APK shell hosting the shared UI.

## Setup

Requirements: JDK 17+ (JDK 17 toolchain is used), Android SDK with platform 36.
Create `local.properties` with `sdk.dir=...` (Android Studio does this automatically).

```
./gradlew :core:allTests              # financial tests on JVM + Android host
./gradlew :composeApp:run             # desktop app
./gradlew :androidApp:assembleDebug   # Android APK
./gradlew :androidApp:installDebug    # install on a connected device/emulator
```

Versions: Gradle 9.5.0, Kotlin 2.3.0, AGP 9.3.0, Compose Multiplatform 1.9.3.

## Docs

- [Architecture](docs/ARCHITECTURE.md)
- [UBS reference notes](docs/UBS_REFERENCE_NOTES.md)
- [Task split](docs/TASK_SPLIT.md)
- [Roadmap](docs/ROADMAP.md)
