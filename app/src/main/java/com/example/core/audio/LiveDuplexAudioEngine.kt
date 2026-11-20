package com.example.core.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * Low-latency bidirectional PCM audio engine for Gemini Live sessions:
 * - Capture: 16 kHz, 16-bit mono PCM with hardware AEC/NS when supported
 * - Playback: 24 kHz, 16-bit mono PCM tagged by responseId so interruptions flush AudioTrack instantly
 */
class LiveDuplexAudioEngine {

    companion object {
        private const val TAG = "LiveDuplexAudioEngine"
        private const val CAPTURE_SAMPLE_RATE = 16_000
        private const val PLAYBACK_SAMPLE_RATE = 24_000
        private const val CAPTURE_FRAME_BYTES = 1280 // 40 ms @ 16 kHz, 16-bit mono
        private const val PLAYBACK_SLICE_BYTES = 2400 // 50 ms @ 24 kHz, 16-bit mono
    }

    private data class TaggedAudioChunk(
        val responseId: Long,
        val pcmBytes: ByteArray
    )

    private val currentResponseId = AtomicLong(1L)
    private val minPlayableResponseId = AtomicLong(1L)
    private val isReleased = AtomicBoolean(false)
    private val trackLock = Any()

    private val playbackChannel = Channel<TaggedAudioChunk>(Channel.UNLIMITED)
    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private var captureJob: Job? = null

    fun currentResponseId(): Long = currentResponseId.get()

    fun startPlaybackLoop(scope: CoroutineScope) {
        if (playbackJob?.isActive == true) return
        ensureAudioTrack()
        playbackJob = scope.launch(Dispatchers.IO) {
            try {
                for (chunk in playbackChannel) {
                    if (!isActive || isReleased.get()) break
                    if (chunk.responseId < minPlayableResponseId.get()) continue
                    writeChunkCheckingInterruption(chunk.responseId, chunk.pcmBytes)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Playback loop ended", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startMicCapture(
        scope: CoroutineScope,
        onPcmChunk: suspend (ByteArray) -> Unit,
        onAmplitude: (Float) -> Unit
    ) {
        if (captureJob?.isActive == true) return
        captureJob = scope.launch(Dispatchers.IO) {
            var recorder: AudioRecord? = null
            var aec: AcousticEchoCanceler? = null
            var ns: NoiseSuppressor? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    CAPTURE_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(CAPTURE_FRAME_BYTES * 2)

                recorder = createAudioRecord(minBuf)
                if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord initialization failed")
                    return@launch
                }

                runCatching {
                    if (AcousticEchoCanceler.isAvailable()) {
                        aec = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
                    }
                }
                runCatching {
                    if (NoiseSuppressor.isAvailable()) {
                        ns = NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true }
                    }
                }

                recorder.startRecording()
                val buffer = ByteArray(CAPTURE_FRAME_BYTES)
                while (isActive && !isReleased.get()) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val copy = buffer.copyOf(read)
                        onAmplitude(computePcmAmplitude(copy))
                        onPcmChunk(copy)
                    } else if (read < 0) {
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Mic capture loop stopped", e)
            } finally {
                onAmplitude(0f)
                runCatching { aec?.release() }
                runCatching { ns?.release() }
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
            }
        }
    }

    fun enqueuePlaybackAudio(responseId: Long, pcmBytes: ByteArray) {
        if (isReleased.get() || pcmBytes.isEmpty()) return
        if (responseId < minPlayableResponseId.get()) return
        playbackChannel.trySend(TaggedAudioChunk(responseId = responseId, pcmBytes = pcmBytes))
    }

    /**
     * Advances the response ID after a normal turn completion without cutting off trailing buffered audio.
     */
    fun advanceTurnGracefully(nextResponseId: Long) {
        currentResponseId.set(nextResponseId)
    }

    /**
     * Immediately halts buffered and queued audio for a cancelled response and advances [minPlayableResponseId].
     */
    fun interruptPlayback(nextResponseId: Long) {
        currentResponseId.set(nextResponseId)
        minPlayableResponseId.set(nextResponseId)
        while (playbackChannel.tryReceive().isSuccess) {
            // Drain all pending stale audio packets
        }
        synchronized(trackLock) {
            val track = audioTrack
            if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                runCatching {
                    track.pause()
                    track.flush()
                    track.play()
                }
            }
        }
    }

    suspend fun release() {
        if (!isReleased.compareAndSet(false, true)) return
        captureJob?.cancel()
        playbackJob?.cancel()
        while (playbackChannel.tryReceive().isSuccess) {
            // Drain channel
        }
        playbackChannel.close()
        withContext(Dispatchers.IO) {
            synchronized(trackLock) {
                runCatching {
                    audioTrack?.pause()
                    audioTrack?.flush()
                    audioTrack?.stop()
                }
                runCatching { audioTrack?.release() }
                audioTrack = null
            }
        }
    }

    private fun ensureAudioTrack() {
        synchronized(trackLock) {
            if (audioTrack != null || isReleased.get()) return
            val minBuf = AudioTrack.getMinBufferSize(
                PLAYBACK_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(PLAYBACK_SLICE_BYTES * 2)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(PLAYBACK_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (track.state == AudioTrack.STATE_INITIALIZED) {
                track.play()
                audioTrack = track
            } else {
                runCatching { track.release() }
                Log.e(TAG, "AudioTrack failed to initialize")
            }
        }
    }

    private fun writeChunkCheckingInterruption(chunkResponseId: Long, pcmBytes: ByteArray) {
        var offset = 0
        while (offset < pcmBytes.size && !isReleased.get()) {
            if (chunkResponseId < minPlayableResponseId.get()) return
            val count = minOf(PLAYBACK_SLICE_BYTES, pcmBytes.size - offset)
            val written = synchronized(trackLock) {
                val track = audioTrack ?: return@synchronized -1
                if (track.state != AudioTrack.STATE_INITIALIZED || chunkResponseId < minPlayableResponseId.get()) {
                    return@synchronized -1
                }
                track.write(pcmBytes, offset, count)
            }
            if (written <= 0) return
            offset += written
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(bufferSize: Int): AudioRecord? {
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC
        )
        for (source in sources) {
            val candidate = runCatching {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(CAPTURE_SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            }.getOrNull()
            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                return candidate
            }
            runCatching { candidate?.release() }
        }
        return null
    }

    private fun computePcmAmplitude(pcmBytes: ByteArray): Float {
        if (pcmBytes.size < 2) return 0f
        var sumSq = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < pcmBytes.size) {
            val lo = pcmBytes[i].toInt() and 0xFF
            val hi = pcmBytes[i + 1].toInt() shl 8
            val sample = (hi or lo).toShort().toDouble()
            sumSq += sample * sample
            samples++
            i += 2
        }
        if (samples == 0) return 0f
        val rms = sqrt(sumSq / samples)
        return (rms / 8500.0).toFloat().coerceIn(0f, 1f)
    }
}
