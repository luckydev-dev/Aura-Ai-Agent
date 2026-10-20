package com.example.core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

class AudioRecordStreamer(
    private val onChunkEncoded: (String) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null

    private var recordingJob: Job? = null
    private var isRecording = false

    private val _micAmplitude = MutableStateFlow(0f)
    val micAmplitude: StateFlow<Float> = _micAmplitude.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val CHUNK_SIZE_BYTES = 1280 // 40ms of 16kHz 16-bit mono PCM
    }

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
    }

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        if (isRecording) return

        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufSize, CHUNK_SIZE_BYTES * 4)

        var record: AudioRecord? = null

        // Attempt 1: VOICE_COMMUNICATION (hardware AEC enabled)
        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                record = null
            }
        } catch (_: Exception) {
            record = null
        }

        // Attempt 2: Fallback to MIC
        if (record == null) {
            try {
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    return
                }
            } catch (_: Exception) {
                return
            }
        }

        val activeRecord = record ?: return

        // Attach hardware/OS audio enhancements if supported
        val sessionId = activeRecord.audioSessionId
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }
            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
            }
        } catch (_: Exception) {}

        try {
            activeRecord.startRecording()
            audioRecord = activeRecord
            isRecording = true

            recordingJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(CHUNK_SIZE_BYTES)
                while (isActive && isRecording) {
                    val read = activeRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        // Calculate RMS amplitude for orb reaction
                        var sum = 0.0
                        var i = 0
                        while (i < read - 1) {
                            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                            sum += sample * sample
                            i += 2
                        }
                        val rms = sqrt(sum / (read / 2.0))
                        val normalized = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
                        _micAmplitude.value = normalized

                        if (!_isMuted.value) {
                            val base64Chunk = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                            onChunkEncoded(base64Chunk)
                        } else {
                            _micAmplitude.value = 0f
                        }
                    }
                }
            }
        } catch (_: Exception) {
            stop()
        }
    }

    fun stop() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
        } catch (_: Exception) {}
        audioRecord = null

        try {
            echoCanceler?.release()
            noiseSuppressor?.release()
            gainControl?.release()
        } catch (_: Exception) {}
        echoCanceler = null
        noiseSuppressor = null
        gainControl = null

        _micAmplitude.value = 0f
    }
}
