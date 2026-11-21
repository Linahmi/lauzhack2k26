package ch.ripple.app.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun VoiceControls(
    language: String, muted: Boolean, state: SpeechOutputState, busy: Boolean,
    onLanguage: (String) -> Unit, onMute: () -> Unit, onReplay: () -> Unit, onStop: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("en" to "English", "fr" to "Français").forEach { (tag, label) ->
                FilterChip(selected = language == tag, onClick = { onLanguage(tag) }, enabled = !busy,
                    label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onMute, Modifier.weight(1f).heightIn(min = 56.dp), enabled = !busy && language in state.languages) {
                Text(if (muted) "Unmute voice" else "Mute voice")
            }
            OutlinedButton(onReplay, Modifier.weight(1f).heightIn(min = 56.dp), enabled = !busy && !muted && language in state.languages) {
                Text("Replay response")
            }
        }
        Text(if (language !in state.languages && state.languages.isNotEmpty()) "No installed voice for this language. Continue with text."
            else state.message, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (state.speaking) TextButton(onStop, Modifier.heightIn(min = 56.dp)) { Text("Stop speaking") }
    }
}
