package ch.ripple.app.assistant

import kotlin.test.Test
import kotlin.test.assertEquals

class LiveVoiceStateTest {
    @Test fun microphoneMuteStillAllowsSpeaking() {
        assertEquals(LivePhase.Speaking, LiveVoiceState(connected = true, muted = true, playbackActive = true).audioPhase())
        assertEquals(LivePhase.MicrophoneMuted, LiveVoiceState(connected = true, muted = true).audioPhase())
    }

    @Test fun speakerMuteDoesNotDependOnCaptureMute() {
        assertEquals(LivePhase.SpeakerMuted, LiveVoiceState(connected = true, microphoneActive = true, speakerMuted = true, playbackActive = true).audioPhase())
        assertEquals(LivePhase.Listening, LiveVoiceState(connected = true, microphoneActive = true).audioPhase())
    }

    @Test fun captureFramesCannotOverwriteThinking() {
        assertEquals(LivePhase.Thinking, LiveVoiceState(connected = true, microphoneActive = true, awaitingResponse = true).audioPhase())
        assertEquals(LivePhase.Speaking, LiveVoiceState(connected = true, microphoneActive = true, awaitingResponse = true, playbackActive = true).audioPhase())
    }

    @Test fun disconnectedAndConnectingStatesDoNotPretendToListen() {
        for (phase in listOf(LivePhase.Ready, LivePhase.Connecting, LivePhase.Disconnected, LivePhase.Error)) {
            assertEquals(phase, LiveVoiceState(phase = phase, microphoneActive = true).audioPhase())
        }
    }
}
