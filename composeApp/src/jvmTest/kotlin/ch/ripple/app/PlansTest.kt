package ch.ripple.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import ch.ripple.core.Amount
import ch.ripple.core.Chf
import ch.ripple.core.Correction
import ch.ripple.core.ExampleDecisions
import ch.ripple.core.Hypothesis
import ch.ripple.core.InputState
import ch.ripple.core.OPTION_LATER
import ch.ripple.core.briefing
import ch.ripple.core.correct
import ch.ripple.core.explore
import ch.ripple.core.select
import ch.ripple.core.startGraph
import ch.ripple.core.toSession
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PlansTest {
    private fun tempDir(): File = Files.createTempDirectory("routes-plans").toFile()

    @Test fun aPlanSurvivesARestartWithItsVersionsAndPlace() {
        val dir = tempDir()
        val spec = ExampleDecisions.samStudyAbroad.copy(fictional = false, changes = ExampleDecisions.samStudyAbroad.changes.map {
            if (it.id == "travel") it.copy(source = "FSO, 2022–2024", sourceRef = "market:x") else it
        })
        val g = startGraph(spec.toSession())
            .correct(Correction.AnswerInput("living_abroad", InputState.Exact(Chf.francs(400)))).graph
            .explore(Hypothesis.TryOption(OPTION_LATER)).graph
            .select("n1")
        LaptopPlans(dir).save(listOf(planOf("p1", spec, 1L, g, null)))

        // A new app start: the steps are replayed by the engine; no number was stored.
        val text = File(dir, "routes-laptop-plans.json").readText()
        assertTrue("closingCash" !in text && "worstMinimumCash" !in text)
        val back = LaptopPlans(dir).load().single()
        assertEquals(3, back.graph.nodes.size)
        assertEquals("n1", back.graph.activeNodeId)
        assertEquals(Chf.francs(1_500), back.graph.briefing("n1").evidence.worstStressTest.worstMinimumCash)
        assertEquals("FSO, 2022–2024", back.spec.changes.single { it.id == "travel" }.source)
        assertEquals(Amount.Estimate(Chf.francs(900)), back.spec.changes.single { it.id == "travel" }.amount)
    }

    @Test fun anUnreadableFileIsKeptAsideNotDeleted() {
        val dir = tempDir()
        File(dir, "routes-laptop-plans.json").writeText("{ not json")
        val plans = LaptopPlans(dir)
        assertTrue(plans.load().isEmpty())
        assertNotNull(plans.problem)
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("routes-laptop-plans.unreadable-") && it.readText() == "{ not json" })
    }

    @Test fun aChangedPlanAppearsOnTheStartScreenAndReopens() = runDesktopComposeUiTest(1320, 1300) {
        val dir = tempDir()
        setContent { RoutesApp(reduceMotion = true, plansDir = dir) }
        onNodeWithText("Sam studies abroad").performClick()
        waitUntil(timeoutMillis = 8_000) { onAllNodesWithText("Up to CHF 483", substring = true).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasContentDescription("Try a value for Extra living costs abroad")).onFirst().performSemanticsAction(SemanticsActions.SetProgress) { it(400f) }
        waitUntil(timeoutMillis = 8_000) { onAllNodesWithText("At CHF 400", substring = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("That's the real figure").performScrollTo().performClick()
        waitUntil(timeoutMillis = 8_000) { onAllNodesWithText("Updated because", substring = true).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithText("Routes", substring = false).performClick() // back to the start
        waitUntil(timeoutMillis = 8_000) { onAllNodesWithText("Your plans").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("2 versions", substring = true).assertExists()
        onNodeWithText("Studying abroad (fictional example)").performClick()
        waitUntil(timeoutMillis = 8_000) { onAllNodesWithText("every month stays at or above your CHF 1'000 cushion", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(File(dir, "routes-laptop-plans.json").exists())
    }
}
