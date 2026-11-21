package ch.ripple.app.assistant

enum class LivePhase { Ready, Connecting, Listening, Thinking, Speaking, Muted, Error }

data class LiveVoiceState(
    val phase: LivePhase = LivePhase.Ready,
    val connected: Boolean = false,
    val muted: Boolean = false,
    val inputLevel: Float = 0f,
    val outputLevel: Float = 0f,
    val heard: String = "",
    val reply: String = "",
    val error: String? = null,
    val model: String = "",
    val canReplay: Boolean = false,
    val permissionDenied: Boolean = false,
)

/** Native audio session only. No speech-to-text -> text LLM -> TTS chain. */
interface LiveConversation {
    val available: Boolean
    val state: LiveVoiceState
    fun start(verifiedContext: String)
    fun mute(muted: Boolean)
    fun stopAudio()
    fun replay()
    fun end()
    fun openMicrophoneSettings() = Unit
}

object NoLiveVoice : LiveConversation {
    override val available = false
    override val state = LiveVoiceState()
    override fun start(verifiedContext: String) = Unit
    override fun mute(muted: Boolean) = Unit
    override fun stopAudio() = Unit
    override fun replay() = Unit
    override fun end() = Unit
}

/** Proposed frontend boundary, not an Ollama API. Lina must agree transport/revisions first. */
enum class FinancialDestination { FINANCES, FUTURE }
data class ConversationRequest(val sessionReference: String, val revision: Long, val confirmedText: String)
data class ProposedFinancialInput(val field: String, val amountText: String, val currency: String, val confirmed: Boolean = false)
data class ConversationResult(
    val sessionReference: String,
    val revision: Long,
    val assistantMessage: String,
    val destination: FinancialDestination? = null,
    val needsClarification: Boolean = true,
    val nextQuestion: String? = null,
    val proposedInputs: List<ProposedFinancialInput> = emptyList(),
    val warnings: List<String> = emptyList(),
)
interface TextConversationBoundary { suspend fun send(request: ConversationRequest): ConversationResult }
