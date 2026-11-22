package ch.ripple.app.phone

import ch.ripple.app.keyMoment
import ch.ripple.core.*
import kotlin.test.*

class RoutesSessionTest {
    private var clock = 1_000L
    private fun session(store: MemoryStore = MemoryStore()) = RoutesSession(store) { clock++ }

    /** Spoken flow for the fictional Alex: question → only missing amounts → decision. */
    private fun alexMovingOut(s: RoutesSession): RoutesDecision {
        s.startExample()
        s.handle("What if I moved out?", Via.VOICE)
        s.handle("350", Via.VOICE)          // higher rent and living costs per month
        s.handle("500", Via.VOICE)          // moving costs, once
        s.handle("not sure", Via.VOICE)     // deposit
        s.handle("1300 to 1700", Via.VOICE)
        return checkNotNull(s.activeDecision) { s.turns.joinToString(" | ") }
    }

    private fun worst(d: RoutesDecision, nodeId: String = d.graph.activeNodeId) = keyMoment(d.graph.briefing(nodeId))

    @Test fun spokenDecisionMatchesTheEngineFixture() {
        val s = session()
        val d = alexMovingOut(s)
        assertNull(s.interview)
        assertEquals("Moving", d.title)
        // Same figures the engine's own Alex fixture asserts (EvolvingTwinTest): 300 with the deposit unknown.
        assertEquals(Chf.francs(300), worst(d).moneyLeft)
        assertEquals(3, worst(d).month)
        assertTrue(d.isAssumed(DecisionSpec.START), "the start month was not said, so it is an assumption, not a confirmed fact")
        assertTrue(s.turns.any { it is Turn.Finding && "April could be tight" in it.text }, s.turns.toString())
    }

    @Test fun correctionNeedsConfirmationThenChangesEngineOutput() {
        val s = session()
        alexMovingOut(s)
        s.handle("The deposit is 1450", Via.VOICE)
        val p = assertNotNull(s.pending)
        assertEquals(Chf.francs(550), p.after.moneyLeft)
        assertEquals(Chf.francs(300), worst(s.activeDecision!!).moneyLeft, "nothing saved before confirming")
        s.confirmPending()
        val d = s.activeDecision!!
        assertEquals(Chf.francs(550), worst(d).moneyLeft)
        assertEquals(InputState.Exact(Chf.francs(1_450)), d.confirmedHead.session.state("deposit"))
        assertEquals(2, d.graph.nodes.size, "earlier version kept")
    }

    @Test fun waitingTwoMonthsIsAWhatIfOnTheSelectedDecision() {
        val s = session()
        alexMovingOut(s)
        s.handle("What if I waited two months?", Via.VOICE)
        val d = s.activeDecision!!
        assertTrue(d.graph.active.hypothetical)
        assertEquals(Chf.francs(2), d.graph.active.session.hypotheticalValues[DecisionSpec.START])
        assertFalse(d.confirmedHead.hypothetical)
        assertEquals(Chf.francs(300), worst(d, d.confirmedHead.id).moneyLeft, "confirmed situation untouched")
    }

    @Test fun justTryingNeverBecomesAFact() {
        val s = session()
        alexMovingOut(s)
        s.handle("The deposit is 1600", Via.TEXT)
        s.tryPending()
        val d = s.activeDecision!!
        assertTrue(d.graph.active.hypothetical)
        assertEquals(Chf.francs(400), worst(d).moneyLeft)
        assertEquals(InputState.Range(Chf.francs(1_300), Chf.francs(1_700)), d.confirmedHead.session.state("deposit"))
    }

    @Test fun sessionSurvivesRestartAndReplaysToSameNumbers() {
        val store = MemoryStore()
        val s = session(store)
        alexMovingOut(s)
        s.handle("deposit is 1450", Via.VOICE); s.confirmPending()
        s.handle("What if I waited two months?", Via.VOICE)
        val before = s.activeDecision!!
        val text = assertNotNull(store.text)
        assertFalse("waited" in text, "conversation text is not stored")

        val reopened = session(MemoryStore(text))
        assertNull(reopened.loadProblem)
        val after = reopened.activeDecision!!
        assertEquals(before.graph.nodes.map { it.id to it.hypothetical }, after.graph.nodes.map { it.id to it.hypothetical })
        assertEquals(worst(before).moneyLeft, worst(after).moneyLeft)
        assertEquals(Chf.francs(550), worst(after, after.confirmedHead.id).moneyLeft)
        assertEquals("Alex", reopened.profile?.name)
        assertTrue(after.isAssumed(DecisionSpec.START))
    }

    @Test fun unreadableFileIsKeptAndNotOverwritten() {
        val store = MemoryStore("""{"format":"routes-session","version":99}""")
        val s = session(store)
        assertNotNull(s.loadProblem)
        s.startExample()
        assertEquals("""{"format":"routes-session","version":99}""", store.text, "no save over an unread file")
        s.startFresh()
        assertNotNull(store.setAsideText)
    }

    @Test fun profileEditReachesDecisionsAsNewVersion() {
        val s = session()
        alexMovingOut(s)
        s.setFact(ProfileField.INCOME, Amount.Known(Chf.francs(2_700)), Via.TAP)
        val d = s.activeDecision!!
        assertEquals(2, d.graph.nodes.size)
        assertEquals(InputState.Exact(Chf.francs(2_700)), d.confirmedHead.session.state(DecisionSpec.INCOME))
        assertEquals(Chf.francs(700), worst(d).moneyLeft, "+100 a month for the four months up to April")
        assertTrue(s.profile!!.edits.any { it.field == ProfileField.INCOME && it.before == Amount.Known(Chf.francs(2_600)) })
    }

    @Test fun unsupportedDecisionSaysSoAndCreatesNothing() {
        val s = session()
        s.startExample()
        s.handle("What if I adopted a cat?", Via.TEXT)
        assertTrue(s.decisions.isEmpty())
        assertTrue((s.turns.last() as Turn.Routes).text.startsWith("I can't model"))
    }

    @Test fun newPersonIsAskedForTheirSituationFirst() {
        val s = session()
        s.handle("What if I bought a car for 15000?", Via.TEXT)
        assertEquals(Ask.Profile(ProfileField.CASH), s.interview?.asking)
        s.handle("6000", Via.TEXT); s.handle("4000", Via.TEXT); s.handle("about 3500", Via.TEXT)
        assertEquals(Ask.Change("running"), s.interview?.asking) // the price was said, so it isn't asked
        s.handle("200 to 400", Via.TEXT)
        val d = assertNotNull(s.activeDecision)
        assertEquals("Buying a car", d.title)
        assertEquals(Amount.Estimate(Chf.francs(3_500)), s.profile!!.facts[ProfileField.SPENDING]?.amount)
    }

    @Test fun decisionExportRoundTrips() {
        val s = session()
        val d = alexMovingOut(s)
        val back = importDecision(d.export())
        assertEquals(worst(d).moneyLeft, worst(back).moneyLeft)
        assertEquals(d.spec, back.spec)
    }
}
