# Gemini Live → Routes session: verification

2026-11-22. Branch `feature/gemini-live-routes`, based on `main` at `4fd14cf`.
Device: **Pixel_10a emulator**, `emulator-5554`, Android API 37, 1080×2424.

**Status: implementation and automated checks pass; real Live acceptance is still pending.**
The existing configured issuer reports `gemini-3.1-flash-live-preview` but lacks this
branch's `routesVoiceProtocol: 1`. It was not stopped, restarted, or used to mint
an incompatible profile-enabled credential. A restart was requested in the user's
configured PowerShell terminal. No successful Gemini microphone/readout claim is made.

## Executed

- `:composeApp:jvmTest`: **17 passed**, including four new fake-Live bridge tests.
- `:core:jvmTest`: **116 passed**; core source is unchanged.
- `:androidApp:assembleDebug` and `:androidApp:lintDebug`: passed; lint has zero
  errors and six SDK, backup, icon, and dependency/catalog warnings.
- Release BuildConfig generation and manifest processing: token URL is empty;
  cleartext traffic is disabled and no debug network configuration is referenced.
- Installed and launched the APK. The old issuer was rejected with an explicit
  reason. Android recognition fallback was attempted; the on-device English model
  needs setup. No model was downloaded and no successful recognition is claimed.
- **Typed device flow:** selected the explicitly fictional Alex example; entered
  “What if I moved out?”, `350`, `500`, “not sure”, “1300 to 1700”. The engine
  showed April / CHF 300. Entered “the deposit is 1450”; the proposal stayed
  unsaved until tapping **That's real, save**, then showed CHF 300 → CHF 550.
- **Typed My Routes flow:** “what if I waited two months?” produced the bronze
  what-if view. After a process kill and relaunch the decision/versions remained;
  returning to the confirmed situation still showed CHF 550. My Twin's deposit
  detail correctly labels this device test as **typed**, not “said by voice”.
- **Unavailable-service cold start:** temporarily built the ignored debug URL
  with port **8788**, where no issuer was running. Force-stopped and relaunched;
  the mic displayed “Cannot reach the local token service”; typing “why?” still
  returned the engine explanation. The existing 8787 service was left untouched.
  Restored the default 8787 URL, rebuilt, and installed the final APK.

## Evidence

Actual emulator screenshots are in [`gemini-live/`](gemini-live/).
`03`–`06` document typing and the confirmation tap; `09`–`10` document the
unreachable-service test; `11`–`13` show restored decisions, the unchanged
confirmed version, and the **typed** provenance. `07` shows the final storage/
Google-processing disclosure. Earlier screenshots precede that final copy edit.
The fake-Live JVM tests prove callback → session → engine text sent to the speaker;
they do not establish real speech recognition or audible output.

## Still required before voice acceptance

Restart `speech-token-service/server.py` from this checkout in the terminal holding
`GEMINI_API_KEY` and `GEMINI_LIVE_MODEL`. On a fresh fictional Alex session, perform
the same sequence by microphone and listen to each readout. Verify the proposal
cannot be saved by a spoken “yes”, tap to save, inspect **Confirmed by you · said
by voice** in My Twin, and try waiting two months in My Routes. Capture actual
Listening/Speaking evidence and human confirmation of the read amounts.
Physical-phone LAN operation and real audio quality are also unverified.

Protocol reference: [Google's Live API](https://ai.google.dev/api/live) documents
input transcription separately from model output and its turn-completion events.
The client dispatches final user input only and discards unsolicited model audio.
