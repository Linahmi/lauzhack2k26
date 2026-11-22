package ch.ripple.android.speech

import android.annotation.SuppressLint
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/** Memory-only, bounded PCM pipeline. 16kHz mono input; 24kHz mono output. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class LivePcmAudio(
    private val scope: CoroutineScope,
    private val onInput: (ByteArray, Float) -> Unit,
    private val onPlayback: (Boolean, Float) -> Unit,
    private val onError: () -> Unit,
) {
    private var capture: Job? = null
    private var recorder: AudioRecord? = null
    private var echo: AcousticEchoCanceler? = null
    private var noise: NoiseSuppressor? = null
    private val epoch = AtomicInteger()
    private data class Chunk(val epoch: Int, val bytes: ByteArray)
    private val queue = Channel<Chunk>(600) // At most 24 seconds / ~1.2MB, never unbounded.
    private val lock = Any()
    private var track: AudioTrack? = null
    private var framesWritten = 0L
    private var totalBytesWritten = 0L
    private var firstWrite = true
    private var speakerMuted = false
    @Volatile private var complete = false
    @Volatile private var closed = false
    private val playback = scope.launch(Dispatchers.IO) {
        try {
            while (isActive) {
                // A timed receive can consume a packet just as its timeout cancels the coroutine.
                // Poll without cancelling a receive so accepted PCM cannot disappear at that boundary.
                val chunk = queue.tryReceive().getOrNull()
                if (chunk != null && chunk.epoch == epoch.get()) {
                    var offset = 0
                    while (offset < chunk.bytes.size && isActive && chunk.epoch == epoch.get()) {
                        val count = synchronized(lock) {
                            if (closed || chunk.epoch != epoch.get()) 0 else {
                                val output = outputTrack()
                                if (output.playState != AudioTrack.PLAYSTATE_PLAYING) output.play()
                                output.write(chunk.bytes, offset, chunk.bytes.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                            }
                        }
                        if (count < 0) error("Audio playback failed")
                        if (count == 0) delay(10) else {
                            offset += count
                            synchronized(lock) {
                                if (chunk.epoch == epoch.get()) {
                                    framesWritten += count / 2
                                    totalBytesWritten += count
                                    if (firstWrite) {
                                        firstWrite = false
                                        Log.i("RippleLive", "First PCM write: bytes=$count rate=24000 channels=1 encoding=PCM16 state=${track?.playState} route=${track?.routedDevice?.type}")
                                    }
                                }
                            }
                            onPlayback(true, if (speakerMuted) 0f else level(chunk.bytes))
                        }
                    }
                }
                val drained = synchronized(lock) {
                    complete && queue.isEmpty && (track == null ||
                        (track!!.playbackHeadPosition.toLong() and 0xffffffffL) >= framesWritten)
                }
                if (drained) {
                    complete = false
                    Log.i("RippleLive", "PCM drained: totalBytes=$totalBytesWritten framesWritten=$framesWritten playbackHead=${track?.playbackHeadPosition}")
                    onPlayback(false, 0f)
                }
                if (chunk == null) delay(10)
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { if (!closed) onError() }
    }

    private fun outputTrack(): AudioTrack = track ?: AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(24_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setBufferSizeInBytes(maxOf(9_600, AudioTrack.getMinBufferSize(24_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
        .setTransferMode(AudioTrack.MODE_STREAM).build().also {
            check(it.state == AudioTrack.STATE_INITIALIZED)
            it.setBufferSizeInFrames(960)
            if (Build.VERSION.SDK_INT >= 31) it.setStartThresholdInFrames(1)
            check(it.setVolume(if (speakerMuted) 0f else 1f) == AudioTrack.SUCCESS)
            track = it
        }

    fun muteSpeaker(muted: Boolean) = synchronized(lock) {
        speakerMuted = muted
        track?.let { check(it.setVolume(if (muted) 0f else 1f) == AudioTrack.SUCCESS) }
    }

    fun diagnostics(): String = synchronized(lock) {
        "bytes=$totalBytesWritten frames=$framesWritten head=${track?.playbackHeadPosition ?: 0} playState=${track?.playState ?: 0} route=${track?.routedDevice?.type ?: 0} speakerMuted=$speakerMuted"
    }

    fun hasDrainedOutput(): Boolean = synchronized(lock) {
        totalBytesWritten > 0 && queue.isEmpty && track != null &&
            (track!!.playbackHeadPosition.toLong() and 0xffffffffL) >= framesWritten
    }

    @SuppressLint("MissingPermission") // Called only after MainActivity permission callback.
    fun startMicrophone() {
        if (closed || capture != null) return
        val record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(6_400, AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)))
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); error("Microphone unavailable") }
        recorder = record
        if (AcousticEchoCanceler.isAvailable()) echo = AcousticEchoCanceler.create(record.audioSessionId)?.also { it.enabled = true }
        if (NoiseSuppressor.isAvailable()) noise = NoiseSuppressor.create(record.audioSessionId)?.also { it.enabled = true }
        record.startRecording()
        check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        capture = scope.launch(Dispatchers.IO) {
            try {
                val buffer = ByteArray(1_280) // 40ms; explicit little-endian Android PCM.
                while (isActive) {
                    val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (count <= 0) error("Microphone disconnected")
                    ensureActive()
                    val bytes = buffer.copyOf(count - count % 2)
                    onInput(bytes, level(bytes))
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { if (!closed && isActive) onError() }
        }
    }

    fun stopMicrophone() {
        capture?.cancel(); capture = null
        try { recorder?.stop() } catch (_: RuntimeException) { }
        recorder?.release(); recorder = null
        echo?.release(); echo = null; noise?.release(); noise = null
    }

    fun enqueue(bytes: ByteArray): Boolean {
        if (closed || bytes.isEmpty() || bytes.size % 2 != 0) return false
        complete = false
        // Large provider packets are split so nonblocking writes never queue huge chunks.
        for (start in bytes.indices step 1_920) {
            if (!queue.trySend(Chunk(epoch.get(), bytes.copyOfRange(start, minOf(start + 1_920, bytes.size)))).isSuccess) return false
        }
        return true
    }
    fun turnComplete() { complete = true }
    fun interrupt() {
        epoch.incrementAndGet()
        while (queue.tryReceive().isSuccess) { }
        synchronized(lock) {
            track?.pause(); track?.flush(); framesWritten = 0
        }
        complete = false
        onPlayback(false, 0f)
    }
    fun close() {
        closed = true; stopMicrophone(); playback.cancel(); queue.close()
        synchronized(lock) { track?.pause(); track?.flush(); track?.release(); track = null }
    }
    companion object {
        fun level(bytes: ByteArray): Float {
            var sum = 0.0
            for (i in 0 until bytes.size - 1 step 2) {
                val sample = ((bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)).toShort().toDouble()
                sum += sample * sample
            }
            return (sqrt(sum / maxOf(1, bytes.size / 2)) / 6_000).toFloat().coerceIn(0f, 1f)
        }
    }
}
