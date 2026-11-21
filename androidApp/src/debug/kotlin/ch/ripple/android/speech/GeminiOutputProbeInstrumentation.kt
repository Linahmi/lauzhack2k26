package ch.ripple.android.speech

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import ch.ripple.android.MainActivity
import ch.ripple.app.assistant.LivePhase

/** Explicit ADB-only Gemini output test. No microphone, audio files, credentials or transcripts logged. */
class GeminiOutputProbeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        var live: AndroidLiveConversation? = null
        try {
            startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            runOnMainSync {
                live = AndroidLiveConversation(targetContext, {}, {})
                live!!.startOutputProbe()
            }
            var complete = false
            val until = SystemClock.elapsedRealtime() + 50_000
            while (!complete && SystemClock.elapsedRealtime() < until) {
                SystemClock.sleep(100)
                runOnMainSync {
                    val state = live!!.state
                    complete = state.phase == LivePhase.Error || (state.canReplay && live!!.outputDrained())
                }
            }
            runOnMainSync {
                result.putString("diagnostics", live!!.diagnostics())
                result.putString("phase", live!!.state.phase.name)
                result.putString("error", live!!.state.error)
                result.putBoolean("completedAudioTurn", live!!.state.canReplay)
                result.putBoolean("microphoneNeverStarted", !live!!.state.microphoneActive)
                result.putString("scope", "Text-triggered Gemini output only. Human confirmation of audibility is required.")
            }
        } catch (error: Exception) {
            result.putString("probeErrorType", error.javaClass.simpleName)
        } finally {
            runOnMainSync { live?.close() }
        }
        finish(Activity.RESULT_OK, result)
    }
}
