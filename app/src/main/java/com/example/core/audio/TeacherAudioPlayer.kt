package com.example.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class TeacherAudioPlayer(context: Context) {

    companion object {
        private const val TAG = "TeacherAudioPlayer"
        private const val DEFAULT_SAMPLE_RATE = 24000

        fun estimateAudioDurationMs(
            audioBytes: ByteArray,
            mimeType: String,
            sampleRateHz: Int = DEFAULT_SAMPLE_RATE
        ): Long {
            if (audioBytes.isEmpty()) return 0L
            val resolvedRate = Regex("rate=(\\d+)", RegexOption.IGNORE_CASE)
                .find(mimeType)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.takeIf { it in 8000..48000 }
                ?: sampleRateHz
            val pcmLen = if (mimeType.contains("wav", ignoreCase = true) && audioBytes.size > 44) {
                audioBytes.size - 44
            } else {
                audioBytes.size
            }
            val frames = (pcmLen - (pcmLen % 2)) / 2L
            return ((frames * 1000L) / resolvedRate.toLong()).coerceAtLeast(300L)
        }
    }

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var activeTrack: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null
    private var localTts: TextToSpeech? = null
    private val cancelled = AtomicBoolean(false)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    suspend fun playAudioBytes(
        audioBytes: ByteArray,
        mimeType: String,
        sampleRateHz: Int = DEFAULT_SAMPLE_RATE,
        onPlaybackStarted: (() -> Unit)? = null
    ) {
        if (audioBytes.isEmpty()) return
        stopImmediately()
        cancelled.set(false)

        val resolvedSampleRate = Regex("rate=(\\d+)", RegexOption.IGNORE_CASE)
            .find(mimeType)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it in 8000..48000 }
            ?: sampleRateHz

        val rawPcm = if (mimeType.contains("wav", ignoreCase = true) && audioBytes.size > 44) {
            audioBytes.copyOfRange(44, audioBytes.size)
        } else {
            audioBytes
        }

        // Ensure 16-bit frame alignment and append short silence tail for clean drain.
        val alignedLen = rawPcm.size - (rawPcm.size % 2)
        if (alignedLen <= 0) return
        val tailSilenceBytes = ((resolvedSampleRate * 2) * 180 / 1000).let { it - (it % 2) }
        val pcmData = ByteArray(alignedLen + tailSilenceBytes)
        System.arraycopy(rawPcm, 0, pcmData, 0, alignedLen)

        val totalFrames = pcmData.size / 2
        val expectedDurationMs = (totalFrames.toLong() * 1000L) / resolvedSampleRate.toLong()

        withContext(Dispatchers.IO) {
            if (cancelled.get()) return@withContext
            requestFocus()

            val minBuf = AudioTrack.getMinBufferSize(
                resolvedSampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(8192)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(resolvedSampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            activeTrack = track
            try {
                track.play()
                _isPlaying.value = true
                onPlaybackStarted?.invoke()

                var offset = 0
                val chunkSize = 4096
                while (offset < pcmData.size && !cancelled.get()) {
                    val count = minOf(chunkSize, pcmData.size - offset)
                    val written = track.write(pcmData, offset, count)
                    if (written <= 0) break
                    offset += written
                }

                val startWaitMs = System.currentTimeMillis()
                val maxWaitMs = expectedDurationMs + 1500L
                val targetFrames = (alignedLen / 2).toLong()
                while (!cancelled.get()) {
                    val head = runCatching { track.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(targetFrames)
                    if (head >= targetFrames) break
                    if (System.currentTimeMillis() - startWaitMs > maxWaitMs) break
                    delay(40L)
                }
            } catch (e: Exception) {
                Log.e(TAG, "AudioTrack playback error", e)
            } finally {
                _isPlaying.value = false
                runCatching { track.stop() }
                runCatching { track.release() }
                if (activeTrack === track) {
                    activeTrack = null
                }
                abandonFocus()
            }
        }
    }

    /**
     * Device TTS fallback used only when cloud TTS is unavailable or Firebase AI Logic
     * is not yet provisioned, keeping spoken voice and whiteboard strokes synchronized.
     */
    suspend fun speakWithDeviceTtsFallback(
        text: String,
        language: String,
        onPlaybackStarted: (() -> Unit)? = null
    ) {
        val clean = text.trim()
        if (clean.isBlank()) return
        stopImmediately()
        cancelled.set(false)

        val initReady = CompletableDeferred<Boolean>()
        val doneSignal = CompletableDeferred<Unit>()
        val utteranceId = UUID.randomUUID().toString()

        var tts: TextToSpeech? = null
        withContext(Dispatchers.Main) {
            tts = TextToSpeech(appContext) { status ->
                initReady.complete(status == TextToSpeech.SUCCESS)
            }
            localTts = tts
        }

        val ready = withTimeoutOrNull(3_000L) { initReady.await() } == true
        val engine = tts
        if (!ready || engine == null || cancelled.get()) {
            withContext(Dispatchers.Main) {
                runCatching { engine?.shutdown() }
                if (localTts === engine) localTts = null
            }
            return
        }

        try {
            val locale = when {
                language.equals("Hindi", ignoreCase = true) ||
                    language.equals("Bhojpuri", ignoreCase = true) ||
                    language.equals("Maithili", ignoreCase = true) ||
                    clean.any { it in '\u0900'..'\u097F' } -> Locale.forLanguageTag("hi-IN")
                language.equals("Nepali", ignoreCase = true) -> Locale.forLanguageTag("ne-NP")
                else -> Locale.ENGLISH
            }
            runCatching { engine.language = locale }
            engine.setSpeechRate(1.02f)

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                    if (id == utteranceId) {
                        _isPlaying.value = true
                        onPlaybackStarted?.invoke()
                    }
                }

                override fun onDone(id: String?) {
                    if (id == utteranceId) {
                        _isPlaying.value = false
                        doneSignal.complete(Unit)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    if (id == utteranceId) {
                        _isPlaying.value = false
                        doneSignal.complete(Unit)
                    }
                }
            })

            withContext(Dispatchers.Main) {
                if (!cancelled.get()) {
                    engine.speak(clean, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
                } else {
                    doneSignal.complete(Unit)
                }
            }
            withTimeoutOrNull(35_000L) { doneSignal.await() }
        } finally {
            _isPlaying.value = false
            withContext(Dispatchers.Main) {
                runCatching { engine.stop() }
                runCatching { engine.shutdown() }
                if (localTts === engine) localTts = null
            }
        }
    }

    fun stopImmediately() {
        cancelled.set(true)
        _isPlaying.value = false
        val track = activeTrack
        activeTrack = null
        try {
            track?.pause()
            track?.flush()
            track?.stop()
        } catch (_: Exception) {
        } finally {
            runCatching { track?.release() }
        }
        val tts = localTts
        localTts = null
        if (tts != null) {
            runCatching { tts.stop() }
            runCatching { tts.shutdown() }
        }
        abandonFocus()
    }

    private fun requestFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS ||
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    ) {
                        stopImmediately()
                    }
                }
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { req ->
                runCatching { audioManager.abandonAudioFocusRequest(req) }
            }
            focusRequest = null
        }
    }
}
