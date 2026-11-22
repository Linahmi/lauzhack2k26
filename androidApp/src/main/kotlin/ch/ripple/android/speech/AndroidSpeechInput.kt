package ch.ripple.android.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.*
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.ripple.app.assistant.*
import java.util.Locale

/** Capability-checked capture. Service presence alone does not mean a language model is installed. */
class AndroidSpeechInput(
    private val context: Context,
    private val requestPermission: () -> Unit,
    private val beforeCapture: () -> Unit = {},
) : SpeechInput {
    override var state by mutableStateOf(SpeechInputState(summary = "Checking installed speech support…"))
        private set
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listener: ((SpeechEvent) -> Unit)? = null
    private var waitingForPermission = false
    private var generation = 0
    private var discoveryGeneration = 0
    private var closed = false
    private var timeout: Runnable? = null
    private val probes = mutableListOf<SpeechRecognizer>()
    private val probeTimeouts = mutableListOf<Runnable>()
    private var downloadRecognizer: SpeechRecognizer? = null
    private var deviceTag = "en-US"
    private var systemTag = "en-US"
    private var language = "en"
    private val onDeviceExists get() = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    override val available get() = onDeviceExists || SpeechRecognizer.isRecognitionAvailable(context)
    override val notice get() = if (state.route == SpeechRoute.SYSTEM)
        "System recognition may send audio to the Android provider for processing. It is used only after your permission and a microphone tap."
        else "On-device recognition keeps recognition audio on this device. A compatible installed language model is required."

    override fun setLanguage(language: String) {
        if (this.language != language) refreshSupport(language)
    }

    override fun chooseRoute(route: SpeechRoute, consentToSystemService: Boolean) {
        cancel()
        if (route == SpeechRoute.SYSTEM && !consentToSystemService) return
        state = state.copy(route = route, systemConsent = route == SpeechRoute.SYSTEM && consentToSystemService)
        updateCapability(route, if (route == SpeechRoute.SYSTEM) state.system else state.onDevice, "Selected $route by user.")
    }

    override fun refreshSupport(language: String) {
        if (closed) return
        cancel()
        clearProbes()
        this.language = language
        deviceTag = if (language == "fr") "fr-FR" else "en-US"
        systemTag = deviceTag
        state = state.copy(language = language,
            onDevice = if (onDeviceExists) SpeechCapability.CHECKING else SpeechCapability.MISSING,
            system = if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechCapability.CHECKING else SpeechCapability.MISSING,
            summary = "Checking installed ${languageName()} speech support…", diagnostics = "")
        Log.i("RippleSpeech", "Default recognizer=${Settings.Secure.getString(context.contentResolver, "voice_recognition_service")}; onDevice=$onDeviceExists; requested=$deviceTag")
        if (onDeviceExists) probe(SpeechRoute.ON_DEVICE)
        if (SpeechRecognizer.isRecognitionAvailable(context)) probe(SpeechRoute.SYSTEM)
        if (!available) state = state.copy(summary = "No recognition service is installed. You can type instead.")
    }

    private fun languageName() = if (language == "fr") "French" else "English"
    private fun create(route: SpeechRoute): SpeechRecognizer =
        if (route == SpeechRoute.ON_DEVICE && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)

    private fun intent(tag: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    private fun probe(route: SpeechRoute) {
        val token = discoveryGeneration
        var service: SpeechRecognizer? = null
        var completed = false
        fun finish(capability: SpeechCapability, detail: String, tag: String? = null) {
            if (token != discoveryGeneration || closed) return
            // Some providers emit CANNOT_CHECK_SUPPORT before returning their real language list.
            val previous = if (route == SpeechRoute.ON_DEVICE) state.onDevice else state.system
            if (completed && previous != SpeechCapability.UNKNOWN) return
            completed = true
            if (tag != null) { if (route == SpeechRoute.ON_DEVICE) deviceTag = tag else systemTag = tag }
            updateCapability(route, capability, detail)
            service?.let { probes.remove(it); destroy(it) }
        }
        try {
            service = create(route).also { probes += it; it.setRecognitionListener(QuietListener()) }
            if (Build.VERSION.SDK_INT >= 33) {
                val expiry = Runnable { finish(SpeechCapability.UNKNOWN, "Support query timed out; no audio has been recorded.") }
                probeTimeouts += expiry
                main.postDelayed(expiry, 8_000)
                service.checkRecognitionSupport(intent(if (route == SpeechRoute.ON_DEVICE) deviceTag else systemTag), context.mainExecutor,
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(result: RecognitionSupport) {
                            main.removeCallbacks(expiry)
                            val requested = if (route == SpeechRoute.ON_DEVICE) deviceTag else systemTag
                            fun matching(values: List<String>) = values.firstOrNull { it.equals(requested, true) }
                                ?: values.sorted().firstOrNull { Locale.forLanguageTag(it).language == language }
                            val installed = matching(result.installedOnDeviceLanguages)
                            val online = if (route == SpeechRoute.SYSTEM) matching(result.onlineLanguages) else null
                            val pending = matching(result.pendingOnDeviceLanguages)
                            val downloadable = matching(result.supportedOnDeviceLanguages)
                            val detail = "$route installed=${result.installedOnDeviceLanguages}; downloadable=${result.supportedOnDeviceLanguages}; pending=${result.pendingOnDeviceLanguages}; online=${result.onlineLanguages}"
                            Log.i("RippleSpeech", detail)
                            when {
                                installed != null -> finish(SpeechCapability.READY, detail, installed)
                                online != null -> finish(SpeechCapability.READY, detail, online)
                                pending != null -> finish(SpeechCapability.PENDING_DOWNLOAD, detail, pending)
                                downloadable != null -> finish(SpeechCapability.DOWNLOADABLE, detail, downloadable)
                                else -> finish(SpeechCapability.UNSUPPORTED, detail)
                            }
                        }
                        override fun onError(error: Int) {
                            main.removeCallbacks(expiry)
                            Log.i("RippleSpeech", "$route support query error=$error")
                            finish(if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
                                SpeechCapability.UNSUPPORTED else SpeechCapability.UNKNOWN, "$route support query error $error")
                        }
                    })
            } else finish(SpeechCapability.UNKNOWN, "This Android version cannot query installed language support.")
        } catch (_: RuntimeException) {
            finish(SpeechCapability.MISSING, "Recognition service could not be queried.")
        }
    }

    private fun updateCapability(route: SpeechRoute, capability: SpeechCapability, detail: String) {
        state = if (route == SpeechRoute.ON_DEVICE) state.copy(onDevice = capability) else state.copy(system = capability)
        val summary = if (state.route == SpeechRoute.SYSTEM && state.system == SpeechCapability.READY)
            "${languageName()} system recognition is ready. The provider may process audio online."
        else when (state.onDevice) {
            SpeechCapability.READY -> "${languageName()} on-device recognition is ready."
            SpeechCapability.DOWNLOADABLE -> "The ${languageName()} on-device model is not installed. You can request a download."
            SpeechCapability.PENDING_DOWNLOAD -> "The ${languageName()} model download is pending. Check again when it finishes."
            SpeechCapability.CHECKING -> "Checking installed ${languageName()} speech support…"
            SpeechCapability.UNKNOWN -> "The on-device service cannot report language support. One user-started attempt is available."
            else -> "${languageName()} is not available on-device. You can type or choose system recognition."
        }
        state = state.copy(summary = summary, diagnostics = (state.diagnostics + "\n" + detail).trim())
    }

    override fun downloadModel(consented: Boolean) {
        if (!consented || !state.canDownload || Build.VERSION.SDK_INT < 33 || closed) return
        cancel()
        try {
            val service = create(SpeechRoute.ON_DEVICE)
            downloadRecognizer = service
            service.setRecognitionListener(QuietListener())
            state = state.copy(onDevice = SpeechCapability.PENDING_DOWNLOAD, summary = "Model download requested. Recording will not start automatically.")
            if (Build.VERSION.SDK_INT >= 34) service.triggerModelDownload(intent(deviceTag), context.mainExecutor, object : ModelDownloadListener {
                override fun onProgress(completedPercent: Int) { if (!closed) state = state.copy(summary = "Model download: $completedPercent%. Recording stays off.") }
                override fun onSuccess() { destroy(service); downloadRecognizer = null; if (!closed) refreshSupport(language) }
                override fun onScheduled() { if (!closed) state = state.copy(summary = "The speech provider scheduled the model download. Check again later.") }
                override fun onError(error: Int) { destroy(service); downloadRecognizer = null; if (!closed) state = state.copy(onDevice = SpeechCapability.DOWNLOADABLE, summary = "Model download could not finish (code $error). You can type instead.") }
            }) else service.triggerModelDownload(intent(deviceTag))
        } catch (_: RuntimeException) {
            state = state.copy(onDevice = SpeechCapability.DOWNLOADABLE, summary = "This provider could not request a model download. You can type instead.")
        }
    }

    override fun start(onEvent: (SpeechEvent) -> Unit) {
        cancel()
        beforeCapture()
        listener = onEvent
        if (!state.canRecord) {
            finish(SpeechEvent.Failure("${languageName()} voice needs setup. You can type instead."))
            return
        }
        onEvent(SpeechEvent.Preparing)
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            waitingForPermission = true
            requestPermission()
        } else beginRecognition()
    }

    fun permissionResult(granted: Boolean) {
        if (!waitingForPermission || listener == null) return
        waitingForPermission = false
        if (granted) beginRecognition() else finish(SpeechEvent.Failure("Microphone permission was not granted. You can type instead."))
    }

    private fun beginRecognition() {
        val token = generation
        val route = state.route
        try {
            val service = create(route)
            recognizer = service
            service.setRecognitionListener(object : QuietListener() {
                private fun emit(event: SpeechEvent) { if (token == generation) listener?.invoke(event) }
                override fun onReadyForSpeech(params: Bundle?) { emit(SpeechEvent.Listening) }
                override fun onBeginningOfSpeech() { emit(SpeechEvent.Listening) }
                override fun onEndOfSpeech() {
                    if (token == generation) { emit(SpeechEvent.Processing); scheduleTimeout(12_000) }
                }
                override fun onError(error: Int) {
                    if (token != generation) return
                    Log.i("RippleSpeech", "$route recognition error=$error")
                    if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
                        updateCapability(route, SpeechCapability.UNSUPPORTED, "$route rejected the selected language (code $error). Check support or change service before retrying.")
                    finish(SpeechEvent.Failure(errorMessage(error)))
                }
                override fun onResults(results: Bundle?) {
                    if (token != generation) return
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }
                    finish(if (text == null) SpeechEvent.Failure("No words were recognised. Try again or type instead.") else SpeechEvent.Result(text))
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    emit(SpeechEvent.Partial(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()))
                }
            })
            scheduleTimeout(45_000)
            val tag = if (route == SpeechRoute.ON_DEVICE) deviceTag else systemTag
            Log.i("RippleSpeech", "Capture starting route=$route language=$tag; playback stopped; systemConsent=${state.systemConsent}")
            service.startListening(intent(tag))
        } catch (_: RuntimeException) {
            finish(SpeechEvent.Failure("The speech service could not start. Please type or check voice settings."))
        }
    }

    override fun stop() {
        if (recognizer == null) return
        listener?.invoke(SpeechEvent.Processing)
        scheduleTimeout(12_000)
        try { recognizer?.stopListening() } catch (_: RuntimeException) { finish(SpeechEvent.Failure("Recording was interrupted. Please retry or type instead.")) }
    }

    override fun cancel() {
        generation++
        waitingForPermission = false
        listener = null
        timeout?.let(main::removeCallbacks)
        timeout = null
        val old = recognizer
        recognizer = null
        try { old?.cancel() } catch (_: RuntimeException) { }
        old?.let(::destroy)
    }

    fun interrupt() {
        if (listener != null) finish(SpeechEvent.Failure("Recording stopped when Route left the foreground. Tap Record when ready.")) else cancel()
    }
    fun close() { cancel(); clearProbes(); downloadRecognizer?.let(::destroy); downloadRecognizer = null; closed = true }
    private fun clearProbes() {
        discoveryGeneration++
        probeTimeouts.forEach(main::removeCallbacks); probeTimeouts.clear()
        probes.toList().forEach(::destroy); probes.clear()
    }
    private fun destroy(service: SpeechRecognizer) { try { service.destroy() } catch (_: RuntimeException) { } }
    private fun finish(event: SpeechEvent) { val callback = listener; cancel(); callback?.invoke(event) }
    private fun scheduleTimeout(milliseconds: Long) {
        timeout?.let(main::removeCallbacks)
        timeout = Runnable { finish(SpeechEvent.Failure("Speech recognition took too long. Retry or type instead.")) }.also { main.postDelayed(it, milliseconds) }
    }
    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "This language is not ready in the selected service. Choose another service or type instead."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH -> "No clear speech was heard. Retry or type instead."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone access is off. Allow it in Android Settings, or type instead."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "The speech provider could not connect. Retry or type instead."
        else -> "Recording could not finish. Retry or type instead."
    }
    private open class QuietListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onError(error: Int) = Unit
        override fun onResults(results: Bundle?) = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
