package ch.ripple.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import ch.ripple.app.App
import ch.ripple.android.speech.AndroidSpeechInput
import ch.ripple.android.speech.AndroidSpeechOutput

class MainActivity : ComponentActivity() {
    private lateinit var speechInput: AndroidSpeechInput
    private lateinit var speechOutput: AndroidSpeechOutput
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (::speechInput.isInitialized) speechInput.permissionResult(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        speechOutput = AndroidSpeechOutput(this) { if (::speechInput.isInitialized) speechInput.interrupt() }
        speechInput = AndroidSpeechInput(this,
            requestPermission = { microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO) },
            beforeCapture = { speechOutput.stop() })
        speechInput.refreshSupport("en")
        setContent {
            App(speechInput = speechInput, speechOutput = speechOutput,
                platformBackHandler = { enabled, onBack -> BackHandler(enabled, onBack) })
        }
    }

    override fun onStop() {
        speechInput.interrupt()
        speechOutput.stop()
        super.onStop()
    }

    override fun onDestroy() {
        speechInput.close()
        speechOutput.close()
        super.onDestroy()
    }
}
