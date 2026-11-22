package ch.ripple.app

import ch.ripple.core.Chf
import ch.ripple.core.Correction
import ch.ripple.core.InputState
import ch.ripple.core.SampleData
import ch.ripple.core.briefing
import ch.ripple.core.correct
import ch.ripple.core.startGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoutesJourneyTest {
    private val graph = startGraph(SampleData.alexEvolvingSession)

    @Test
    fun aprilIsTheKeyMomentWithItsCauses() {
        val b = graph.briefing()
        val k = keyMoment(b)
        assertEquals(3, k.month)
        assertEquals(Chf.francs(300), k.moneyLeft)
        assertEquals(Chf.francs(500), k.cushion)
        assertEquals(listOf(0, 1), k.lowerPayMonths)
        assertEquals(listOf(3), k.monthsBelowCushion)

        val c = causes(graph.active.session, b, k.month)
        assertTrue(c.any { it is Cause.LowerPay && it.months == listOf(0, 1) && it.reduction == Chf.francs(800) })
        assertTrue(c.any { it is Cause.SetAside && it.month == 0 && it.amount == Chf.francs(1_700) })
        assertTrue(c.any { it is Cause.OneOff && it.month == 3 && it.amount == Chf.francs(900) })
        val deposit = c.filterIsInstance<Cause.Input>().single { it.input.id == "deposit" }
        assertEquals(InputState.Unknown, deposit.state)
        assertEquals(Chf.francs(1_700), deposit.testedValue)
        assertEquals(Chf.francs(1_500), deposit.holdsUpTo)

        val t = tested(b)
        assertEquals(22, t.cases)
        assertEquals(6, t.dipping)
    }

    @Test
    fun tryingIsHypotheticalAndConfirmingRecomputesThroughTheGraph() {
        val session = graph.active.session
        assertEquals(Chf.francs(550), preview(session, "deposit", Chf.francs(1_450)).moneyLeft)
        assertEquals(InputState.Unknown, session.state("deposit")) // trying stored nothing
        assertEquals(1, graph.nodes.size)

        val update = graph.correct(Correction.AnswerInput("deposit", InputState.Exact(Chf.francs(1_450))))
        val after = keyMoment(update.graph.briefing(update.node.id))
        assertEquals(Chf.francs(550), after.moneyLeft)
        assertFalse(after.belowCushion)
        assertEquals(2, update.graph.nodes.size) // the earlier version is kept
        assertEquals(Chf.francs(300), keyMoment(update.graph.briefing(update.graph.nodes.first().id)).moneyLeft)
    }

    @Test
    fun followsOtherValuesNotJustTheDemo() {
        val k = preview(graph.active.session, "deposit", Chf.francs(1_300))
        assertEquals(3, k.month)
        assertEquals(Chf.francs(700), k.moneyLeft) // 2,000 - deposit in the hardest case
        assertTrue(k.monthsBelowCushion.isEmpty())
    }
}
