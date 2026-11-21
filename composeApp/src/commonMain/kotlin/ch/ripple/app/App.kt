package ch.ripple.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import ch.ripple.app.assistant.AssistantScreen
import ch.ripple.app.assistant.SpeechInput
import ch.ripple.app.assistant.TypedInputOnly
import ch.ripple.app.assistant.SpeechOutput
import ch.ripple.app.assistant.SilentOutput
import ch.ripple.app.assistant.FinancialTwinController
import ch.ripple.app.components.MotionPreference
import ch.ripple.app.results.ComparisonScreen
import ch.ripple.app.theme.RippleTheme
import ch.ripple.app.welcome.WelcomeScreen
import ch.ripple.app.welcome.UbsDemoScreen

@Composable
fun App(
    speechInput: SpeechInput = TypedInputOnly,
    speechOutput: SpeechOutput = SilentOutput,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit = { _, _ -> },
) {
    var destination by rememberSaveable { mutableStateOf("welcome") }
    var reduceMotion by rememberSaveable { mutableStateOf(false) }
    var showDisplayOptions by rememberSaveable { mutableStateOf(false) }
    var confirmedDeposit by rememberSaveable { mutableStateOf<String?>(null) }
    val twin = remember { FinancialTwinController { confirmedDeposit = it } }
    LaunchedEffect(destination) {
        if (destination == "assistant") twin.initialize(confirmedDeposit)
    }
    val screens = rememberSaveableStateHolder()
    val back = { destination = when (destination) {
        "assistant" -> "ubs-demo"
        "comparison" -> "assistant"
        else -> "welcome"
    } }
    val openAssistant = { destination = "assistant" }
    platformBackHandler(destination != "welcome", back)
    RippleTheme {
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
            .onPreviewKeyEvent {
                if (it.key == Key.Escape && it.type == KeyEventType.KeyUp && destination != "welcome") {
                    back(); true
                } else false
            }) {
            Column {
                screens.SaveableStateProvider(destination) { when (destination) {
                    "assistant" -> AssistantScreen(speechInput, reduceMotion, Modifier.weight(1f),
                        speechOutput = speechOutput, twin = twin, onBack = back)
                    "ubs-demo" -> UbsDemoScreen(Modifier.weight(1f), onBack = back, onContinue = openAssistant)
                    "comparison" -> ComparisonScreen(Modifier.weight(1f), onBack = back, onAssistant = openAssistant)
                    else -> WelcomeScreen(Modifier.weight(1f), reduceMotion,
                        onGetStarted = { destination = "ubs-demo" }, onDisplayOptions = { showDisplayOptions = true })
                } }
            }
        }
        if (showDisplayOptions) AlertDialog(onDismissRequest = { showDisplayOptions = false },
            title = { Text("Display options") },
            text = { MotionPreference(reduceMotion) { reduceMotion = it } },
            confirmButton = { TextButton({ showDisplayOptions = false }) { Text("Done") } })
    }
}
