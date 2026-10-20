package com.example.core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import kotlin.math.sqrt

class WakePhraseDetector(
    private val onWakePhraseDetected: () -> Unit
) {
    private var isListening = false
    private var job: Job? = null
    private var audioRecord: AudioRecord? = null

    @SuppressLint("MissingPermission")
    fun startListening(scope: CoroutineScope) {
        if (isListening) return

        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return
            }

            record.startRecording()
            audioRecord = record
            isListening = true

            job = scope.launch(Dispatchers.IO) {
                val buffer = ShortArray(1600) // 100ms
                var voiceFramesCount = 0

                while (isActive && isListening) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        var sum = 0.0
                        for (i in 0 until read) {
                            sum += buffer[i] * buffer[i]
                        }
                        val rms = sqrt(sum / read)
                        // Trigger voice threshold (distinct human speech cadence over 3 consecutive windows)
                        if (rms > 1200) {
                            voiceFramesCount++
                            if (voiceFramesCount >= 3) {
                                voiceFramesCount = 0
                                withContext(Dispatchers.Main) {
                                    onWakePhraseDetected()
                                }
                                delay(2000) // Debounce
                            }
                        } else {
                            if (voiceFramesCount > 0) voiceFramesCount--
                        }
                    }
                }
            }
        } catch (_: Exception) {
            stopListening()
        }
    }

    fun stopListening() {
        isListening = false
        job?.cancel()
        job = null
        try {
            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) stop()
                release()
            }
        } catch (_: Exception) {}
        audioRecord = null
    }
}
