package ch.ripple.app.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ch.ripple.app.assistant.SpeechCapability
import ch.ripple.app.assistant.SpeechEvent
import ch.ripple.app.assistant.SpeechRoute
import ch.ripple.app.assistant.SpeechInput
import ch.ripple.app.assistant.TypedInputOnly

enum class Space(val label: String) { EXPLORE("Explore"), ROUTES("My Routes"), TWIN("My Twin") }

/** Platform pieces the shared phone UI needs; everything else is commonMain. */
class PhonePlatform(
    val speech: SpeechInput = TypedInputOnly,
    /** Local clock time for history lines ("9:42"). */
    val timeText: (Long) -> String = { "" },
    /** System back: (enabled, onBack). */
    val backHandler: @Composable (Boolean, () -> Unit) -> Unit = { _, _ -> },
)

class PhoneNav {
    var space by mutableStateOf(Space.EXPLORE)
    /** Decision open in My Routes (null = the list). */
    var openDecision by mutableStateOf<String?>(null)
}

/** The Android entry point: three spaces over one shared [RoutesSession]. */
@Composable
fun PhoneApp(session: RoutesSession, platform: PhonePlatform) {
    val nav = remember { PhoneNav() }
    val voice = remember { VoiceState(platform.speech) }
    MaterialTheme(colorScheme = lightColorScheme(primary = Tk.Ink, background = Tk.White, surface = Tk.White)) {
        Column(Modifier.fillMaxSize().background(Tk.White).safeDrawingPadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (nav.space) {
                    Space.EXPLORE -> ExploreScreen(session, nav, voice, platform)
                    Space.ROUTES -> RoutesScreen(session, nav, voice, platform)
                    Space.TWIN -> TwinScreen(session, nav, platform)
                }
            }
            NavBar(nav)
        }
    }
}

@Composable
private fun NavBar(nav: PhoneNav) {
    Divider()
    Row(Modifier.fillMaxWidth().height(60.dp).background(Tk.White)) {
        Space.entries.forEach { s ->
            val on = nav.space == s
            Column(
                Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Tab) { nav.space = s; if (s == Space.ROUTES && on) nav.openDecision = null }
                    .semantics { contentDescription = s.label + if (on) ", selected" else "" },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.width(36.dp).height(2.dp).background(if (on) Tk.Ink else Color.Transparent))
                Spacer(Modifier.height(6.dp))
                NavIcon(s, if (on) Tk.Ink else Tk.Ink2)
                Text(s.label, style = Ty.Small.copy(color = if (on) Tk.Ink else Tk.Ink2, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
            }
        }
    }
}

@Composable
private fun NavIcon(s: Space, c: Color) = Canvas(Modifier.size(20.dp)) {
    val u = size.width / 24f
    val st = Stroke(2f * u, cap = StrokeCap.Round)
    when (s) {
        Space.EXPLORE -> { // microphone
            drawRoundRect(c, Offset(9 * u, 3 * u), androidx.compose.ui.geometry.Size(6 * u, 11 * u), androidx.compose.ui.geometry.CornerRadius(3 * u), style = st)
            drawArc(c, 0f, 180f, false, Offset(5 * u, 4 * u), androidx.compose.ui.geometry.Size(14 * u, 14 * u), style = st)
            drawLine(c, Offset(12 * u, 18 * u), Offset(12 * u, 21 * u), 2f * u)
        }
        Space.ROUTES -> { // the fork
            drawLine(c, Offset(7 * u, 21 * u), Offset(7 * u, 13 * u), 2f * u, StrokeCap.Round)
            drawLine(c, Offset(7 * u, 13 * u), Offset(18 * u, 7 * u), 2f * u, StrokeCap.Round)
            drawLine(c, Offset(7 * u, 13 * u), Offset(4 * u, 5 * u), 2f * u, StrokeCap.Round)
        }
        Space.TWIN -> { // person
            drawCircle(c, 4 * u, Offset(12 * u, 8 * u), style = st)
            drawArc(c, 180f, 180f, false, Offset(4 * u, 15 * u), androidx.compose.ui.geometry.Size(16 * u, 12 * u), style = st)
        }
    }
}

// ------------------------------------------------------------ voice + text input, shared by Explore and My Routes

/** Speech-to-text only (Android's recognizer). No audio is stored; the words go to the same parser as typing. */
class VoiceState(val speech: SpeechInput) {
    /** On-device recognition isn't ready but Android's system service is: offer it, with its privacy note. */
    val canUseSystem: Boolean get() = speech.state.route == SpeechRoute.ON_DEVICE && !speech.state.canRecord &&
        speech.state.system in setOf(SpeechCapability.READY, SpeechCapability.UNKNOWN)
    fun useSystem() { speech.chooseRoute(SpeechRoute.SYSTEM, consentToSystemService = true); problem = null }

    var listening by mutableStateOf(false); private set
    var heard by mutableStateOf("")
    var problem by mutableStateOf<String?>(null)

    fun toggle(onText: (String) -> Unit) {
        if (listening) { speech.stop(); return }
        problem = null
        if (!speech.available) {
            problem = "Voice isn't available on this device (no speech recognizer). Type instead; it does exactly the same."
            return
        }
        heard = ""
        listening = true
        speech.start { e ->
            when (e) {
                is SpeechEvent.Partial -> heard = e.text
                is SpeechEvent.Result -> { listening = false; heard = ""; onText(e.text) }
                is SpeechEvent.Failure -> { listening = false; problem = e.message }
                else -> Unit
            }
        }
    }
}

@Composable
fun Composer(voice: VoiceState, hint: String, onSend: (String, Via) -> Unit) {
    var text by remember { mutableStateOf("") }
    fun send() { if (text.isNotBlank()) { voice.problem = null; onSend(text, Via.TEXT); text = "" } }
    Column(Modifier.fillMaxWidth().background(Tk.White).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        voice.problem?.let { Row(verticalAlignment = Alignment.CenterVertically) {
            Text(it, style = Ty.Small.copy(color = Tk.Bronze), modifier = Modifier.weight(1f))
            LinkText("Hide") { voice.problem = null }
        } }
        if (voice.problem != null && voice.speech.state.canDownload) {
            Text("This phone can download an English speech model; recognition then stays on the device.", style = Ty.Small)
            LinkText("Download the speech model") { voice.speech.downloadModel(consented = true); voice.problem = voice.speech.state.summary }
        }
        if (voice.problem != null && voice.canUseSystem) {
            Text("Android's speech service can do it instead. It may send the audio to its provider to turn it into text; Routes itself stores no audio.", style = Ty.Small)
            LinkText("Use Android's speech service") { voice.useSystem() }
        }
        if (voice.listening) {
            Row(Modifier.fillMaxWidth().background(Tk.Chalk, Tk.R).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(Tk.Red, Tk.R))
                Spacer(Modifier.width(10.dp))
                Text(voice.heard.ifBlank { "Listening…" }, style = Ty.Body, modifier = Modifier.weight(1f))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(48.dp).background(if (voice.listening) Tk.Ink else Tk.Red, Tk.R)
                    .clickable(role = Role.Button) { voice.toggle { onSend(it, Via.VOICE) } }
                    .semantics { contentDescription = if (voice.listening) "Stop listening" else "Speak" },
                contentAlignment = Alignment.Center,
            ) {
                if (voice.listening) Box(Modifier.size(14.dp).background(Color.White)) else NavIcon(Space.EXPLORE, Color.White)
            }
            Box(Modifier.weight(1f).heightIn(min = 48.dp).border(1.dp, Tk.Line, Tk.R).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) Text(hint, style = Ty.Body.copy(color = Tk.Ink2))
                BasicTextField(text, { text = it }, textStyle = Ty.Body, singleLine = true, cursorBrush = SolidColor(Tk.Ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Type a question" })
            }
            if (text.isNotBlank()) Secondary("Send", Modifier.width(72.dp)) { send() }
        }
    }
}

/** A focused view over the current screen (bottom sheet without the motion). */
@Composable
fun Sheet(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x521C1C1C)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.86f)
                .background(Tk.White, androidx.compose.foundation.shape.RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = Ty.Strong, modifier = Modifier.weight(1f))
                LinkText("Close", onClick = onClose)
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}
