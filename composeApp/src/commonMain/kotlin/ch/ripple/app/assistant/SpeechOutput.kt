package ch.ripple.app.assistant

data class SpeechOutputState(
    val languages: Set<String> = emptySet(),
    val speaking: Boolean = false,
    val message: String = "Preparing installed voices…",
)

/** Native speech output only. The shared UI chooses the exact, visible response. */
interface SpeechOutput {
    val state: SpeechOutputState
    fun speak(text: String, language: String)
    fun stop()
}

object SilentOutput : SpeechOutput {
    override val state = SpeechOutputState(message = "Read-aloud is available on Android. All responses remain visible here.")
    override fun speak(text: String, language: String) = Unit
    override fun stop() = Unit
}

