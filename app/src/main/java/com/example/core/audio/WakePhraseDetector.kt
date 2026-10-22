package com.example.core.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

class WakePhraseDetector(
    private val context: Context,
    private val modelManager: VoskModelManager,
    private val onWakePhraseDetected: () -> Unit
) {
    companion object {
        private const val TAG = "Aura"
        const val SAMPLE_RATE = 16000
        const val GRAMMAR_JSON = "[\"hey aura\",\"hi aura\",\"hello aura\",\"ok aura\",\"hey ora\",\"hey aara\",\"aura\",\"[unk]\"]"
    }

    private var isListening = false
    private var listeningJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var voskModel: Model? = null
    private var recognizer: Recognizer? = null

    private var lastTriggerTimestamp = 0L

    private val _lastHeardText = MutableStateFlow("")
    val lastHeardText: StateFlow<String> = _lastHeardText.asStateFlow()

    private val _isDetectorActive = MutableStateFlow(false)
    val isDetectorActive: StateFlow<Boolean> = _isDetectorActive.asStateFlow()

    var sensitivity: String = "normal" // strict, normal, loose

    @SuppressLint("MissingPermission")
    fun startListening(scope: CoroutineScope): Boolean {
        if (isListening) return true

        if (!modelManager.isModelReady()) {
            Log.w(TAG, "Cannot start WakePhraseDetector: Vosk model is not downloaded or ready.")
            return false
        }

        try {
            val modelPath = modelManager.getModelDirectory().absolutePath
            if (voskModel == null) {
                voskModel = Model(modelPath)
            }

            val rec = Recognizer(voskModel, SAMPLE_RATE.toFloat(), GRAMMAR_JSON)
            recognizer = rec

            val bufferSize = maxOf(
                AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
                4096
            )

            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize for WakePhraseDetector")
                record.release()
                return false
            }

            record.startRecording()
            audioRecord = record
            isListening = true
            _isDetectorActive.value = true

            Log.i(TAG, "WakePhraseDetector listening with Vosk model at $modelPath (sensitivity=$sensitivity)")

            listeningJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(4096)
                while (isActive && isListening) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val isFinal = rec.acceptWaveForm(buffer, read)
                        val resultJson = if (isFinal) rec.result else rec.partialResult
                        checkWakePhraseMatch(resultJson)
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Exception initializing WakePhraseDetector", e)
            stopListening()
            return false
        }
    }

    private fun checkWakePhraseMatch(jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            val text = (if (json.has("text")) json.optString("text") else json.optString("partial")).trim().lowercase()
            if (text.isNotBlank() && text != "[unk]") {
                _lastHeardText.value = text
                Log.d(TAG, "Vosk heard: $text")

                val matches = when (sensitivity.lowercase()) {
                    "strict" -> {
                        // Requires exact "hey aura", "hi aura", "hello aura", "ok aura"
                        text.contains("hey aura") || text.contains("hi aura") ||
                                text.contains("hello aura") || text.contains("ok aura")
                    }
                    "loose" -> {
                        // Any occurrence of "aura", "ora", or "aara"
                        text.contains("aura") || text.contains("ora") || text.contains("aara")
                    }
                    else -> { // "normal" (default)
                        // Trigger on aura/ora/aara with a preceding hey/hi/ok/hello or standalone "hey aura"
                        (text.contains("aura") || text.contains("ora") || text.contains("aara")) &&
                                (text.contains("hey") || text.contains("hi") || text.contains("hello") || text.contains("ok"))
                    }
                }

                if (matches) {
                    val now = System.currentTimeMillis()
                    if (now - lastTriggerTimestamp > 3000L) { // 3-second debounce
                        lastTriggerTimestamp = now
                        Log.i(TAG, "🎯 'Hey Aura' wake phrase triggered with text: '$text'")
                        // CRITICAL: Stop and release AudioRecord completely before invoking callback!
                        stopListening()
                        onWakePhraseDetected()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking wake phrase match from JSON: $jsonStr", e)
        }
    }

    /**
     * Completely stops listening and RELEASES AudioRecord and Recognizer,
     * so that the call pipeline can immediately take over the microphone.
     */
    fun stopListening() {
        isListening = false
        _isDetectorActive.value = false
        listeningJob?.cancel()
        listeningJob = null

        try {
            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audioRecord in WakePhraseDetector", e)
        }
        audioRecord = null

        try {
            recognizer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing Vosk recognizer", e)
        }
        recognizer = null
    }

    fun release() {
        stopListening()
        try {
            voskModel?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing Vosk model", e)
        }
        voskModel = null
    }
}
