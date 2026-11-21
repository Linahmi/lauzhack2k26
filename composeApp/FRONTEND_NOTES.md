# Welcome and voice companion

## Capability checks and verified core conversation (latest milestone)

This section supersedes older placeholder-conversation and speech-validation notes below.
No changes were made to core, shared Gradle configuration, or the staged reference image.

- Android queries on-device and default providers with `checkRecognitionSupport` on API
  33+. It selects a reported installed English/French locale instead of assuming service
  presence means language readiness. API 12/13 errors disable that configuration until
  support is refreshed. Older providers with no support query permit a user-started attempt.
- Pixel_10a/API 37: AiAi has no installed recognition languages; English/French are
  downloadable. Default Google recognition reports en-US installed, French downloadable.
  Provider error 14 can precede its valid support list; the adapter accepts that later list.
  System-provider selection requires explicit disclosure/consent, then a separate Record
  tap. Download has a separate consent dialog; no model/package was downloaded in testing.
- English system capture reached Listening. Host microphone was enabled and a Windows
  synthesized test phrase was played, but no transcript resulted (`ERROR_NO_MATCH`, 7).
  Host speaker-to-emulator-microphone routing is unverified. This is not a successful STT
  test. French recognition and offline TTS voices are absent on this AVD.
- Voice layout uses a Canvas blush ring, stronger waveform, actual recording timer,
  labelled large controls, editable words, short recovery actions and overflow options.
  Idle contains a typing invitation, not a pretend transcript. Reduced-motion remains
  available through the Welcome wordmark's display options.
- `FinancialTwinController` initializes `startGraph(SampleData.alexSession)` off the UI
  thread and renders `graph.briefing()`. It uses the real DecisionSession and InputState.
  A reviewed goal requires a second explicit confirmation of Alex's fictional moving
  demo, before the real Briefing deposit question appears. This is not a retirement model.
- Deposit answers use whole-field, locale-specific CHF input or explicit Unknown. Core
  free-text parsing is intentionally never called: its first-number parser can confuse
  age 70 with deposit CHF 1,500. Ambiguous sentences open an empty structured amount
  field. Typed `Correct(AnswerInput(...))` is dispatched without confirmation for review,
  then dispatched with `confirmedByUser=true` only after Confirm amount. The returned
  GraphUpdate supplies the recomputed Briefing. No frontend financial calculations.
- All financial sentences use Briefing facts. Bounds, disclosure and limitation keys are
  displayed and included in read-aloud. Changing a deposit retains the graph's correction
  history. Activity recreation restores only a previously confirmed typed answer; drafts
  and pending proposals are never replayed into core. System speech consent is not saved.
- Automated verification: 128 core test executions, five frontend adapter JVM tests,
  JVM compilation, Android assembly/install and Android lint pass (0 errors, 3 existing
  SDK/icon warnings). A Kotlin incremental-cache issue after moving test sources was
  resolved by rerunning test compilation; the five tests were discovered and executed.
- Emulator exercised: all three screens and Back, permission denial/retry/grant, system
  provider consent, French support discovery, download dialog cancellation, Listening/
  silence recovery, typed goal review, intent confirmation, ambiguous numeric rejection,
  deposit review/confirmation and real CHF 500 minimum result for CHF 1,500 deposit.
  Rotation restores the confirmed result after recalculation. Long content scrolls while
  Cancel, Record/Stop and Confirm stay pinned and reachable. New questions reset scrolling.
  English TTS onStart, onDone and interruption callbacks were observed; human audibility is not
  verifiable here. Final screenshots live in androidApp/build/emulator-testing/screenshots.
- Challenge gate: confirmed structured input addresses financial-information barriers for
  older users without silently converting speech into money. UBS proof is real bounded
  simulation plus visible assumptions. This is a potential access route for nonprofit
  beneficiaries; beneficiary benefit/charity eligibility remain unvalidated. Shared state,
  input checks and rendering run in KMP; only native speech is Android-specific. The live
  demo falls back to text. Owner is frontend; no graph screen/LLM was added. Core remains
  immutable, financial computations stay in core, and new tests cover the safety boundary.

## Current visual onboarding (Swiss Companion A reference correction)

This section supersedes the earlier welcome navigation descriptions below. The root
`img.png` is the visual reference and was left untouched, including its existing staging.

- Flow: Welcome (`Get started`) -> Connect to UBS (`Connect`) -> Voice input. Both
  Android system Back and labelled onscreen Back follow the reverse path. The first CTA
  never opens financial results. Existing core, financial screen and native speech adapters
  are unchanged by this visual milestone; no dashboard/graph or bank login was added.
- Welcome: centered crest/wordmark, reference headline and paragraph, original layered
  Alpine vector landscape with house and gently moving Swiss flag, and one bottom CTA
  over the hills. No branching chart, extra CTA links or desktop hero. Tapping the wordmark
  opens the preserved reduced-motion preference without adding visible page clutter.
- Connect: centered typographic UBS name with an original key motif, serif heading,
  three rounded benefit rows, bottom Connect button and concise prototype disclosure.
  The artwork is not the official UBS logo. The unsupported "Secured by UBS" statement
  is intentionally replaced by a real-demo disclosure; no affiliation/security endorsement
  is claimed and no credentials are requested.
- Voice: reference heading, large rose/red voice ring, actual recording timer starting at
  zero, editable inline transcript, and three large circular controls. Idle example text
  is explicitly labelled and never inserted into a transcript or treated as recognized
  speech. TTS/language options and detailed service errors live behind the ellipsis.
- Shared state is retained per destination with Compose's saved-state holder. Recording
  still stops on disposal/Back; SpeechRecognizer/TTS ownership and cleanup are unchanged.
- Challenge gate: this is a frontend readability/navigation correction for older adults,
  with a low-clutter entry to the existing financial proof. Common Compose code is shared;
  no new financial conclusions or charity-impact claims are introduced. Integration risk
  is layout/state regressions, checked through actual Android navigation, typed input and
  confirmation. Speculative financial/graph work remains cut from scope.
- Final validation: required core tests, JVM compilation, Android assembly, lint and install
  passed. Lint has zero errors and three existing configuration warnings. No desktop app
  was launched for this visual task. Pixel_10a (Android 17 / API 37) was reused.
- Actual Android checks: all three pages opened, Get started/Connect clicked, reverse Back
  navigation verified, inline text entered/edited and confirmed, and final screenshots
  visually inspected against `img.png`. Speech audio limitations from the prior milestone
  still apply; no successful microphone transcription is claimed by a visual test.
- Final screenshots in `androidApp/build/emulator-testing/screenshots/`:
  `swiss-final-01-welcome.png`, `swiss-final-02-connect.png`, `swiss-final-03-voice.png`.
  Original vector drawing details, platform serif rendering, native system bars and device
  aspect ratio differ from the reference. Accessible action labels are retained.

Scope: Developer B owns the shared interface in `composeApp` and Android hosting/speech
in `androidApp`. No financial calculations or engine APIs change.

## Challenge gate / proposed PR notes

- Beneficiary and barrier: older adults and people preparing for budget counselling may
  find dense financial interfaces or typing difficult. A readable welcome, optional speech
  capture and editable confirmation reduce those interaction barriers.
- UBS: retain the real, reproducible fictional baseline/alternative comparison and expose
  its assumptions. Speech only captures a proposed request; it cannot execute a scenario.
- IMC: the proposed workflow is preparation for a budget-support conversation, including
  for a Caritas-type service's beneficiaries. Charity eligibility, partnership and actual
  benefit remain unvalidated; do not claim them. Demonstrable benefit is an alternative
  to typing plus explicit review, not measured financial impact.
- JetBrains: theme, navigation, screens, request review and engine comparison are shared
  in `commonMain`; Android alone supplies system speech recognition. Desktop has the same
  request review through typing.
- Demo proof: Welcome -> How it works -> demo comparison -> Back -> companion -> speak
  (Android) or type -> edit -> confirm. Typed input is the fallback when recognition is
  absent or offline. Both platform launches and hardware speech need honest runtime checks.
- Feasibility: UI owner implements two frontend milestones without core changes. Interfaces
  are a small speech adapter and callbacks. Test financial regressions with existing core
  tests, compile both platforms, then manually exercise permission/lifecycle/accessibility.
  Integration risks are speech-provider availability and platform permission/lifecycle
  behavior. Cut LLM parsing, cloud sync and speculative graph animation before expanding scope.

## Manual acceptance

1. Launch desktop and Android. Explore the demo and return with Back (also Android system
   Back / desktop Escape). Check all original figures and the assumptions section.
2. Open and dismiss How does it work. Resize desktop, rotate Android, enlarge system text,
   use keyboard focus and TalkBack, and enable Reduce motion / system animation disabling.
3. Open the companion. On Android tap the microphone: permission is requested only now.
   Test grant, deny, deny permanently, service unavailable, silence and network/provider error.
4. Speak, Stop, edit the final text, Confirm, edit again and Retry. Cancel while listening
   and processing. Go Back or background the app mid-recording and return. The microphone
   must stop, stale callbacks must not change the draft, and recording must not resume.
5. Type instead on both platforms. Blank input cannot be confirmed. Confirmation shows the
   exact reviewed request and a next-milestone notice; it never calls core.
6. On API 31+ with an on-device service, check the on-device notice; otherwise check the
   system-service disclosure. Model/language availability still depends on the device.

No transcript or audio is saved to disk by Ripple or sent to an app backend. The system
recognition provider may process audio remotely when on-device recognition is unavailable.
Draft text is retained in Compose saved state for activity recreation. English and French
guided prompts and recognition languages can be selected. Android read-aloud uses installed
offline voices only; full app localization and financial interpretation remain deferred.

## Two-way voice milestone

Native Android TTS initializes once per activity and is shut down on destruction. Voice is
muted by default. Unmute reads the current visible response; Replay reads it again. Mute,
Stop, Back and backgrounding interrupt playback. Capture stops TTS before starting; TTS
cancels capture before speaking. No audio/text is sent to an app backend. A provider may
still process recognition remotely when the on-device service is absent, as disclosed.

The guided flow recognizes only a short explicit list of moving-home phrases, or a user's
explicit selection of the guided example. It asks about the deposit, accepts a confirmed
answer (including uncertainty), and stops without interpreting amounts or fabricating a
simulation. All other requests receive a clear scope limitation. The existing financial
demo remains separate and uses the unchanged `compare()` API. No unmerged engine types
are imported and no graph/history or suggested financial adjustment is invented.

Impact note: read-aloud and one question at a time provide an additional access route for
people who find financial text difficult. Actual beneficiary benefit and charity eligibility
still require validation. Shared prompts/review run on both platforms; speech services are
Android-specific. No new dependencies or paid services were added.

## Verification (2026-11-21)

- Phase 1: `:composeApp:compileKotlinJvm :androidApp:assembleDebug` passed before voice work.
- Final: `:core:allTests :composeApp:compileKotlinJvm :androidApp:assembleDebug
  :androidApp:lintDebug` passed. All 14 existing core tests passed on JVM and Android host
  (28 test executions, no failures). No new test dependencies were added.
- Android lint: no errors; remaining warnings concern the existing SDK version and absent
  launcher icon. Gradle also reports deprecations for a future Gradle version.
- `:composeApp:run` was launched again for this milestone; desktop interaction has not
  been visually verified. Android voice APIs do not enter the JVM sources.
- Emulator: existing `Pixel_10a`, Android 17 / API 37, serial `emulator-5554`. Its snapshot
  boot stalled; a cold boot with software graphics worked without data deletion/downloads.
  `:androidApp:installDebug` succeeded and `ch.ripple.android/.MainActivity` was launched.
- Actually exercised: welcome, explanation open/dismiss, both navigation paths, Android
  and onscreen Back, assistant, permission denial/retry/grant, real recognition attempt,
  error recovery, typed draft editing, confirmation, guided deposit question and typed
  uncertain answer, English read-aloud/replay/mute, French unavailable-voice fallback,
  scrolling and portrait/landscape recreation with retained confirmed text.
- Recognition: service exists, but real attempts returned Android error 13
  (`ERROR_LANGUAGE_UNAVAILABLE`), including with explicit en-US. No successful real-audio
  recognition or listening animation is claimed. No transcript was injected as speech.
- TTS: English installed; actual onStart/onDone callbacks and UI Speaking state observed.
  Replay/mute exercised. French offline voice absent. Audible output cannot be heard by
  this tool session; a person must verify speaker quality/volume. No voice was downloaded.
- Visually inspected Android screenshots exposed low-contrast system-bar icons and an
  overcrowded assistant header. Fixed light-system-bar appearance, collapsed optional
  voice controls, shortened heading and adjusted spacing. Screenshots are local build
  artifacts in `androidApp/build/emulator-testing/screenshots/`; they are not source assets.
- Final animation verification: `24-path-animation-start.png` and
  `25-path-animation-end.png` were captured and visually inspected. The path now begins
  drawing when it is substantially visible after scrolling, rather than finishing offscreen.
- Ordered native logs verified TTS playback interruption before recognition starts. Evidence:
  `androidApp/build/emulator-testing/speech-verification.log`. Voice playback is not
  automatically repeated after activity recreation for an already-spoken response.
- Remaining manual checks: real microphone audio on a device with a usable installed
  recognition model, audible speech, French audio on a device with its voice installed,
  TalkBack, largest font sizes, and desktop interaction. Normal error/typed paths work
  without installing a recognition model.
