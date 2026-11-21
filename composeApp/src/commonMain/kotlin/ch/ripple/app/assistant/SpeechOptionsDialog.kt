package ch.ripple.app.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SpeechOptionsDialog(
    speechInput: SpeechInput, speechOutput: SpeechOutput, language: String, muted: Boolean, busy: Boolean,
    onLanguage: (String) -> Unit, onMute: () -> Unit, onReplay: () -> Unit, onDismiss: () -> Unit,
) {
    var askSystem by remember { mutableStateOf(false) }
    var askDownload by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(false) }
    val input = speechInput.state
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Voice & language") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                VoiceControls(language, muted, speechOutput.state, busy, onLanguage, onMute, onReplay, { speechOutput.stop() })
                HorizontalDivider()
                Text("Speech recognition", style = MaterialTheme.typography.titleMedium)
                Text(input.summary, style = MaterialTheme.typography.bodySmall)
                Text(if (input.route == SpeechRoute.SYSTEM) "Selected: Android system service" else "Selected: on-device recognition", style = MaterialTheme.typography.bodySmall)
                Text(speechInput.notice, style = MaterialTheme.typography.bodySmall)
                if (input.canDownload) OutlinedButton({ askDownload = true }, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = !busy) { Text("Download language model…") }
                if (input.onDevice != SpeechCapability.MISSING && input.route != SpeechRoute.ON_DEVICE) {
                    OutlinedButton({ speechInput.chooseRoute(SpeechRoute.ON_DEVICE) }, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = !busy) { Text("Use on-device recognition") }
                }
                if (input.system != SpeechCapability.MISSING && input.route != SpeechRoute.SYSTEM) {
                    OutlinedButton({ askSystem = true }, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = !busy) { Text("Use system recognition…") }
                }
                if (speechInput.available) TextButton({ speechInput.refreshSupport(language) }, Modifier.heightIn(min = 48.dp), enabled = !busy) { Text("Check availability again") }
                TextButton({ diagnostics = !diagnostics }, Modifier.heightIn(min = 48.dp)) { Text("Support details") }
                if (diagnostics) Text(input.diagnostics.ifBlank { "No support report is available." }, style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onDismiss, Modifier.heightIn(min = 56.dp)) { Text("Done") } })
    if (askSystem) AlertDialog(onDismissRequest = { askSystem = false },
        title = { Text("Use Android system recognition?") },
        text = { Text("Your Android speech provider may send audio to its servers. Ripple cannot promise offline processing for this service. You can keep typing or use an installed on-device model instead. Nothing is recorded until you tap Record.") },
        confirmButton = { TextButton({ speechInput.chooseRoute(SpeechRoute.SYSTEM, consentToSystemService = true); askSystem = false }, Modifier.heightIn(min = 56.dp)) { Text("Allow system speech") } },
        dismissButton = { TextButton({ askSystem = false }, Modifier.heightIn(min = 56.dp)) { Text("Keep typing") } })
    if (askDownload) AlertDialog(onDismissRequest = { askDownload = false },
        title = { Text("Download a speech model?") },
        text = { Text("Android's existing speech provider will request the selected language model. This uses network data and device storage and may open a system confirmation. Recording will stay off. Only continue if you want this download.") },
        confirmButton = { TextButton({ speechInput.downloadModel(consented = true); askDownload = false }, Modifier.heightIn(min = 56.dp)) { Text("Download model") } },
        dismissButton = { TextButton({ askDownload = false }, Modifier.heightIn(min = 56.dp)) { Text("Cancel") } })
}
