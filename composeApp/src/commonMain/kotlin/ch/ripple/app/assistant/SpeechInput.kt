package ch.ripple.app.assistant

enum class SpeechCapability { CHECKING, READY, DOWNLOADABLE, PENDING_DOWNLOAD, UNSUPPORTED, UNKNOWN, MISSING }
enum class SpeechRoute { ON_DEVICE, SYSTEM }

data class SpeechInputState(
    val language: String = "en",
    val onDevice: SpeechCapability = SpeechCapability.MISSING,
    val system: SpeechCapability = SpeechCapability.MISSING,
    val route: SpeechRoute = SpeechRoute.ON_DEVICE,
    val systemConsent: Boolean = false,
    val summary: String = "Speech recognition is Android-only. You can type instead.",
    val diagnostics: String = "",
) {
    val active: SpeechCapability get() = if (route == SpeechRoute.ON_DEVICE) onDevice else system
    val canRecord: Boolean get() = active in setOf(SpeechCapability.READY, SpeechCapability.UNKNOWN) &&
        (route != SpeechRoute.SYSTEM || systemConsent)
    val canDownload: Boolean get() = onDevice == SpeechCapability.DOWNLOADABLE
}

/** Input only. Neither discovery nor model downloads record audio. Callbacks use the UI thread. */
interface SpeechInput {
    val available: Boolean
    val notice: String
    val state: SpeechInputState get() = SpeechInputState()
    fun setLanguage(language: String) = Unit
    fun refreshSupport(language: String) = Unit
    fun chooseRoute(route: SpeechRoute, consentToSystemService: Boolean = false) = Unit
    fun downloadModel(consented: Boolean) = Unit
    fun start(onEvent: (SpeechEvent) -> Unit)
    fun stop()
    fun cancel()
}

sealed interface SpeechEvent {
    data object Preparing : SpeechEvent
    data object Listening : SpeechEvent
    data object Processing : SpeechEvent
    data class Partial(val text: String) : SpeechEvent
    data class Result(val text: String) : SpeechEvent
    data class Failure(val message: String) : SpeechEvent
}

object TypedInputOnly : SpeechInput {
    override val available = false
    override val notice = "Microphone recognition is Android-only for now. You can type and review your request here."
    override fun start(onEvent: (SpeechEvent) -> Unit) = Unit
    override fun stop() = Unit
    override fun cancel() = Unit
}
