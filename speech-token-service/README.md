# Ripple local Gemini Live credentials (development only)

This isolated Python 3.12 stdlib service has no connection to Lina's Ollama/Decision Map.
It issues a one-use credential; Android streams audio **directly to Google**, not here.
No permanent credential, audio or transcript is written to disk or logged by this service.
It binds only 127.0.0.1, rejects browser Origin/foreign Host headers, sends no CORS headers,
limits issuance to six requests/minute and sets no-store. Local processes are trusted in this
development setup. This is not production authentication or a public endpoint.

## Current verification (2026-11-21)

- The agent's child processes cannot see GEMINI_API_KEY. The loopback service was started
  without a key for health/NOT_CONFIGURED testing; no real token was minted, no Gemini
  model authenticated, and no Live audio or multi-turn/interruption/English/French success
  is claimed. Configure the model only after listing your own project's available models.
- Pixel_10a, API 37: the real three-second AudioRecord probe captured 94,720 bytes, with
  nonzero samples and no capture error. Intelligible speech is unverified. A synthetic
  100ms PCM tone was accepted by AudioTrack and its playback head drained. This is an
  output-pipe check, not generated Gemini speech or human verification of audibility.
- The short-tone test exposed a startup-threshold stall; reducing the streaming buffer
  and setting the API 31+ start threshold fixed the tested playback path.
- Six token-service tests cover missing configuration, model validation, actual REST
  token schema/constraints/expiry, sanitized provider errors and the local Host/Origin/body boundary. Five existing
  frontend adapter tests pass; 128 core executions remain green (up-to-date on final run).
  Android assemble/install, lint and JVM compilation pass. Lint has zero errors and five
  warnings: SDK/icon defaults, cached OkHttp version and avoiding shared version-catalog edits.
- Actual Gemini Listening/Speaking screenshots cannot be captured without authentication.
  Ready, disclosure, permission/error and text/core fallback are testable without Gemini.
  A separate per-mode saveable-state holder retains guided text progress while switching
  back to the voice entry. Live audio is never automatically resumed on activity recreation.

Changed frontend files: App, MicrophoneControl, MainActivity, Android manifest/module
dependency; new LiveConversation/LiveCompanionScreen, AndroidLiveConversation/LivePcmAudio,
debug-only network policy and explicit microphone probe. Welcome, UBS, core, Ollama and
Decision Map source files were not changed. No shared Gradle configuration was changed.

## Windows setup

From the project root, **in the existing PowerShell terminal containing your API key**:

```powershell
# Prints availability only; never the key.
if ([string]::IsNullOrWhiteSpace($env:GEMINI_API_KEY)) { 'Key unavailable in this terminal' } else { 'Key available' }
python .\speech-token-service\server.py --models
# Select a native-audio Live model returned for YOUR project.
# Current docs describe gemini-3.8-live; this does not prove your project has access.
$env:GEMINI_LIVE_MODEL = 'gemini-3.8-live'
.\speech-token-service\Start.ps1
```

Do not paste the key into chat, write .env files, Gradle/BuildConfig, resources or source.
A key assigned in one PowerShell process is not inherited by an already-running IDE/agent.
Run this script in that terminal; do not copy it to another process through a file.
To stop, press Ctrl+C. Never use a network tunnel or bind 0.0.0.0.

`GET http://127.0.0.1:8787/health` reports configuration only. `POST /token` has no body;
it validates the configured model's advertised bidiGenerateContent capability before minting
through `POST https://generativelanguage.googleapis.com/v1beta/auth_tokens`.
Do not print/save the token response. It contains a credential (one new session within 60s,
expires after ten minutes). Its model, audio response modality and system instruction are constrained.
The REST request uses `bidiGenerateContentSetup` (the official SDK maps its
`liveConnectConstraints` convenience option to this wire field); the same full setup is
returned to Android. The omitted fieldMask locks the complete setup.
Errors are structured as `error.code`/`error.message`; upstream response bodies are suppressed.
Invalid credentials, model access, quotas, timeouts and local rate limits are distinguished.

## Android

Debug builds use `http://10.0.2.2:8787/token` on Pixel_10a. Debug cleartext is permitted only
for emulator/loopback hosts. Release builds permit no cleartext and intentionally require
a production authenticated HTTPS provisioning integration before Live can start.
OkHttp is Android-only; shared Gradle configuration is unchanged.

The constrained v1beta WebSocket uses an in-memory ephemeral `access_token`. Do not enable
HTTP/WebSocket body/URL logging. AudioRecord sends 16kHz mono signed PCM16 little-endian
in 40ms packets; AudioTrack plays 24kHz mono PCM16. The bounded playback queue is flushed
on server interruption. Automatic server VAD handles conversational turns and barge-in;
muting stops/releases capture and sends audioStreamEnd. Stop mutes and discards the current
response; End/Back/background/rotation releases all resources and requires a new consented
session. AEC/noise suppression are used where available. Use headphones on physical devices
if acoustic echo cancellation is inadequate. No automatic reconnect/recording in background.

Ready means no recording. Connecting means token/setup pending. Listening starts only after
setupComplete and successful microphone reads. Speaking follows PCM accepted by AudioTrack,
not a text reply or Android TTS. Physical audibility and intelligible speech still need testing.
Generated audio stays in bounded memory for replay and is cleared on End. No mandatory
transcription review, SpeechRecognizer or TTS participates in the native Live pipeline.

For a physical Android device: use USB debugging and
`adb reverse tcp:8787 tcp:8787`. The current debug URL is emulator-specific; change only
the debug provisioning URL in AndroidLiveConversation to `http://127.0.0.1:8787/token`
for a physical-device build. Never expose the local issuer to Wi-Fi/the public internet.

## Verification

```powershell
python -m unittest discover -s .\speech-token-service -v
.\gradlew.bat :core:allTests :composeApp:jvmTest :composeApp:compileKotlinJvm :androidApp:assembleDebug :androidApp:lintDebug :androidApp:installDebug
```

Open Welcome → Connect → What's on your mind? → circle → disclosure consent → microphone
permission. Say an English sentence, wait for a spoken answer, then ask a second question.
Repeat in French, interrupt mid-response, test Mute/Unmute, Stop, replay, End, Back and
backgrounding. Verify the orange/green OS microphone indicator goes off after End.
Only a correct spoken response to captured speech establishes audio-in/audio-out success.
A successful token response or setupComplete alone does not establish that.

Without a configured key the service can still be started with `python .\speech-token-service\server.py`
for an explicit NOT_CONFIGURED test. No provider request is made in that state.
The debug-only, ADB-invoked microphone probe records no files and sends no data:

```powershell
adb shell pm grant ch.ripple.android android.permission.RECORD_AUDIO
adb shell am instrument -w ch.ripple.android/ch.ripple.android.speech.AudioProbeInstrumentation
```

Speak a known phrase while that three-second probe runs. It reports byte/nonzero counts,
not speech recognition. Nonempty PCM can be silence or noise; do not claim intelligibility.

## App/core and Lina boundary

The existing guided text experience is explicitly labelled; it is not Ollama chat. Both modes
use the App-owned FinancialTwinController/confirmed deposit state. Only its existing structured
deposit review/confirmation can change the decision graph; Live model output never calls core.
At session start, after explicit disclosure consent, Gemini receives only the current verified
fictional Briefing facts and qualifications, not arbitrary parsed numbers. Text-mode TTS is
stopped before Live; Live is ended before text-mode playback.

Function calling and model-proposed mutations/navigation are deliberately not enabled until a
real multi-turn audio pipeline can be authenticated/verified. Current voice does not claim
successful navigation or financial changes. The proposed `TextConversationBoundary`, request,
result and structured input types in commonMain are **not Lina's API**: agree session IDs,
revision/conflict rules, evidence, confirmation tokens and intent/destination semantics with her.
Future tools must only stage proposals, validate single-field amounts with DepositInput and
require explicit UI confirmation before controller dispatch. The known core parser's age/amount
ambiguity must never be used for raw model/speech text.

"Explore my future plan · demo" is an explicit transition to the real guided demo. My Finances
is not implemented; no fake dashboard or unsupported retirement simulation was created.

Official references consulted (2026-11-21):
- https://ai.google.dev/gemini-api/docs/live
- https://ai.google.dev/gemini-api/docs/live-api/capabilities
- https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens
- https://ai.google.dev/gemini-api/docs/live-api/tools
- https://aistudio.google.com/docs/live-api/get-started-websocket
- https://aistudio.google.com/api-reference/live#AuthToken
- https://github.com/googleapis/python-genai/blob/main/google/genai/tokens.py

Challenge gate: this milestone tests an accessible voice entry route for users who struggle
with financial text. Existing deterministic demo facts remain the UBS evidence; a nonprofit
beneficiary outcome remains unvalidated. Compose UI and confirmation logic are shared KMP;
streaming is Android-specific. Text is the working fallback. Owner: frontend; scope excludes
Ollama, dashboards, decision graph UI and financial computations. No claims of voice success
without actual captured-speech/multiple-turn evidence.
