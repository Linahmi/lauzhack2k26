package ch.ripple.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import java.io.File
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AppearanceTest {
    @AfterTest fun reset() { RoutesTheme.palette = RoutesPalettes.Light }

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    /** WCAG AA: 4.5:1 for text, 3:1 for marks and controls. */
    @Test fun bothThemesMeetContrastForEveryMeaning() {
        listOf(RoutesPalettes.Light, RoutesPalettes.Dark).forEach { p ->
            val name = if (p.isDark) "dark" else "light"
            val text = mapOf(
                "ink on background" to (p.ink to p.background), "ink on surface" to (p.ink to p.surface),
                "secondary on background" to (p.ink2 to p.background), "secondary on surface" to (p.ink2 to p.surface),
                "link on background" to (p.linkRed to p.background), "link on surface" to (p.linkRed to p.surface),
                "bronze on background" to (p.bronze to p.background), "bronze on surface" to (p.bronze to p.surface),
                "finding on gold" to (p.ink to p.gold), "button text on red" to (p.onRed to p.red),
            )
            text.forEach { (what, pair) -> assertTrue(contrast(pair.first, pair.second) >= 4.5, "$name: $what is ${contrast(pair.first, pair.second)}") }
            val marks = mapOf(
                "red button against page" to (p.red to p.background), "below-cushion marks" to (p.bronzeBar to p.background),
                "selected route against surface" to (p.ink to p.surface), "reference marks" to (p.ink2 to p.background),
            )
            marks.forEach { (what, pair) -> assertTrue(contrast(pair.first, pair.second) >= 3.0, "$name: $what is ${contrast(pair.first, pair.second)}") }
            // No colour carries two meanings: action, vulnerability, finding and links stay distinct.
            val roles = listOf(p.red, p.bronze, p.bronzeBar, p.gold, p.linkRed, p.ink)
            assertEquals(roles.size, roles.toSet().size, "$name: a colour is reused across meanings")
        }
    }

    @Test fun choiceResolvesAndPersists() {
        assertEquals(RoutesPalettes.Dark, paletteFor(Appearance.SYSTEM, systemDark = true))
        assertEquals(RoutesPalettes.Light, paletteFor(Appearance.SYSTEM, systemDark = false))
        assertEquals(RoutesPalettes.Light, paletteFor(Appearance.LIGHT, systemDark = true))
        assertEquals(Appearance.SYSTEM, appearanceOf("nonsense"))

        val node = Preferences.userRoot().node("ch/ripple/routes-test-${System.nanoTime()}")
        try {
            AppearanceSetting(PrefsAppearanceStore(node)).choose(Appearance.DARK)
            // A new start reads the saved choice.
            assertEquals(Appearance.DARK, AppearanceSetting(PrefsAppearanceStore(node)).value)
        } finally { node.removeNode() }
    }

    /** The demo: switching theme mid-exploration keeps the decision, the try and every number. */
    @Test fun switchingThemeKeepsTheExplorationAndItsNumbers() = runDesktopComposeUiTest(1320, 1300) {
        var appearance by mutableStateOf(Appearance.LIGHT)
        val shots = System.getenv("ROUTES_SHOTS")?.let(::File)?.also { it.mkdirs() }
        fun shot(name: String) { waitForIdle(); shots?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "$name.png")) } }
        setContent { RoutesApp(reduceMotion = true, appearance = appearance) }
        onNodeWithText("Sam studies abroad").performClick()
        waitUntil(timeoutMillis = 8_000) { onAllNodesWithText("Up to CHF 483", substring = true).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasContentDescription("Try a value for Extra living costs abroad")).onFirst().performSemanticsAction(SemanticsActions.SetProgress) { it(400f) }
        waitUntil(timeoutMillis = 8_000) { onAllNodesWithText("At CHF 400: it keeps your cushion.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val before = onAllNodesWithText("The tightest is August: CHF 1'500 left", substring = true).fetchSemanticsNodes().size
        assertEquals(1, before)
        shot("theme-light")

        appearance = Appearance.DARK
        waitForIdle()
        assertTrue(RoutesTheme.palette.isDark)
        // Same try, same result, same finding: only colours changed.
        onNodeWithText("At CHF 400: it keeps your cushion.", substring = true).assertExists()
        onNodeWithText("The tightest is August: CHF 1'500 left", substring = true).assertExists()
        shot("theme-dark")

        appearance = Appearance.LIGHT
        waitForIdle()
        assertNotEquals(true, RoutesTheme.palette.isDark)
        onNodeWithText("At CHF 400: it keeps your cushion.", substring = true).assertExists()
    }
}
