package ch.ripple.android.speech

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.ripple.app.assistant.SpeechOutput
import ch.ripple.app.assistant.SpeechOutputState

class AndroidSpeechOutput(context: Context, private val beforeSpeak: () -> Unit) : SpeechOutput {
    override var state by mutableStateOf(SpeechOutputState())
        private set
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private var utterance = 0
    private var voices = emptyMap<String, Voice>()
    private val engine = TextToSpeech(context.applicationContext) { result ->
        // Post so the constructor has assigned engine even with a fast service callback.
        main.post { if (!closed) initialize(result) }
    }

    private fun initialize(result: Int) {
        if (result != TextToSpeech.SUCCESS) {
            state = SpeechOutputState(message = "Read-aloud could not start. You can continue with text.")
            return
        }
        try {
            voices = engine.voices.orEmpty().filter {
                !it.isNetworkConnectionRequired && "notInstalled" !in it.features.orEmpty()
            }.sortedBy { it.name }.filter { it.locale.language in setOf("en", "fr") }
                .groupBy { it.locale.language }.mapValues { (_, options) -> options.first() }
            engine.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            engine.setSpeechRate(.9f)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = update(utteranceId, true, "Speaking")
                override fun onDone(utteranceId: String?) = update(utteranceId, false, "Ready to read aloud")
                @Deprecated("Required by Android")
                override fun onError(utteranceId: String?) = update(utteranceId, false, "Speech playback failed. You can retry or read the response.")
                override fun onStop(utteranceId: String?, interrupted: Boolean) = update(utteranceId, false, "Speech stopped")
            })
            state = SpeechOutputState(voices.keys, message = if (voices.isEmpty())
                "No installed English or French voice is available. Continue with text."
                else "Installed read-aloud voices: ${voices.keys.joinToString { if (it == "fr") "Français" else "English" }}")
            Log.i("RippleSpeech", "TTS initialized; installed languages=${voices.keys}")
        } catch (_: RuntimeException) {
            state = SpeechOutputState(message = "Installed voices could not be loaded. Continue with text.")
        }
    }

    private fun update(id: String?, speaking: Boolean, message: String) {
        main.post {
            if (!closed && id == utterance.toString()) {
                state = state.copy(speaking = speaking, message = message)
                Log.i("RippleSpeech", "TTS $message") // Never log a user's text.
            }
        }
    }

    override fun speak(text: String, language: String) {
        if (closed) return
        stop()
        val voice = voices[language]
        if (voice == null) {
            state = state.copy(message = "This language has no installed voice. You can read the response.")
            return
        }
        beforeSpeak()
        try {
            if (engine.setVoice(voice) == TextToSpeech.ERROR) {
                state = state.copy(message = "The selected voice is unavailable. Continue with text.")
                return
            }
            state = state.copy(speaking = true, message = "Preparing speech…")
            if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utterance.toString()) == TextToSpeech.ERROR) {
                state = state.copy(speaking = false, message = "Speech playback failed. You can retry or read the response.")
            }
        } catch (_: RuntimeException) {
            state = state.copy(speaking = false, message = "Speech service interrupted. Continue with text.")
        }
    }

    override fun stop() {
        if (state.speaking) Log.i("RippleSpeech", "TTS interrupted")
        utterance++ // Ignore callbacks from interrupted playback.
        if (!closed) {
            try { engine.stop() } catch (_: RuntimeException) { /* Service disconnected. */ }
            state = state.copy(speaking = false)
        }
    }

    fun close() {
        stop()
        closed = true
        main.removeCallbacksAndMessages(null)
        engine.shutdown()
    }
}
