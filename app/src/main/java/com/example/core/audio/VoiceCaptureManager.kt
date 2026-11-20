package com.example.core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class VoiceCaptureManager {

    companion object {
        private const val TAG = "VoiceCaptureManager"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val MAX_PCM_BYTES = SAMPLE_RATE * 2 * 30 // Bounded 30-second buffer (~960 KB)
    }

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val pcmBuffer = ByteArrayOutputStream()
    private val bufferLock = Any()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    @SuppressLint("MissingPermission")
    fun startCapture(scope: CoroutineScope, onError: (String) -> Unit) {
        if (_isRecording.value) return
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuf <= 0) {
            onError("Microphone audio format not supported on this device.")
            return
        }
        val bufferSize = (minBuf * 2).coerceAtLeast(4096)
        try {
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                onError("Unable to initialize microphone recorder.")
                return
            }
            synchronized(bufferLock) {
                pcmBuffer.reset()
            }
            audioRecord = recorder
            recorder.startRecording()
            _isRecording.value = true

            captureJob = scope.launch(Dispatchers.IO) {
                val readChunk = ByteArray(bufferSize)
                while (isActive && _isRecording.value) {
                    val bytesRead = recorder.read(readChunk, 0, readChunk.size)
                    if (bytesRead > 0) {
                        synchronized(bufferLock) {
                            if (pcmBuffer.size() + bytesRead <= MAX_PCM_BYTES) {
                                pcmBuffer.write(readChunk, 0, bytesRead)
                            }
                        }
                        _amplitude.value = computeNormalizedRms(readChunk, bytesRead)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Microphone permission missing", e)
            onError("Microphone permission is required for voice input.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio capture", e)
            onError(e.localizedMessage ?: "Failed to start microphone capture.")
        }
    }

    fun stopAndBuildWav(): ByteArray? {
        _isRecording.value = false
        _amplitude.value = 0f
        captureJob?.cancel()
        captureJob = null

        val recorder = audioRecord
        audioRecord = null
        try {
            recorder?.stop()
        } catch (_: Exception) {
        } finally {
            try {
                recorder?.release()
            } catch (_: Exception) {
            }
        }

        val pcmBytes = synchronized(bufferLock) {
            val out = pcmBuffer.toByteArray()
            pcmBuffer.reset()
            out
        }
        // Require at least ~0.25s of audio
        if (pcmBytes.size < SAMPLE_RATE / 2) return null
        return wrapPcmAsWav(pcmBytes, SAMPLE_RATE, 1, 16)
    }

    fun cancelCapture() {
        _isRecording.value = false
        _amplitude.value = 0f
        captureJob?.cancel()
        captureJob = null
        val recorder = audioRecord
        audioRecord = null
        try {
            recorder?.stop()
        } catch (_: Exception) {
        } finally {
            try {
                recorder?.release()
            } catch (_: Exception) {
            }
        }
        synchronized(bufferLock) {
            pcmBuffer.reset()
        }
    }

    private fun computeNormalizedRms(buffer: ByteArray, length: Int): Float {
        if (length < 2) return 0f
        var sumSq = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < length) {
            val lo = buffer[i].toInt() and 0xFF
            val hi = buffer[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toDouble()
            sumSq += sample * sample
            samples++
            i += 2
        }
        if (samples == 0) return 0f
        val rms = sqrt(sumSq / samples)
        return (rms / 12000.0).toFloat().coerceIn(0f, 1f)
    }

    private fun wrapPcmAsWav(
        pcmData: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): ByteArray {
        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(totalDataLen)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * bitsPerSample / 8).toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcmData.size)
        }.array()
        return header + pcmData
    }
}
