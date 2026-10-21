package com.example.core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import android.util.Log
import com.example.AuraApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

enum class AudioRecordState {
    IDLE, RECORDING, INIT_FAILED, PERMISSION_DENIED
}

class AudioRecordStreamer(
    private val onChunkEncoded: (String) -> Unit
) {
    companion object {
        private const val TAG = "Aura"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val CHUNK_SIZE_BYTES = 1280 // 40ms of 16kHz 16-bit mono PCM
    }

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

    private val _recordState = MutableStateFlow(AudioRecordState.IDLE)
    val recordState: StateFlow<AudioRecordState> = _recordState.asStateFlow()

    private val diagnostics = runCatching { AuraApplication.instance.liveDiagnostics }.getOrNull()

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
    }

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope): Boolean {
        if (isRecording) return true

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
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord VOICE_COMMUNICATION failed to init, trying MIC fallback: ${e.message}")
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
                    _recordState.value = AudioRecordState.INIT_FAILED
                    diagnostics?.setAudioRecordState("Init Failed (MIC uninitialized)")
                    return false
                }
            } catch (e: Exception) {
                Log.e(TAG, "AudioRecord MIC init failed", e)
                _recordState.value = AudioRecordState.INIT_FAILED
                diagnostics?.setAudioRecordState("Init Failed: ${e.message}")
                return false
            }
        }

        val activeRecord = record ?: run {
            _recordState.value = AudioRecordState.INIT_FAILED
            diagnostics?.setAudioRecordState("Init Failed")
            return false
        }

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
        } catch (e: Exception) {
            Log.w(TAG, "Audio effect attachment notice: ${e.message}")
        }

        try {
            activeRecord.startRecording()
            audioRecord = activeRecord
            isRecording = true
            _recordState.value = AudioRecordState.RECORDING
            diagnostics?.setAudioRecordState("Recording (16kHz Mono)")

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
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            _recordState.value = AudioRecordState.INIT_FAILED
            diagnostics?.setAudioRecordState("Start Failed: ${e.message}")
            stop()
            return false
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
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audioRecord", e)
        }
        audioRecord = null

        try {
            echoCanceler?.release()
            noiseSuppressor?.release()
            gainControl?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio effects", e)
        }
        echoCanceler = null
        noiseSuppressor = null
        gainControl = null

        _micAmplitude.value = 0f
        _recordState.value = AudioRecordState.IDLE
        diagnostics?.setAudioRecordState("Idle")
    }
}
