package ch.ripple.app.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RippleWordmark() {
    val red = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Canvas(Modifier.size(36.dp).clearAndSetSemantics { }) {
            // Original open ripple: a small beginning with room to grow.
            drawCircle(red, size.minDimension * .12f, Offset(size.width * .28f, size.height * .65f))
            drawArc(red, 200f, 240f, false, Offset(size.width * .12f, size.height * .25f),
                Size(size.width * .58f, size.height * .58f), style = Stroke(2.dp.toPx()))
            drawArc(red, 200f, 240f, false, Offset(0f, size.height * .03f),
                Size(size.width * .94f, size.height * .94f), style = Stroke(2.dp.toPx()))
        }
        Text("Ripple", fontFamily = FontFamily.Serif, fontSize = 36.sp)
    }
}
@Composable
fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick, modifier.heightIn(min = 60.dp), enabled = enabled,
        shape = MaterialTheme.shapes.small, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)) { Text(label) }
}
@Composable
fun BackButton(onBack: () -> Unit) {
    TextButton(onBack, Modifier.heightIn(min = 56.dp)) { Text("‹  Back") }
}
@Composable
fun DemoDisclosure() {
    Text("Fictional demo data. No bank connection. Results illustrate assumptions, not predictions or financial advice.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable
fun MotionPreference(reduced: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(reduced, role = Role.Checkbox, onValueChange = onChange)
        .padding(horizontal = 20.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
        Text("Reduce motion", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(8.dp))
        Checkbox(checked = reduced, onCheckedChange = null)
    }
}
