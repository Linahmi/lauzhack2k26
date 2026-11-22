package ch.ripple.android

import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import ch.ripple.android.speech.AndroidSpeechInput
import ch.ripple.android.speech.AndroidLiveConversation
import ch.ripple.app.phone.PhoneApp
import ch.ripple.app.phone.PhonePlatform
import ch.ripple.app.phone.RoutesSession
import java.util.Date

class MainActivity : ComponentActivity() {
    private lateinit var speechInput: AndroidSpeechInput
    private lateinit var live: AndroidLiveConversation
    private val livePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (::live.isInitialized) live.permissionResult(granted)
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
        // Marwa's capability-checked recognizer (from ui/my-future); words only, no audio kept.
        speechInput = AndroidSpeechInput(this, requestPermission = { microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO) })
        speechInput.refreshSupport("en")
        live = AndroidLiveConversation(this,
            requestPermission = { livePermission.launch(android.Manifest.permission.RECORD_AUDIO) },
            beforeStart = { speechInput.cancel() })
        val timeFormat = DateFormat.getTimeFormat(this)
        val store = FileSessionStore(filesDir)
        val appearance = ch.ripple.app.AppearanceSetting(PrefsAppearanceStore(getSharedPreferences("routes", MODE_PRIVATE)))
        setContent {
            // Status and navigation bar icons follow the theme in use.
            val dark = ch.ripple.app.RoutesTheme.palette.isDark
            androidx.compose.runtime.SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            val session = remember { RoutesSession(store) { System.currentTimeMillis() } }
            val platform = remember {
                PhonePlatform(speechInput, timeText = { timeFormat.format(Date(it)) }, backHandler = { enabled, onBack -> BackHandler(enabled, onBack) }, live = live,
                    canCapture = { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }, appearance = appearance,
                    animationsOff = { android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f })
            }
            PhoneApp(session, platform)
        }
    }

    override fun onStop() { live.end(); speechInput.interrupt(); super.onStop() }
    override fun onDestroy() { live.close(); speechInput.close(); super.onDestroy() }
}
