package ch.ripple.app.phone

import ch.ripple.app.assistant.LiveConversation

/** Main-thread adapter: Gemini supplies words, never commands, facts or financial results. */
class RoutesVoiceBridge(private val session: RoutesSession, private val live: LiveConversation) {
    private var seen: List<Turn> = session.turns
    private var pending: Pending? = session.pending
    private val listener: (String) -> Unit = ::handleTranscript

    fun attach() { live.onTranscript = listener }
    fun detach() { if (live.onTranscript === listener) live.onTranscript = null }

    fun handleTranscript(text: String) {
        // Preserve the proposal when a person tries to confirm it by voice.
        if (session.pending != null && text.trim().lowercase().trimEnd('.', '!', '?') in
            setOf("yes", "yeah", "yep", "okay", "ok", "save", "save it", "that's real", "confirm")) {
            live.speak("Tap 'That's real, save' on the screen to save this change.")
            return
        }
        session.handle(text, Via.VOICE)
        speakNewTurns()
    }

    /** Also observe typed requests and screen confirmations, without replaying history. */
    fun speakNewTurns() {
        val turns = session.turns
        val extendsSeen = turns.size >= seen.size && turns.take(seen.size) == seen
        val fresh = if (extendsSeen) turns.drop(seen.size) else emptyList()
        seen = turns
        val proposal = session.pending
        val text = buildList {
            fresh.forEach { turn -> when (turn) {
                is Turn.Routes -> add(turn.text)
                is Turn.Finding -> add(turn.text)
                is Turn.User -> Unit
            } }
            if (proposal != null && proposal != pending)
                add("Should I save: ${proposal.summary}? Tap 'That's real, save' to save. If this is real: ${changeText(proposal.before, proposal.after)}")
        }.joinToString("\n")
        pending = proposal
        if (text.isNotBlank() && live.state.connected) live.speak(text)
    }
}
