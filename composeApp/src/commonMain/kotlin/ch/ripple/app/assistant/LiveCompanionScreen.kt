package ch.ripple.app.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.ripple.app.components.PrimaryButton

@Composable
fun LiveCompanionScreen(
    live: LiveConversation, speechInput: SpeechInput, speechOutput: SpeechOutput,
    twin: FinancialTwinController, reduceMotion: Boolean, modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    var textMode by rememberSaveable { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    val modes = rememberSaveableStateHolder()
    val state = live.state
    val phase = state.phase
    val active = state.connected || phase == LivePhase.Connecting
    DisposableEffect(live) { onDispose { live.end() } }
    fun begin() { consent = true }
    if (textMode) {
        Column(modifier) {
            Text("Guided fictional demo · text\nOllama text chat is not connected yet.",
                Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            modes.SaveableStateProvider("guided-text") {
                AssistantScreen(speechInput, reduceMotion, Modifier.weight(1f), speechOutput, twin) { textMode = false }
            }
        }
    } else {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({ live.end(); onBack() }, Modifier.size(48.dp).semantics { contentDescription = "Back" }) { Text("‹", fontSize = 30.sp) }
                    TextButton({ options = true }, Modifier.size(48.dp).semantics { contentDescription = "Conversation options" }) { Text("···", fontSize = 26.sp) }
                }
                Spacer(Modifier.height(28.dp))
                Text("What's on your mind?", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() })
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(24.dp))
                MicrophoneControl(phase == LivePhase.Listening, phase == LivePhase.Connecting || phase == LivePhase.Thinking,
                    live.available, reduceMotion, speaking = phase == LivePhase.Speaking,
                    audioLevel = if (phase == LivePhase.Speaking) state.outputLevel else state.inputLevel,
                    actionLabel = if (!active) "Start microphone" else if (state.muted) "Unmute microphone" else "Mute microphone",
                    onClick = { if (active) live.mute(!state.muted) else begin() })
                Spacer(Modifier.height(20.dp))
                Text(when (phase) {
                    LivePhase.Ready -> "Ready when you are"
                    LivePhase.Connecting -> "Connecting"
                    LivePhase.Listening -> "Listening"
                    LivePhase.Thinking -> "Gemini thinking"
                    LivePhase.Speaking -> "Gemini speaking"
                    LivePhase.MicrophoneMuted -> "Microphone muted"
                    LivePhase.SpeakerMuted -> "Speaker muted"
                    LivePhase.Disconnected -> "Disconnected"
                    LivePhase.Error -> "Conversation unavailable"
                }, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyLarge)
                if (state.connected) Text(
                    (if (state.muted) "Microphone muted" else "Microphone on") + " · " +
                        (if (state.speakerMuted) "Speaker muted" else "Speaker on"),
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                if (state.responseStopped) Text("Reply stopped. You can keep talking.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(20.dp))
                Text("Tell me what's on your mind. I'm here to help.", style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!live.available) Text("Live voice is available on Android. You can use the guided text demo here.",
                    Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                state.error?.let { Text(it, Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
                if (phase == LivePhase.Error) TextButton({ begin() }, Modifier.heightIn(min = 56.dp)) { Text("Try again") }
                if (state.permissionDenied) TextButton({ live.openMicrophoneSettings() }, Modifier.heightIn(min = 56.dp)) { Text("Open microphone settings") }
                if (state.reply.isNotBlank()) Text(state.reply.takeLast(600), Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (active) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton({ live.stopAudio() }, Modifier.heightIn(min = 56.dp), enabled = state.connected) { Text("Stop reply") }
                        OutlinedButton({ live.mute(!state.muted) }, Modifier.heightIn(min = 56.dp), enabled = state.connected) { Text(if (state.muted) "Unmute mic" else "Mute mic") }
                    }
                    TextButton({ live.end() }, Modifier.heightIn(min = 56.dp)) { Text("End conversation") }
                }
                TextButton({ live.end(); speechOutput.stop(); textMode = true }, Modifier.fillMaxWidth().heightIn(min = 60.dp)) { Text("I'd rather type") }
            }
        }
    }
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text("Start a voice conversation?") },
        text = { Text("Your microphone audio and the fictional demo's verified context will be sent directly to Google's Gemini API while this conversation is active. Gemini generates the spoken replies. No real bank account is connected. You can mute, end, or type instead. Financial changes still require confirmation in the app.") },
        confirmButton = { TextButton({
            consent = false; speechInput.cancel(); speechOutput.stop()
            val briefing = twin.briefing
            val context = if (briefing == null) "No verified financial result is available. Do not state any financial figures."
                else "Verified fictional Alex moving-home demo only: " + BriefingPresenter.insight(briefing, false) + " " +
                    (listOfNotNull(BriefingPresenter.bounds(briefing, false)) + BriefingPresenter.qualifications(briefing, false)).joinToString(" ")
            live.start(context)
        }, Modifier.heightIn(min = 56.dp)) { Text("Agree and start") } },
        dismissButton = { TextButton({ consent = false }, Modifier.heightIn(min = 56.dp)) { Text("Cancel") } })
    if (options) AlertDialog(onDismissRequest = { options = false }, title = { Text("Conversation options") },
        text = { Column {
            Text("English and French are selected naturally by your speech. Native Gemini audio is separate from the guided demo's Android read-aloud.")
            TextButton({ live.replay() }, Modifier.heightIn(min = 56.dp), enabled = state.connected && state.canReplay) { Text("Replay last response") }
            TextButton({ live.muteSpeaker(!state.speakerMuted) }, Modifier.heightIn(min = 56.dp), enabled = state.connected) {
                Text(if (state.speakerMuted) "Unmute speaker" else "Mute speaker")
            }
            Text("Microphone mute only pauses recording. Speaker mute only silences replies. Replies use Android's call volume; raise it while a reply is playing if needed.", style = MaterialTheme.typography.bodySmall)
            Text(state.model.ifBlank { "Model supplied by your local token service." }, style = MaterialTheme.typography.bodySmall)
            Text("My Finances is not implemented yet. The supported My Future experience is the fictional moving-home guided demo. No automatic navigation or financial corrections are enabled for Live voice.", style = MaterialTheme.typography.bodySmall)
            TextButton({ live.end(); options = false; textMode = true }, Modifier.heightIn(min = 56.dp)) { Text("Explore my future plan · demo") }
        } }, confirmButton = { TextButton({ options = false }) { Text("Done") } })
}
