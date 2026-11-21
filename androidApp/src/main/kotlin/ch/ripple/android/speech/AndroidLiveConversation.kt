package ch.ripple.android.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.*
import ch.ripple.app.assistant.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Ephemeral-token authenticated native audio, directly between Android and Gemini. */
class AndroidLiveConversation(
    private val context: Context,
    private val requestPermission: () -> Unit,
    private val beforeStart: () -> Unit,
) : LiveConversation {
    override val available = true
    override var state by mutableStateOf(LiveVoiceState())
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var tokenCall: Call? = null
    private var connectJob: Job? = null
    private var deadline: Job? = null
    private var audio: LivePcmAudio? = null
    @Volatile private var generation = 0
    private var waitingPermission = false
    private var pendingContext = ""
    private var speaking = false
    private var discardOutput = false
    private var replayJob: Job? = null
    private val replayAudio = ByteArrayOutputStream()
    private var replayOverflow = false
    private var completedReplay = ByteArray(0)
    private val manager = context.getSystemService(AudioManager::class.java)
    private var oldMode = AudioManager.MODE_NORMAL
    private var focus: AudioFocusRequest? = null
    private var modeOwned = false
    private var previousDevice: AudioDeviceInfo? = null
    private var changedDevice = false
    private var previousSpeaker = false
    private val tokenUrl: String? = if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        "http://10.0.2.2:8787/token" else null // Release needs authenticated HTTPS provisioning, never dev cleartext.

    override fun start(verifiedContext: String) {
        end()
        beforeStart()
        pendingContext = verifiedContext.take(8_000)
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            waitingPermission = true; requestPermission(); return
        }
        connect()
    }
    fun permissionResult(granted: Boolean) {
        if (!waitingPermission) return
        waitingPermission = false
        if (granted) connect() else {
            fail("Microphone permission was denied. You can type instead, or allow access in Android Settings and retry.")
            state = state.copy(permissionDenied = true)
        }
    }
    private fun connect() {
        val url = tokenUrl ?: return fail("Live voice needs a secure token service configured for this build. You can type instead.")
        val token = generation
        state = state.copy(phase = LivePhase.Connecting, error = null)
        connectJob = scope.launch {
            try {
                val credentials = withContext(Dispatchers.IO) {
                    val call = http.newCall(Request.Builder().url(url).post(ByteArray(0).toRequestBody()).build())
                    tokenCall = call
                    call.execute().use { response ->
                        val body = response.peekBody(65_537).string() // Never log response or credential.
                        check(body.length <= 65_536)
                        val json = JSONObject(body)
                        if (!response.isSuccessful) throw TokenError(json.optJSONObject("error")?.optString("message")
                            ?: "Token service unavailable. You can type instead.")
                        json
                    }
                }
                if (token != generation) return@launch
                val ephemeral = credentials.getString("token")
                val setup = credentials.getJSONObject("setup")
                state = state.copy(model = credentials.getString("model"))
                val endpoint = "https://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained"
                    .toHttpUrl().newBuilder().addQueryParameter("access_token", ephemeral).build()
                socket = http.newWebSocket(Request.Builder().url(endpoint).build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (token != generation) { webSocket.cancel(); return }
                        webSocket.send(JSONObject().put("setup", setup).toString())
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) = receive(token, text)
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) = receive(token, bytes.utf8())
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        scope.launch { if (token == generation) fail("Live connection failed. Check project access, model, quota and internet, then retry.") }
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, null)
                        scope.launch { if (token == generation) fail("The voice session disconnected (code $code). Start a new conversation or type instead.") }
                    }
                })
                deadline = scope.launch { delay(30_000); if (token == generation && !state.connected) fail("Gemini did not finish connecting. Please try again.") }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: TokenError) { if (token == generation) fail(error.message ?: "Token service rejected this request.")
            } catch (_: Exception) { if (token == generation) fail("Cannot reach the local token service. Start it in your configured PowerShell terminal. You can type instead.") }
        }
    }

    private fun receive(token: Int, text: String) {
        scope.launch {
            if (token != generation) return@launch
            try {
                require(text.length <= 1_048_576)
                val message = JSONObject(text)
                if (message.has("error")) { fail("Gemini rejected the live session. Check model access and quota."); return@launch }
                if (message.has("goAway")) { fail("This live session is expiring. Please start a new conversation."); return@launch }
                when (message.optJSONObject("voiceActivity")?.optString("type")) {
                    "ACTIVITY_START" -> if (!state.muted) { state = state.copy(phase = LivePhase.Listening, heard = "", reply = "") }
                    "ACTIVITY_END" -> if (!speaking && !state.muted) state = state.copy(phase = LivePhase.Thinking)
                }
                if (message.has("setupComplete") && !state.connected) {
                    deadline?.cancel()
                    prepareAudio(token)
                    state = state.copy(connected = true)
                    // Only actual verified facts; no conversational parsing or session mutation.
                    send(JSONObject().put("clientContent", JSONObject().put("turns", org.json.JSONArray().put(
                        JSONObject().put("role", "user").put("parts", org.json.JSONArray().put(JSONObject().put("text",
                            "App context, not a spoken request. $pendingContext Wait for the user's speech.")))))
                        .put("turnComplete", false)))
                    audio?.startMicrophone()
                    deadline = scope.launch { delay(9 * 60_000L); if (token == generation) fail("The voice session has reached its time limit. Start a new conversation.") }
                }
                val content = message.optJSONObject("serverContent") ?: return@launch
                if (content.optBoolean("interrupted")) {
                    replayJob?.cancel(); replayJob = null
                    discardOutput = false; audio?.interrupt(); replayAudio.reset(); replayOverflow = false; speaking = false; updatePhase()
                }
                content.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let {
                    state = state.copy(heard = (state.heard + it).takeLast(2_000))
                }
                content.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let {
                    state = state.copy(reply = (state.reply + it).takeLast(2_000))
                }
                val parts = content.optJSONObject("modelTurn")?.optJSONArray("parts")
                if (parts != null && !content.optBoolean("interrupted")) for (i in 0 until parts.length()) {
                    val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                    val mime = inline.optString("mimeType")
                    require(mime.startsWith("audio/pcm") && (!mime.contains("rate=") || mime.contains("rate=24000")))
                    val data = inline.optString("data")
                    require(data.length <= 350_000)
                    if (!discardOutput) {
                        val bytes = Base64.decode(data, Base64.DEFAULT)
                        if (!replayOverflow && replayAudio.size() + bytes.size <= 1_440_000) replayAudio.write(bytes)
                        else { replayOverflow = true; replayAudio.reset() } // Never replay a truncated response.
                        if (audio?.enqueue(bytes) != true) { fail("Audio playback could not keep up. Please reconnect."); return@launch }
                    }
                }
                if (content.optBoolean("turnComplete")) {
                    completedReplay = if (replayOverflow) ByteArray(0) else replayAudio.toByteArray()
                    replayAudio.reset(); replayOverflow = false
                    state = state.copy(canReplay = completedReplay.isNotEmpty())
                    discardOutput = false; audio?.turnComplete()
                    if (!speaking) updatePhase()
                }
            } catch (_: Exception) { if (token == generation) fail("The voice service returned an unsupported response. You can reconnect or type instead.") }
        }
    }

    private fun prepareAudio(token: Int) {
        oldMode = manager.mode
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build())
            .setOnAudioFocusChangeListener { change -> if (change < 0) scope.launch { if (token == generation) fail("Voice paused because another app needs audio. Tap to reconnect.") } }.build()
        check(manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        focus = request; manager.mode = AudioManager.MODE_IN_COMMUNICATION; modeOwned = true
        if (Build.VERSION.SDK_INT >= 31) {
            previousDevice = manager.communicationDevice
            val devices = manager.availableCommunicationDevices
            val personalAudio = devices.any { it.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET) }
            if (!personalAudio) devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let {
                changedDevice = manager.setCommunicationDevice(it)
            }
        } else {
            @Suppress("DEPRECATION")
            previousSpeaker = manager.isSpeakerphoneOn
            @Suppress("DEPRECATION")
            manager.isSpeakerphoneOn = true
        }
        audio = LivePcmAudio(scope, onInput = { bytes, level ->
            // A stale recording can never enter a newly-created socket/session.
            if (token == generation) {
                val current = socket
                if (current != null && current.queueSize() < 65_536) {
                    val accepted = current.send(JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject()
                        .put("mimeType", "audio/pcm;rate=16000").put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)))).toString())
                    if (!accepted) scope.launch { if (token == generation) fail("The voice connection closed. Please reconnect.") }
                    scope.launch { if (token == generation && !state.muted) {
                        state = state.copy(inputLevel = level,
                            phase = if (speaking) LivePhase.Speaking else if (state.phase == LivePhase.Thinking) LivePhase.Thinking else LivePhase.Listening)
                    } }
                } else scope.launch { if (token == generation) fail("The connection is too slow for live audio. Please reconnect.") }
            }
        }, onPlayback = { playing, level -> scope.launch { if (token == generation) {
            speaking = playing; state = state.copy(outputLevel = level); updatePhase()
        } } }, onError = { scope.launch { if (token == generation) fail("Microphone or speaker unavailable. You can type instead.") } })
        Log.i("RippleLive", "Setup accepted; PCM pipeline initialized (no audio or tokens logged)")
    }
    private fun send(message: JSONObject) { if (socket?.send(message.toString()) != true) error("Session disconnected") }
    private fun updatePhase() { state = state.copy(phase = when { speaking -> LivePhase.Speaking; state.muted -> LivePhase.Muted; else -> LivePhase.Listening }) }

    override fun mute(muted: Boolean) {
        if (!state.connected || state.muted == muted) return
        try {
            if (!muted && replayJob?.isActive == true) {
                replayJob?.cancel(); replayJob = null; audio?.interrupt(); discardOutput = false
            }
            state = state.copy(muted = muted, inputLevel = 0f)
            if (muted) { audio?.stopMicrophone(); send(JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true))) }
            else audio?.startMicrophone()
            updatePhase()
        } catch (_: Exception) { fail("The microphone could not resume. Please reconnect or type instead.") }
    }
    override fun stopAudio() {
        if (!state.connected) { end(); return }
        replayJob?.cancel(); replayJob = null
        discardOutput = true; audio?.interrupt(); speaking = false; mute(true)
    }
    override fun replay() {
        if (!state.connected || completedReplay.isEmpty()) return
        replayJob?.cancel()
        mute(true); discardOutput = true; audio?.interrupt()
        val recording = completedReplay
        replayJob = scope.launch {
            for (start in recording.indices step 1_920) {
                if (audio?.enqueue(recording.copyOfRange(start, minOf(start + 1_920, recording.size))) != true) {
                    fail("This response could not be replayed."); return@launch
                }
                delay(40)
            }
            audio?.turnComplete()
        }
    }
    override fun end() {
        generation++; waitingPermission = false
        connectJob?.cancel(); connectJob = null; deadline?.cancel(); deadline = null
        replayJob?.cancel(); replayJob = null
        tokenCall?.cancel(); tokenCall = null
        socket?.close(1000, "User ended session"); socket = null
        audio?.close(); audio = null
        focus?.let { manager.abandonAudioFocusRequest(it) }; focus = null
        if (Build.VERSION.SDK_INT >= 31 && changedDevice) {
            previousDevice?.let { manager.setCommunicationDevice(it) } ?: manager.clearCommunicationDevice()
        } else if (Build.VERSION.SDK_INT < 31 && modeOwned) {
            @Suppress("DEPRECATION")
            manager.isSpeakerphoneOn = previousSpeaker
        }
        changedDevice = false; previousDevice = null
        if (modeOwned) { manager.mode = oldMode; modeOwned = false }
        speaking = false; discardOutput = false; pendingContext = ""
        replayAudio.reset(); replayOverflow = false; completedReplay = ByteArray(0)
        state = LiveVoiceState()
    }
    override fun openMicrophoneSettings() {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    private fun fail(message: String) { end(); state = state.copy(phase = LivePhase.Error, error = message) }
    fun close() { end(); scope.cancel(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    private class TokenError(message: String) : Exception(message)
}
