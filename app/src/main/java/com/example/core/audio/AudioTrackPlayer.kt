package com.example.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.sqrt

class AudioTrackPlayer(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 24000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioTrack: AudioTrack? = null
    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()
    private var playbackJob: Job? = null
    private var isPlaying = false

    private val _speakerAmplitude = MutableStateFlow(0f)
    val speakerAmplitude: StateFlow<Float> = _speakerAmplitude.asStateFlow()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun start(scope: CoroutineScope) {
        if (isPlaying) return

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = true
        } catch (_: Exception) {}

        val minBufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufSize, 8192)

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .setEncoding(AUDIO_FORMAT)
            .build()

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    }
                }
                .build()

            audioTrack?.setVolume(1.0f)
            audioTrack?.play()
            isPlaying = true
        } catch (_: Exception) {
            isPlaying = false
            return
        }

        playbackJob = scope.launch(Dispatchers.IO) {
            while (isActive && isPlaying) {
                val chunk = audioQueue.poll()
                if (chunk != null && chunk.isNotEmpty()) {
                    // Compute amplitude
                    var sum = 0.0
                    var i = 0
                    while (i < chunk.size - 1) {
                        val sample = (chunk[i].toInt() and 0xFF) or (chunk[i + 1].toInt() shl 8)
                        sum += sample * sample
                        i += 2
                    }
                    val rms = sqrt(sum / (chunk.size / 2.0))
                    val norm = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
                    _speakerAmplitude.value = norm

                    audioTrack?.write(chunk, 0, chunk.size)
                } else {
                    _speakerAmplitude.value = 0f
                    delay(10)
                }
            }
        }
    }

    fun enqueueBase64Chunk(base64: String) {
        try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            if (bytes.isNotEmpty()) {
                audioQueue.add(bytes)
            }
        } catch (_: Exception) {}
    }

    /**
     * Instantly flushes the playback queue and stops current audio when barge-in occurs.
     */
    fun flushAndInterrupt() {
        audioQueue.clear()
        try {
            audioTrack?.apply {
                pause()
                flush()
                play()
            }
        } catch (_: Exception) {}
        _speakerAmplitude.value = 0f
    }

    fun setSpeakerphoneOn(on: Boolean) {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = on
        } catch (_: Exception) {}
    }

    fun stop() {
        isPlaying = false
        playbackJob?.cancel()
        playbackJob = null
        audioQueue.clear()

        try {
            audioTrack?.apply {
                pause()
                flush()
                stop()
                release()
            }
        } catch (_: Exception) {}
        audioTrack = null
        _speakerAmplitude.value = 0f

        try {
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}
    }
}
