# Routes — JetBrains Kotlin Multiplatform submission

Audit date: 2026-11-22, re-verified on commit `2c84d72` (voice-first Explore screen) on `main`. Every claim below was checked against
code, a test run or a real launch on that date. Where something was not verified, it says so.

## 30-second pitch

Routes has **one deterministic financial engine written once in Kotlin `commonMain`**. It turns a
"What if…?" into explicit monthly cash changes, simulates them in integer rappen, stress-tests
ranges and unknowns, and finds where money runs out. The same engine and the same
conversation/session logic run on **Android** and on the **JVM desktop**, and the same 122 shared
financial tests pass on both the JVM and the Android host target. Neither interface computes
money: the phone is a voice/text conversation, the laptop is a wide canvas for inspecting the
math — two native experiences over one Kotlin brain.

## Architecture

```
                ┌──────────────────────── :core  (KMP: jvm + android) ───────────────────────┐
                │ commonMain: Chf (rappen), Simulation, DecisionSpec, DecisionGraph,          │
                │ BreakMyPlan (stress), FindAWay (repairs), TradeOffs, Insight, Brief,         │
                │ Corrections, Market, Research (validation of cited figures), ResearchJson   │
                │ jvmMain:   OllamaDecisionReader, HttpResearchProvider (JVM HTTP clients)     │
                └──────────────────────────────────┬──────────────────────────────────────────┘
                                                   │ api
                ┌──────────────────── :composeApp  (KMP + Compose Multiplatform) ─────────────┐
                │ commonMain: phone UI (PhoneApp, Explore/Routes/Twin screens), RoutesSession  │
                │   (conversation state), Language (deterministic words→commands), SessionCodec│
                │   (handoff/session JSON), RoutesPalette (light/dark), voice interfaces        │
                │ jvmMain:   desktop UI (RoutesApp, RouteCanvas, RoutesStart), LaptopPlans,     │
                │   window/menu (Main.kt)                                                      │
                └─────────────┬───────────────────────────────────────────────┬──────────────┘
                              │                                               │
              ┌───────────── :androidApp ─────────────┐        ┌──── desktop (composeApp jvm) ────┐
              │ MainActivity, FileSessionStore,        │        │ :composeApp:run                    │
              │ PrefsAppearanceStore (SharedPrefs),    │        │ optional: local Ollama, research   │
              │ AndroidSpeechInput, AndroidLive-       │        │ proxy (HttpResearchProvider)       │
              │ Conversation + LivePcmAudio (Gemini)   │        └────────────────────────────────────┘
              └────────────────────────────────────────┘
   Optional local services: :research-server (Kotlin/JVM, uses :core types + validation)
                            speech-token-service (Python, dev-only Gemini ephemeral tokens)
```

## Shared vs platform-specific

| Layer | Shared (`commonMain`) | Android only | Desktop only |
|---|---|---|---|
| Financial engine | All money math, stress search, repairs, research-figure validation (`:core`) | — | — |
| App logic | `RoutesSession`, `Language` parser, `SessionCodec`, `RoutesDecision` | — | `LaptopPlans` (plans on disk), `stepsOf` |
| UI | Phone UI composables + palette (Compose Multiplatform) | Activity host, insets, back handler | `RoutesApp`, `RouteCanvas`, `RoutesStart`, menu bar |
| Storage | `SessionStore` / `AppearanceStore` interfaces | file in app-private dir, SharedPreferences | files under user data dir, Java prefs |
| Voice / AI | `LiveConversation`, `SpeechInput` interfaces; `readAiProposal` keeps structure, drops numbers | Android `SpeechRecognizer`, Gemini Live audio | Local Ollama reader (`core/jvmMain`) |

The desktop reuses `RoutesDecision`, `SessionCodec` and the palette from `composeApp/commonMain`
(that is how a phone decision is opened on the laptop). It uses its own desktop layout, not the
phone screens.

### Shared-code metric (reproducible)

Non-blank, non-comment, non-import Kotlin lines in **app code** (`:core`, `:composeApp`,
`:androidApp`; tests and `:research-server` excluded):

| Source set | Lines |
|---|---|
| core/commonMain | 2855 (includes ~150 lines of fictional fixture data in `SampleData.kt`) |
| composeApp/commonMain | 1931 |
| core/jvmMain | 65 |
| composeApp/jvmMain (desktop) | 1777 |
| androidApp | 863 |

Shared: **4786 / 7491 ≈ 64 %**. 100 % of financial calculation is shared; the platform code is UI,
storage, speech and HTTP. Command used:
`git ls-files <dir> | grep '\.kt$' | xargs cat | grep -vE '^\s*(//|\*|/\*|import |package |$)' | wc -l`.

### No duplicated financial math

Frontend arithmetic was searched (`*12`, `sumOf`, `minOf`, etc. in `composeApp/*Main`,
`androidApp`): the only hits are chart scaling, slider positions and month labels. Every
amount shown comes from a `:core` result. Known small duplication: CHF display formatting exists
in both `phone/Language.kt` and the desktop `RoutesTheme.kt` (formatting only, noted in code).

## Tech stack (actual, from `gradle/libs.versions.toml`)

Kotlin 2.3.0 · Kotlin Multiplatform (jvm + android) · Compose Multiplatform 1.9.3 (Material 3) ·
AGP 9.3.0 (`com.android.kotlin.multiplatform.library` + separate `:androidApp`) · Gradle 9.5.0 ·
kotlinx-serialization-json 1.9.0 (JSON tree API, no plugin) · kotlinx-coroutines-swing ·
OkHttp 4.12 (Android Live voice) · Anthropic Java SDK + PDFBox (`:research-server` only) ·
compileSdk/targetSdk 36, minSdk 26, JVM toolchain 17.

## Verified features

| Feature | Status | Evidence |
|---|---|---|
| Android runs | ✅ | Debug APK installed and launched on emulator (`Pixel_10a`), typed "What if I stop working for 6 months" + "1300 to 1700" produced the engine result (−CHF 5'100 in June, first negative month March; February ends at +CHF 200 = 2'100 + 100 − 2'000): `docs/evidence/jetbrains-audit/android-range-result.png`, `android-months.png` |
| Desktop runs | ✅ | `:composeApp:run` opened the "Routes" window: `docs/evidence/jetbrains-audit/desktop-start.png`; end-to-end desktop flows covered by Compose UI tests (`RoutesUiTest`, `RoutesJourneyTest`) |
| Compose Multiplatform | ✅ | Phone UI in `composeApp/commonMain`, desktop UI in `jvmMain` |
| Light/dark mode | ✅ | One shared `RoutesPalette`; Android follows system / setting, desktop View menu; `AppearanceTest` |
| Phone → laptop handoff | ✅ (copy/paste code, no sync) | `SessionCodec`, `HandoffTest` |
| Kotlin backend | ⚠️ optional | `:research-server` (Kotlin/JVM, shares `:core` wire format + validation); 14 tests pass. App works without it (cached research fallback). Not required for the demo |
| Shared AI logic | ⚠️ partial | The AI *boundary* is shared (`readAiProposal` drops every model number). The AI clients themselves are platform code: Ollama on desktop, Gemini Live on Android. No Koog |
| Localization | ❌ English only | Android speech code can request French, but UI strings and the app call use English |
| JetBrains Air | ❌ not used | No Air evidence. Development used Claude Code (31 of 36 commits carry a Claude co-author trailer). Do not claim the Air bonus |

## Testing evidence (run 2026-11-22, all green)

| Suite | Tests | Result |
|---|---|---|
| `:core:jvmTest` (commonTest + jvmTest) | 125 | 0 failures |
| `:core:testAndroidHostTest` (same commonTest on the Android target) | 122 | 0 failures |
| `:composeApp:jvmTest` (session, handoff, appearance, Explore screen, Compose desktop UI) | 38 | 0 failures |
| `:research-server:test` | 14 | 0 failures (live-network tests off by default) |
| `speech-token-service` (Python unittest) | 8 | OK |
| `:androidApp:assembleDebug`, `:androidApp:assembleRelease`, `:androidApp:lintDebug` | — | success; 0 Kotlin compiler warnings |

Deterministic-math tests that run on both targets: `SimulationTest` reproduces the UBS starter-kit
values (closing cash CHF 152,000 / 144,600 / 97,500; Master's minimum CHF −18,600 in month 6),
plus `FastPathEquivalenceTest`, `BreakMyPlanTest`, `DecisionGraphTest`, `TwinLoopTest`.

## Build and run

```powershell
.\gradlew.bat :core:allTests :composeApp:jvmTest      # shared tests on JVM + Android host
.\gradlew.bat :composeApp:run                         # desktop app
.\gradlew.bat :androidApp:installDebug                # Android (emulator or device running)
```

Optional services (not needed for the core demo):
- Desktop local AI: run Ollama with `qwen2.5:7b`; without it the desktop says it is unavailable.
- Research proxy: `.\gradlew.bat :research-server:run` (listens on 127.0.0.1:8787).
- Android Live voice: `speech-token-service\Start.ps1` (also defaults to port **8787**; see limitations).

## Known limitations

- **Port clash:** the research proxy and the Gemini token service both default to 8787. Don't run
  both at once, or start the token service with `-Port 8788` and set
  `routes.liveTokenUrl=http://10.0.2.2:8788/token` in `local.properties` before building the APK.
- Live voice works only in the debug build with a local token service; release has no token URL.
  Typing and Android's on-device recognizer work without it.
- Fixed in this audit: on Android 12+ `allowBackup="false"` does not block device-to-device
  transfer, so `res/xml/data_extraction_rules.xml` now excludes every domain from cloud backup and
  device transfer. Lint still suggests `fullBackupContent` for API ≤ 30; there `allowBackup="false"`
  already covers both, so it was not added.
- Lint also notes: no launcher icon, a newer OkHttp is available, and the Gradle `archives`
  configuration deprecation (from a plugin, will fail on Gradle 10).
- English only; no JetBrains Air; no live sync between devices (manual code handoff only).
- Desktop window launch was verified; a desktop full-journey screenshot was not captured
  (journeys are covered by Compose UI tests instead).
