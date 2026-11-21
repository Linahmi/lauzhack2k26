package ch.ripple.app.welcome

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.ripple.app.components.PrimaryButton

/** A single mobile composition. Large text can scroll; the normal layout fills the viewport. */
@Composable
fun WelcomeScreen(
    modifier: Modifier = Modifier,
    reduceMotion: Boolean,
    onGetStarted: () -> Unit,
    onDisplayOptions: () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val height = maxHeight.coerceAtLeast(740.dp)
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(height * .10f))
            Column(Modifier.align(Alignment.CenterHorizontally)
                .clickable(role = Role.Button, onClickLabel = "Display options", onClick = onDisplayOptions)
                .semantics { contentDescription = "Ripple. Display options" },
                horizontalAlignment = Alignment.CenterHorizontally) {
                RippleCrest(Modifier.size(57.dp, 30.dp))
                Text("Ripple", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
                    fontSize = 43.sp, lineHeight = 50.sp)
            }
            Spacer(Modifier.height((height * .20f - 80.dp).coerceAtLeast(24.dp)))
            Text("Understand your\nfinances with\nconfidence.",
                Modifier.fillMaxWidth().padding(horizontal = 32.dp).semantics { heading() },
                fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp)
            Spacer(Modifier.height(22.dp))
            Text("A calm place to see your money clearly, explore your options, and make decisions at your own pace.",
                Modifier.fillMaxWidth().padding(horizontal = 32.dp), style = MaterialTheme.typography.bodyMedium)
            Box(Modifier.fillMaxWidth().height((height * .70f - 223.dp).coerceAtLeast(260.dp))) {
                AlpineLandscape(Modifier.fillMaxSize(), reduceMotion)
                PrimaryButton("Get started", onGetStarted,
                    Modifier.align(Alignment.BottomCenter).padding(start = 28.dp, end = 28.dp, bottom = 30.dp).fillMaxWidth())
            }
        }
    }
}
