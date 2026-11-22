package ch.ripple.app

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Drives the real desktop UI through semantics (text, roles, links, slider actions). Set
 * ROUTES_SHOTS=<dir> to also save screenshots of each step.
 */
@OptIn(ExperimentalTestApi::class)
class RoutesUiTest {
    private val shots = System.getenv("ROUTES_SHOTS")?.let(::File)?.also { it.mkdirs() }

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        shots?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "$name.png")) }
    }

    private fun ComposeUiTest.waitText(text: String) =
        waitUntil(timeoutMillis = 8_000) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.gone(text: String) =
        waitUntil(timeoutMillis = 8_000) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty() }

    private fun ComposeUiTest.absent(text: String) = check(onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty()) { "unexpected: $text" }

    private fun SemanticsNodeInteraction.click() = performScrollTo().performClick()
    private fun ComposeUiTest.month(name: String) = onNode(hasContentDescription("$name: ", substring = true))
    private fun ComposeUiTest.slider(name: String) = onAllNodes(hasContentDescription("Try a value for $name")).onFirst()

    @Test fun askingLeadsToOneQuestionAtATime() = runDesktopComposeUiTest(1320, 1300) {
        setContent { RoutesApp(reduceMotion = true) }
        shot("00-ask")
        onNode(hasSetTextAction()).performTextInput("What if I studied abroad in Amsterdam?")
        onNodeWithText("Explore", substring = true).performClick()
        waitText("How much money do you have now?")
        waitText("The local AI isn't running")
        absent("How much is")
        onNodeWithText("Sam's situation").performClick()
        waitText("How much is “Pay you'd give up while away”?")
        onNode(hasSetTextAction()).performTextInput("1200")
        onNodeWithText("Next", substring = true).performClick()
        waitText("How much is “Extra living costs abroad”?")
        waitText("For Amsterdam, enter your own estimate or a range.")
    }

    /** The seven states, one investigation area at a time. */
    @Test fun samExploresOneThingAtATime() = runDesktopComposeUiTest(1320, 1300) {
        setContent { RoutesApp(reduceMotion = true) }
        onNodeWithText("Sam studies abroad").performClick()
        // 1. Default: decision, finding, route, and the strongest tested way.
        waitText("you'd run out of money in August")
        waitText("How high could extra living costs abroad be?")
        waitText("Up to CHF 483")
        absent("What led here")
        shot("01-default")

        // 2. Find a way: everything else Routes tested, in the same area.
        onNodeWithText("Other things Routes tested", substring = true).click()
        waitText("What Routes tested")
        absent("Up to CHF 483")
        shot("02-find-a-way")
        onNodeWithText("‹ Back").click()

        // 3. A month replaces it; the way is no longer shown.
        month("August").performClick()
        waitText("What led here")
        absent("How high could extra living costs abroad be?")
        shot("03-month")

        // 4. A cause opens its figure in the same area; trying a value recomputes.
        onNodeWithText("Extra living costs abroad: CHF 700", substring = true).click()
        waitText("‹ August")
        slider("Extra living costs abroad").performSemanticsAction(SemanticsActions.SetProgress) { it(400f) }
        waitText("At CHF 400: it keeps your cushion.")
        absent("What led here")
        shot("04-assumption")

        // 5. Back to the month, then its amount opens the calculation.
        onNodeWithText("‹ August").click()
        waitText("What led here")
        onNodeWithText("Left at the end:", substring = true).performFirstLinkClick()
        waitText("How August adds up")
        absent("What led here")
        shot("05-calculation")

        // Clicking the month again returns to the default.
        month("August").performClick()
        waitText("How high could extra living costs abroad be?")
    }

    @Test fun samComparesARouteAndConfirmsACorrection() = runDesktopComposeUiTest(1320, 1300) {
        setContent { RoutesApp(reduceMotion = true) }
        onNodeWithText("Sam studies abroad").performClick()
        waitText("Other things Routes tested")
        onNodeWithText("Other things Routes tested", substring = true).click()
        onNodeWithText("What about the same, 3 months later?").click()
        waitText("Yes: it keeps your cushion in every tested case within the 12 months shown.")
        onNodeWithText("Compare this route").click()
        // 6. Alternative comparison.
        waitText("What-if, not saved to your plan")
        waitText("Comparing a route")
        shot("06-alternative")
        onNodeWithText("Back to your plan").click()
        gone("What-if, not saved to your plan")

        // 7. Confirmed correction.
        waitText("Up to CHF 483")
        slider("Extra living costs abroad").performSemanticsAction(SemanticsActions.SetProgress) { it(400f) }
        waitText("At CHF 400")
        onNodeWithText("That's the real figure").click()
        waitText("Updated because you confirmed extra living costs abroad is CHF 400")
        waitText("every month stays at or above your CHF 1'000 cushion")
        shot("07-confirmed")
    }

    @Test fun noorFindsTheStartBoundary() = runDesktopComposeUiTest(1320, 1300) {
        setContent { RoutesApp(reduceMotion = true) }
        onNodeWithText("Noor works fewer hours").performClick()
        waitText("you'd run out of money in September")
        waitText("How much would childcare you'd save need to be?")
        waitText("From CHF 523 up")
        onNodeWithText("Other things Routes tested", substring = true).click()
        onNodeWithText("Would starting later change this?").click()
        waitText("Starting in August or later keeps your cushion within the 12 months shown")
        slider("When it starts").performSemanticsAction(SemanticsActions.SetProgress) { it(7f) }
        waitText("At August: it keeps your cushion.")
        onNodeWithText("Plan it for August").assertExists()
        shot("20-noor")
    }

    @Test fun alexStillWorksAsTheRegressionExample() = runDesktopComposeUiTest(1320, 1300) {
        setContent { RoutesApp(reduceMotion = true) }
        onNodeWithText("Alex moves out").performClick()
        waitText("April could be tight")
        waitText("How high could the deposit be?")
        waitText("Up to CHF 1'500")
        shot("30-alex")
        onNodeWithText("All figures", substring = true).click()
        onNodeWithText("Your cushion").click()
        waitText("your own reference point, not a rule")
        waitText("‹ All figures")
    }

    @Test fun aCustomDecisionGetsTheSameExperience() = runDesktopComposeUiTest(1320, 1500) {
        setContent { RoutesApp(reduceMotion = true) }
        onNode(hasSetTextAction()).performTextInput("What if I buy a car for 12000?")
        onNodeWithText("Explore", substring = true).performClick()
        waitText("How much money do you have now?")
        onNodeWithText("Noor's situation").performClick()
        waitText("How much is “Purchase price”?")
        onNodeWithText("Next", substring = true).performClick()
        waitText("How much is “Running costs (insurance, fuel, parking)”?")
        onNode(hasSetTextAction()).performTextInput("300")
        onNodeWithText("Next", substring = true).performClick()
        onNodeWithText("Next", substring = true).performClick()
        waitText("Is there an amount you'd like to always keep?")
        onNodeWithText("Next", substring = true).performClick()
        onNodeWithText("Show my routes", substring = true).click()
        waitText("you'd run out of money")
        waitText("How high could purchase price be?")
        shot("40-custom")
    }

    @Test fun theImportantNumbersAreThemselvesInteractive() = runDesktopComposeUiTest(1320, 1300) {
        setContent { RoutesApp(reduceMotion = true) }
        onNodeWithText("Sam studies abroad").performClick()
        waitText("you'd run out of money in August")
        onNodeWithText("you'd run out of money in August", substring = true).performFirstLinkClick()
        waitText("What led here")
        onNodeWithText("your cushion CHF 1'000").click()
        waitText("your own reference point, not a rule")
        onNodeWithText("your cushion CHF 1'000").click()
        waitText("Up to CHF 483")
        onNodeWithText("Up to CHF 483", substring = true).performFirstLinkClick()
        waitText("At CHF 483: it keeps your cushion.")
        waitText("The tightest is August: CHF 1'002 left")
        onNodeWithText("Other things Routes tested", substring = true).click()
        onNodeWithText("What about the same, 3 months later?").click()
        onNodeWithText("Its lowest month", substring = true).performFirstLinkClick()
        waitText("What-if, not saved to your plan")
    }
}
