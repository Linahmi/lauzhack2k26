package ch.ripple.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension

fun main() = application {
    // Maximized: a fixed 1320×900 dp window overflows the screen at 150 % display scaling.
    val state = rememberWindowState(placement = WindowPlacement.Maximized, width = 1320.dp, height = 900.dp)
    // Desktop has no portable "reduce motion" signal: the View menu (or ROUTES_REDUCE_MOTION=1) turns transitions off.
    var reduceMotion by remember { mutableStateOf(System.getenv("ROUTES_REDUCE_MOTION") == "1") }
    var esc by remember { mutableStateOf(0) }
    Window(
        onCloseRequest = ::exitApplication, state = state, title = "Routes",
        onPreviewKeyEvent = { if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { esc++; true } else false },
    ) {
        LaunchedEffect(Unit) { window.minimumSize = Dimension(980, 700) }
        MenuBar { Menu("View") { CheckboxItem("Reduce motion", reduceMotion, onCheckedChange = { reduceMotion = it }) } }
        RoutesApp(reduceMotion, esc)
    }
}
