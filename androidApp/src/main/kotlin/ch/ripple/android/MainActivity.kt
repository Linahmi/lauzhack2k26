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
import ch.ripple.android.speech.AndroidLiveConversation

class MainActivity : ComponentActivity() {
    private lateinit var speechInput: AndroidSpeechInput
    private lateinit var speechOutput: AndroidSpeechOutput
    private lateinit var liveConversation: AndroidLiveConversation
    private val livePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (::liveConversation.isInitialized) liveConversation.permissionResult(granted)
    }
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (::speechInput.isInitialized) speechInput.permissionResult(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        speechOutput = AndroidSpeechOutput(this) {
            if (::speechInput.isInitialized) speechInput.interrupt()
            if (::liveConversation.isInitialized) liveConversation.end()
        }
        speechInput = AndroidSpeechInput(this,
            requestPermission = { microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO) },
            beforeCapture = { speechOutput.stop() })
        speechInput.refreshSupport("en")
        liveConversation = AndroidLiveConversation(this,
            requestPermission = { livePermission.launch(android.Manifest.permission.RECORD_AUDIO) },
            beforeStart = { speechInput.cancel(); speechOutput.stop() })
        setContent {
            App(speechInput = speechInput, speechOutput = speechOutput, liveConversation = liveConversation,
                platformBackHandler = { enabled, onBack -> BackHandler(enabled, onBack) })
        }
    }

    override fun onStop() {
        liveConversation.end()
        speechInput.interrupt()
        speechOutput.stop()
        super.onStop()
    }

    override fun onDestroy() {
        liveConversation.close()
        speechInput.close()
        speechOutput.close()
        super.onDestroy()
    }
}
