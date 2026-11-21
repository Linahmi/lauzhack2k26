package ch.ripple.android.speech

import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong

/** Explicit ADB-only capture diagnostic, absent from release. Records no files and sends no audio. */
class AudioProbeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val bytes = AtomicLong()
        val nonZero = AtomicLong()
        var peak = 0f
        var failed = false
        val played = java.util.concurrent.atomic.AtomicBoolean()
        val drained = java.util.concurrent.atomic.AtomicBoolean()
        val audio = LivePcmAudio(scope, { chunk, level ->
            bytes.addAndGet(chunk.size.toLong())
            nonZero.addAndGet(chunk.count { it != 0.toByte() }.toLong())
            peak = maxOf(peak, level)
        }, { playing, _ -> if (playing) played.set(true) else drained.set(true) }, { failed = true })
        try {
            audio.startMicrophone()
            SystemClock.sleep(3_000)
            audio.stopMicrophone()
            // Known synthetic 100ms PCM tone tests the output pipe; never a pretend Gemini response.
            val tone = ByteArray(4_800)
            for (i in 0 until 2_400) {
                val sample = (kotlin.math.sin(i * 2.0 * Math.PI * 440 / 24_000) * 2_000).toInt()
                tone[i * 2] = sample.toByte(); tone[i * 2 + 1] = (sample shr 8).toByte()
            }
            audio.enqueue(tone); audio.turnComplete()
            SystemClock.sleep(1_000)
            result.putLong("capturedBytes", bytes.get())
            result.putLong("nonZeroBytes", nonZero.get())
            result.putFloat("peakLevel", peak)
            result.putBoolean("captureError", failed)
            result.putBoolean("syntheticPcmAcceptedByAudioTrack", played.get())
            result.putBoolean("syntheticPcmPlaybackHeadDrained", drained.get())
            result.putString("scope", "Local capture only. Nonempty bytes do NOT establish intelligible speech or Gemini receipt.")
        } catch (_: Exception) { result.putString("error", "Microphone capture failed or permission missing.")
        } finally { audio.close(); scope.cancel() }
        finish(Activity.RESULT_OK, result)
    }
}
