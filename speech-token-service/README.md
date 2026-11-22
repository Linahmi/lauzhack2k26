# Routes voice-only Gemini Live issuer (development)

The phone streams audio directly to Google. This Python stdlib service issues a short-lived,
single-use credential; the permanent key stays in the PowerShell environment. It never
receives audio or transcripts. Do not put credentials in local.properties, source, or an APK.

Gemini has no tools. Its constrained system instruction permits only reading text inside
`[ROUTES SAYS] ... [/ROUTES SAYS]`. The app sends final user transcripts to RoutesSession's
deterministic parser and speaks only its new Routes/Finding turns and pending summaries.
Unrequested model audio is discarded. A spoken yes cannot save a proposal: tap **That's real, save**.

## Emulator

In the PowerShell terminal that already contains GEMINI_API_KEY and GEMINI_LIVE_MODEL:

```powershell
python .\speech-token-service\server.py --models
# Choose a project-accessible native-audio Live model. No key is printed.
.\speech-token-service\Start.ps1
```

The default issuer binds 127.0.0.1:8787; the debug APK uses http://10.0.2.2:8787/token.
Restart any issuer from ui/my-future: it has a different system instruction/profile tools.
The phone checks `/health` for `routesVoiceProtocol: 1` before recording. A missing,
unconfigured or outdated issuer shows a reason and falls back to Android speech recognition.
Typing always uses the same session and engine. No model/language downloads happen automatically.

The native transport supports final inputTranscription utterances from Gemini 3.1+;
legacy 2.5 fragments are assembled through turnComplete. Interim transcripts are display-only.
Actual supported model/device checks are recorded with the screenshots; compilation isn't audio proof.

## Real Android phone on a trusted LAN

In the ignored root local.properties file, add the laptop's actual private IPv4:

```properties
routes.liveTokenUrl=http://192.168.1.20:8787/token
```

Rebuild the debug APK. Only that URL's host gets a generated debug cleartext exception.
Release has an empty token URL and no cleartext exception. The LAN IP is configuration, not a key.

Start the service with the laptop and phone's actual private IPv4 addresses:

```powershell
.\speech-token-service\Start.ps1 -HostAddress 192.168.1.20 -AllowClient 192.168.1.30
```

LAN binding is explicit and requires an exact phone allowlist. Host/Origin checks, no CORS,
no-store responses and the six-requests/minute limit remain. Allow the port in your own
firewall only on the trusted private network. This isn't a production authentication service.
Use a production authenticated HTTPS issuer before enabling release voice.

## Verification

```powershell
python -m unittest discover -s speech-token-service -p test_server.py -v
.\gradlew.bat :composeApp:jvmTest :core:jvmTest :androidApp:assembleDebug
```

The bridge tests use a fake LiveConversation and the real engine: Alex's original CHF 300,
proposed deposit CHF 1,450, explicit tap leading to CHF 550, spoken yes protection and a
waiting what-if that leaves the confirmed version unchanged. Fake transcripts are not
proof of microphone recognition or audible Gemini output. Runtime evidence and limitations
belong in docs/design/android-screens/GEMINI_LIVE_VERIFICATION.md.

The native PCM transport and issuer originated in Marwa Saidi's ui/my-future work at f43b0e9;
the import commit retains her authorship. Desktop and core source are unchanged.
