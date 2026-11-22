package ch.ripple.app.phone

import ch.ripple.app.assistant.*
import ch.ripple.app.keyMoment
import ch.ripple.core.Chf
import ch.ripple.core.briefing
import kotlin.test.*

class RoutesVoiceBridgeTest {
    private class FakeLive : LiveConversation {
        override val available = true
        override var state = LiveVoiceState(connected = true, phase = LivePhase.Listening)
        override var onTranscript: ((String) -> Unit)? = null
        val readouts = mutableListOf<String>()
        fun transcript(text: String) { onTranscript?.invoke(text) }
        override suspend fun unavailableReason(): String? = null
        override fun speak(text: String) { readouts += text }
        override fun start(verifiedContext: String) = Unit
        override fun mute(muted: Boolean) = Unit
        override fun muteSpeaker(muted: Boolean) = Unit
        override fun stopAudio() = Unit
        override fun replay() = Unit
        override fun end() = Unit
    }

    private var clock = 1_000L
    private val store = MemoryStore()
    private val session = RoutesSession(store) { clock++ }
    private val live = FakeLive()
    private fun moving(): RoutesVoiceBridge {
        session.startExample()
        val bridge = RoutesVoiceBridge(session, live).also { it.attach() }
        listOf("What if I moved out?", "350", "500", "not sure", "1300 to 1700").forEach(live::transcript)
        return bridge
    }

    @Test fun finalTranscriptUsesSessionAndSpeaksOnlyItsNewEngineText() {
        val bridge = moving()
        val decision = assertNotNull(session.activeDecision)
        assertEquals(Chf.francs(300), keyMoment(decision.graph.briefing()).moneyLeft)
        assertTrue(live.readouts.last().contains("April could be tight"))
        assertTrue(live.readouts.last().contains("CHF 300"))
        assertTrue(session.turns.filterIsInstance<Turn.User>().all { it.via == Via.VOICE })
        val count = live.readouts.size
        bridge.speakNewTurns() // Compose observes the same changes after the callback.
        assertEquals(count, live.readouts.size, "no double readout")
        assertFalse(store.text.orEmpty().contains("1300 to 1700"), "no conversation log is persisted")
    }

    @Test fun proposalIsSpokenButOnlyScreenConfirmationSavesAndSpeaksTheChange() {
        val bridge = moving()
        live.transcript("the deposit is 1450")
        val proposal = assertNotNull(session.pending)
        assertEquals(Chf.francs(550), proposal.after.moneyLeft)
        assertTrue(live.readouts.last().contains(proposal.summary))
        assertTrue(live.readouts.last().contains("Tap 'That's real, save'"))
        live.transcript("yes")
        assertEquals(proposal, session.pending)
        assertEquals(Chf.francs(300), keyMoment(session.activeDecision!!.graph.briefing()).moneyLeft)
        session.confirmPending() // The real screen tap's existing action.
        bridge.speakNewTurns()
        assertEquals(Chf.francs(550), keyMoment(session.activeDecision!!.graph.briefing()).moneyLeft)
        assertTrue(live.readouts.last().contains("300"))
        assertTrue(live.readouts.last().contains("550"))
        assertEquals(Via.VOICE, session.activeDecision!!.steps.last().via)
    }

    @Test fun voiceWhatIfDoesNotChangeConfirmedVersion() {
        moving()
        val confirmed = session.activeDecision!!.confirmedHead
        live.transcript("what if I waited two months?")
        val d = session.activeDecision!!
        assertTrue(d.graph.active.hypothetical)
        assertEquals(confirmed.id, d.confirmedHead.id)
        assertEquals(confirmed.session, d.confirmedHead.session)
        assertTrue(live.readouts.last().contains("What-if, not saved as fact"))
    }

    @Test fun detachedBridgeCannotMutateSessionAndHistoryIsNotReadOnAttach() {
        val bridge = moving()
        bridge.detach()
        val turns = session.turns
        live.transcript("deposit is 9999")
        assertEquals(turns, session.turns)
        val count = live.readouts.size
        RoutesVoiceBridge(session, live).also { it.attach(); it.speakNewTurns() }
        assertEquals(count, live.readouts.size)
    }
}
