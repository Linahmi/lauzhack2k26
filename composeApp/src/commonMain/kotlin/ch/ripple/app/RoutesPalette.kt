package ch.ripple.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/*
 * One semantic colour system for Routes, shared by the phone (Tk) and the laptop (Rt). Each role
 * keeps its meaning in both themes: red = the main action, bronze = below the cushion (financial
 * vulnerability), gold = the one finding / a confirmed fact, grey = routes not taken. Dark is
 * designed for dark, not inverted: a lifted surface, a softer red for text, a lighter bronze and
 * a deep gold, all checked for WCAG AA (RoutesPaletteTest).
 */
data class RoutesPalette(
    val isDark: Boolean,
    /** Page background. */
    val background: Color,
    /** Quiet surfaces: cards, the investigation area, chips. */
    val surface: Color,
    /** Primary text and the selected route. */
    val ink: Color,
    /** Secondary text. */
    val ink2: Color,
    /** Hairlines and borders. */
    val line: Color,
    /** Slider tracks and empty bars. */
    val track: Color,
    /** Fill of primary action buttons and the Routes mark. */
    val red: Color,
    /** Text on [red]. */
    val onRed: Color,
    /** Underlined contextual actions (text). */
    val linkRed: Color,
    /** Money below the cushion: text. */
    val bronze: Color,
    /** Money below the cushion: marks and bars. */
    val bronzeBar: Color,
    /** The one thing that matters; confirmed facts (behind [ink] text). */
    val gold: Color,
    /** Routes not taken, reference marks. */
    val routeGrey: Color,
    /** Behind dialogs and sheets. */
    val scrim: Color,
)

object RoutesPalettes {
    /** Routes × UBS light tokens (docs/DECISIONS.md, 2026-11-22), unchanged. */
    val Light = RoutesPalette(
        isDark = false,
        background = Color(0xFFFFFFFF), surface = Color(0xFFF9F9F7),
        ink = Color(0xFF1C1C1C), ink2 = Color(0xFF5A5D5C),
        line = Color(0xFFE0DED6), track = Color(0xFFECEAE3),
        red = Color(0xFFE60000), onRed = Color(0xFFFFFFFF), linkRed = Color(0xFFDA0000),
        bronze = Color(0xFF86671D), bronzeBar = Color(0xFFAF8626),
        gold = Color(0xFFF1E2BC), routeGrey = Color(0xFFBDBBB0),
        scrim = Color(0x521C1C1C),
    )

    /** Designed for dark: warm near-black, lifted surfaces, AA-checked red, bronze and gold. */
    val Dark = RoutesPalette(
        isDark = true,
        background = Color(0xFF171819), surface = Color(0xFF232527),
        ink = Color(0xFFF2F1ED), ink2 = Color(0xFFA8ABA9),
        line = Color(0xFF383A3C), track = Color(0xFF303235),
        red = Color(0xFFD8343A), onRed = Color(0xFFFFFFFF), linkRed = Color(0xFFFF6B6B),
        bronze = Color(0xFFD9B46A), bronzeBar = Color(0xFFC9A14E),
        gold = Color(0xFF4A3E22), routeGrey = Color(0xFF6E716F),
        scrim = Color(0x99000000),
    )
}

/** The person's choice. SYSTEM follows the operating system. */
enum class Appearance { SYSTEM, LIGHT, DARK }

fun paletteFor(appearance: Appearance, systemDark: Boolean): RoutesPalette = when (appearance) {
    Appearance.LIGHT -> RoutesPalettes.Light
    Appearance.DARK -> RoutesPalettes.Dark
    Appearance.SYSTEM -> if (systemDark) RoutesPalettes.Dark else RoutesPalettes.Light
}

/** Where a platform keeps the choice across restarts. */
interface AppearanceStore {
    fun load(): Appearance
    fun save(appearance: Appearance)
}

/** Reads a stored value leniently: anything unknown means SYSTEM. */
fun appearanceOf(stored: String?): Appearance = Appearance.entries.firstOrNull { it.name == stored } ?: Appearance.SYSTEM

/**
 * The palette in use. Token objects (Rt, Tk) read it, so composition and drawing both follow a
 * change at once; only colours change, never state, so a decision being explored stays as it is.
 */
object RoutesTheme {
    var palette: RoutesPalette by mutableStateOf(RoutesPalettes.Light)
}

/** Applies [appearance] (resolving SYSTEM against the OS) to everything below it. */
@Composable
fun ApplyAppearance(appearance: Appearance) {
    val p = paletteFor(appearance, isSystemInDarkTheme())
    if (RoutesTheme.palette != p) SideEffect { RoutesTheme.palette = p }
}

/** For tests and platforms without storage. */
class MemoryAppearanceStore(private var value: Appearance = Appearance.SYSTEM) : AppearanceStore {
    override fun load() = value
    override fun save(appearance: Appearance) { value = appearance }
}

/** The person's appearance choice, observable by the UI and saved on every change. */
class AppearanceSetting(private val store: AppearanceStore) {
    var value: Appearance by mutableStateOf(runCatching { store.load() }.getOrDefault(Appearance.SYSTEM))
        private set
    fun choose(appearance: Appearance) { value = appearance; runCatching { store.save(appearance) } }
}
