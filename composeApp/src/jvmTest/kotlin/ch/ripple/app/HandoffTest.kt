package ch.ripple.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import ch.ripple.app.phone.RoutesDecision
import ch.ripple.app.phone.Step
import ch.ripple.app.phone.Via
import ch.ripple.app.phone.export
import ch.ripple.app.phone.importDecision
import ch.ripple.core.Chf
import ch.ripple.core.Correction
import ch.ripple.core.ExampleDecisions
import ch.ripple.core.Hypothesis
import ch.ripple.core.InputState
import ch.ripple.core.OPTION_LATER
import ch.ripple.core.briefing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A decision made on the phone opens on the laptop with the same versions, recomputed by the engine. */
@OptIn(ExperimentalTestApi::class)
class HandoffTest {
    /** As the phone would record it: Sam confirms living costs abroad of CHF 400, then tries going later. */
    private val phoneDecision = RoutesDecision(
        id = "d1", spec = ExampleDecisions.samStudyAbroad, createdAt = 0, via = Via.VOICE,
        steps = listOf(
            Step.Correct("n0", 1, Via.VOICE, Correction.AnswerInput("living_abroad", InputState.Exact(Chf.francs(400)))),
            Step.Explore("n1", 2, Via.TAP, Hypothesis.TryOption(OPTION_LATER)),
        ),
    )

    @Test fun exportImportReplaysTheSameVersions() {
        val text = phoneDecision.export()
        // Only what the person said and did travels; no computed results.
        assertTrue("\"worstMinimumCash\"" !in text && "closingCash" !in text)
        val laptop = importDecision(text)
        assertEquals(3, laptop.graph.nodes.size)
        assertEquals(Chf.francs(1_500), laptop.graph.briefing("n1").evidence.worstStressTest.worstMinimumCash)
        assertEquals(phoneDecision.graph.briefing("n2").evidence.worstStressTest.worstMinimumCash, laptop.graph.briefing("n2").evidence.worstStressTest.worstMinimumCash)
        assertTrue(laptop.graph.node("n2").hypothetical)
    }

    @Test fun theLaptopOpensAPhoneDecision() = runDesktopComposeUiTest(1320, 1300) {
        setContent { RoutesApp(reduceMotion = true) }
        onNodeWithText("Open a decision from your phone").performClick()
        onNode(hasSetTextAction()).performTextInput("not a decision")
        onNodeWithText("Open it", substring = true).performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("This isn't a Routes decision", substring = true).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithText("Open a decision from your phone").assertExists()
        onNode(hasSetTextAction()).performTextClearance()
        onNode(hasSetTextAction()).performTextInput(phoneDecision.export())
        onNodeWithText("Open it", substring = true).performClick()
        waitUntil(timeoutMillis = 8_000) { onAllNodesWithText("Opened from your phone: 3 versions", substring = true).fetchSemanticsNodes().isNotEmpty() }
        // The phone ended on a what-if: the laptop shows it as one, with the confirmed plan behind it.
        onNodeWithText("What-if, not saved to your plan", substring = true).assertExists()
        onNodeWithText("Back to your plan").performClick()
        waitUntil(timeoutMillis = 8_000) { onAllNodesWithText("every month stays at or above your CHF 1'000 cushion", substring = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Earlier versions").assertExists()
    }
}
