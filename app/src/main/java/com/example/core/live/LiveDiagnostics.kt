package com.example.core.live

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DiagnosticLogEntry(
    val timestamp: Long,
    val tag: String,
    val message: String
)

class LiveDiagnostics {
    private val _connectionState = MutableStateFlow("Disconnected")
    val connectionState: StateFlow<String> = _connectionState.asStateFlow()

    private val _currentModel = MutableStateFlow("gemini-3.8-live")
    val currentModel: StateFlow<String> = _currentModel.asStateFlow()

    private val _lastCloseCodeAndReason = MutableStateFlow("None")
    val lastCloseCodeAndReason: StateFlow<String> = _lastCloseCodeAndReason.asStateFlow()

    private val _lastServerMessageTypes = MutableStateFlow<List<String>>(emptyList())
    val lastServerMessageTypes: StateFlow<List<String>> = _lastServerMessageTypes.asStateFlow()

    private val _bytesAudioSent = MutableStateFlow(0L)
    val bytesAudioSent: StateFlow<Long> = _bytesAudioSent.asStateFlow()

    private val _bytesAudioReceived = MutableStateFlow(0L)
    val bytesAudioReceived: StateFlow<Long> = _bytesAudioReceived.asStateFlow()

    private val _setupCompleteArrived = MutableStateFlow(false)
    val setupCompleteArrived: StateFlow<Boolean> = _setupCompleteArrived.asStateFlow()

    private val _micPermissionStatus = MutableStateFlow("Not Checked")
    val micPermissionStatus: StateFlow<String> = _micPermissionStatus.asStateFlow()

    private val _audioRecordState = MutableStateFlow("Idle")
    val audioRecordState: StateFlow<String> = _audioRecordState.asStateFlow()

    private val _audioTrackState = MutableStateFlow("Idle")
    val audioTrackState: StateFlow<String> = _audioTrackState.asStateFlow()

    private val _logHistory = MutableStateFlow<List<DiagnosticLogEntry>>(emptyList())
    val logHistory: StateFlow<List<DiagnosticLogEntry>> = _logHistory.asStateFlow()

    fun updateConnectionState(state: String) {
        _connectionState.value = state
    }

    fun setModel(model: String) {
        _currentModel.value = model
    }

    fun setLastClose(code: Int, reason: String) {
        _lastCloseCodeAndReason.value = "Code $code: $reason"
    }

    fun setSetupComplete(arrived: Boolean) {
        _setupCompleteArrived.value = arrived
    }

    fun setMicPermission(status: String) {
        _micPermissionStatus.value = status
    }

    fun setAudioRecordState(state: String) {
        _audioRecordState.value = state
    }

    fun setAudioTrackState(state: String) {
        _audioTrackState.value = state
    }

    fun addAudioSentBytes(bytes: Long) {
        _bytesAudioSent.value += bytes
    }

    fun recordAudioSent(bytes: Long) = addAudioSentBytes(bytes)

    fun addAudioReceivedBytes(bytes: Long) {
        _bytesAudioReceived.value += bytes
    }

    fun recordAudioReceived(bytes: Long) = addAudioReceivedBytes(bytes)

    fun recordServerMessageType(type: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val current = _lastServerMessageTypes.value.toMutableList()
        current.add(0, "[$time] $type")
        if (current.size > 50) current.removeLast()
        _lastServerMessageTypes.value = current
    }

    fun recordMessage(type: String) = recordServerMessageType(type)

    fun log(tag: String, message: String) {
        val entry = DiagnosticLogEntry(System.currentTimeMillis(), tag, message)
        val list = _logHistory.value.toMutableList()
        list.add(0, entry)
        if (list.size > 100) list.removeLast()
        _logHistory.value = list
    }

    fun getFullLogText(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val sb = StringBuilder()
        sb.append("=== AURA LIVE DIAGNOSTICS ===\n")
        sb.append("Timestamp: ${sdf.format(Date())}\n")
        sb.append("State: ${_connectionState.value}\n")
        sb.append("Model: ${_currentModel.value}\n")
        sb.append("SetupComplete: ${_setupCompleteArrived.value}\n")
        sb.append("Last Close: ${_lastCloseCodeAndReason.value}\n")
        sb.append("Audio Sent: ${_bytesAudioSent.value} bytes\n")
        sb.append("Audio Received: ${_bytesAudioReceived.value} bytes\n")
        sb.append("Mic Permission: ${_micPermissionStatus.value}\n")
        sb.append("AudioRecord State: ${_audioRecordState.value}\n")
        sb.append("AudioTrack State: ${_audioTrackState.value}\n\n")
        sb.append("--- Last 50 Server Messages ---\n")
        _lastServerMessageTypes.value.forEach { sb.append("$it\n") }
        sb.append("\n--- Event Log ---\n")
        _logHistory.value.forEach { entry ->
            sb.append("${sdf.format(Date(entry.timestamp))} [${entry.tag}] ${entry.message}\n")
        }
        return sb.toString()
    }
}
