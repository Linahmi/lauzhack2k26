package ch.ripple.app.phone

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import ch.ripple.app.assistant.LiveConversation
import ch.ripple.app.assistant.LivePhase
import ch.ripple.app.assistant.LiveVoiceState
import ch.ripple.app.assistant.TypedInputOnly
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ExploreScreenTest {
    private class FakeLive(override var state: LiveVoiceState = LiveVoiceState()) : LiveConversation {
        override val available = true
        override var onTranscript: ((String) -> Unit)? = null
        override suspend fun unavailableReason(): String? = null
        override fun speak(text: String) = Unit
        override fun start(verifiedContext: String) = Unit
        override fun mute(muted: Boolean) = Unit
        override fun muteSpeaker(muted: Boolean) = Unit
        override fun stopAudio() = Unit
        override fun replay() = Unit
        override fun end() = Unit
    }

    private val shots = System.getenv("ROUTES_SHOTS")?.let(::File)?.also { it.mkdirs() }
    private fun ComposeUiTest.shot(name: String) { waitForIdle(); shots?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "$name.png")) } }

    private fun ComposeUiTest.explore(session: RoutesSession, live: LiveConversation = FakeLive()) = setContent {
        val scope = rememberCoroutineScope()
        val voice = VoiceState(TypedInputOnly, live, scope, onVoiceText = { session.handle(it, Via.VOICE) })
        ExploreScreen(session, PhoneNav(), voice, PhonePlatform(animationsOff = { true }))
    }

    private fun session() = RoutesSession(MemoryStore()) { 1_000L }

    @Test fun idleShowsTheMicAtBothWidths() = listOf(360, 412).forEach { w ->
        runDesktopComposeUiTest(w, 800) {
            explore(session())
            onNode(hasContentDescription("Start talking")).assertExists()
            onNodeWithText("Tap to talk").assertExists()
            onNodeWithText("Voice is sent to Google to reply. You can always type.").assertExists()
            shot("explore-idle-$w")
        }
    }

    @Test fun startingTheExampleStaysIdleWithItsGreeting() = runDesktopComposeUiTest(360, 800) {
        explore(session().also { it.startExample() })
        onNodeWithText("You're exploring as Alex", substring = true).assertExists()
        onNode(hasContentDescription("Start talking")).assertExists()
        onNodeWithText("Tap to talk").assertExists()
        onNodeWithText("Try: “What if I move out?”").assertExists()
    }

    @Test fun typeInsteadOpensAndFocusesTheField() = runDesktopComposeUiTest(360, 800) {
        explore(session())
        onNodeWithText("Type instead").performClick()
        waitForIdle()
        onNode(hasContentDescription("Type a question")).assertIsFocused()
    }

    @Test fun deniedMicrophoneShowsTheTextField() = runDesktopComposeUiTest(360, 800) {
        explore(session(), FakeLive(LiveVoiceState(permissionDenied = true)))
        onNodeWithText("Allow microphone").assertExists()
        onNode(hasContentDescription("Type a question")).assertExists()
        shot("explore-denied")
    }

    @Test fun listeningShowsTheTranscriptAndStop() = runDesktopComposeUiTest(360, 800) {
        explore(session(), FakeLive(LiveVoiceState(connected = true, phase = LivePhase.Listening, inputLevel = 0.6f, heard = "What if I move out in March and")))
        onNodeWithText("What if I move out in March and").assertExists()
        onNode(hasContentDescription("Stop listening")).assertExists()
        shot("explore-listening")
    }

    @Test fun answerComesFromTheEngineReadout() = listOf(360, 412).forEach { w ->
        runDesktopComposeUiTest(w, 800) {
            val s = session()
            s.startExample()
            listOf("What if I moved out?", "350", "500", "not sure", "1300 to 1700").forEach { s.handle(it, Via.TEXT) }
            explore(s, FakeLive(LiveVoiceState(connected = true, phase = LivePhase.Speaking, playbackActive = true)))
            // Alex's engine result: April, CHF 300 left, below the CHF 500 cushion.
            onNodeWithText("April gets tight").assertExists()
            onNodeWithText("CHF 300 left at the end of April.").assertExists()
            onNodeWithText("Month by month →").assertExists()
            onNodeWithText("Speaking · tap to interrupt").assertExists()
            onNode(hasContentDescription("Interrupt")).assertExists()
            shot("explore-answer-$w")
        }
    }
}
