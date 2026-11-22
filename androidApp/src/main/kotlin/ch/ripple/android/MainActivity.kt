package ch.ripple.android

import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import ch.ripple.android.speech.AndroidSpeechInput
import ch.ripple.app.phone.PhoneApp
import ch.ripple.app.phone.PhonePlatform
import ch.ripple.app.phone.RoutesSession
import java.util.Date

class MainActivity : ComponentActivity() {
    private lateinit var speechInput: AndroidSpeechInput
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (::speechInput.isInitialized) speechInput.permissionResult(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        // Marwa's capability-checked recognizer (from ui/my-future); words only, no audio kept.
        speechInput = AndroidSpeechInput(this, requestPermission = { microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO) })
        speechInput.refreshSupport("en")
        val timeFormat = DateFormat.getTimeFormat(this)
        val store = FileSessionStore(filesDir)
        setContent {
            val session = remember { RoutesSession(store) { System.currentTimeMillis() } }
            val platform = remember {
                PhonePlatform(speechInput, timeText = { timeFormat.format(Date(it)) }, backHandler = { enabled, onBack -> BackHandler(enabled, onBack) })
            }
            PhoneApp(session, platform)
        }
    }

    override fun onStop() { speechInput.interrupt(); super.onStop() }
    override fun onDestroy() { speechInput.close(); super.onDestroy() }
}
