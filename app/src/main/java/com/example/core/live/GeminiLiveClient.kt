package com.example.core.live

import android.util.Base64
import android.util.Log
import com.example.AuraApplication
import com.example.core.audio.AudioTrackPlayer
import com.example.core.tools.ToolRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class LiveConnectionState {
    object Disconnected : LiveConnectionState()
    object Connecting : LiveConnectionState()
    object Connected : LiveConnectionState()
    data class Reconnecting(val attempt: Int) : LiveConnectionState()
    data class Error(val message: String) : LiveConnectionState()
}

/**
 * Robust bidirectional WebSocket client for Gemini Live (BidiGenerateContent).
 * Supports text and binary frames (OkHttp ByteString), raw frame logging,
 * shared handshake construction with per-model profiles (thinkingConfig & non-blocking tools),
 * watchdog diagnostics, and audio I/O.
 */
class GeminiLiveClient(
    private val toolRegistry: ToolRegistry,
    private val audioPlayer: AudioTrackPlayer,
    private val diagnostics: LiveDiagnostics? = AuraApplication.instance.liveDiagnostics
) {
    companion object {
        private const val TAG = "Aura"
        const val DEFAULT_FALLBACK_MODEL = "gemini-3.8-live"
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var scope: CoroutineScope? = null

    private var reconnectAttempt = 0
    private var intentionalDisconnect = false
    private var terminalError = false
    private var isSetupComplete = false
    private var setupTimeoutJob: Job? = null
    private var reconnectJob: Job? = null

    // Session resumption handle returned by server
    private var resumptionHandle: String = ""

    private val _connectionState = MutableStateFlow<LiveConnectionState>(LiveConnectionState.Disconnected)
    val connectionState: StateFlow<LiveConnectionState> = _connectionState.asStateFlow()

    private val _userTranscript = MutableStateFlow("")
    val userTranscript: StateFlow<String> = _userTranscript.asStateFlow()

    private val _modelTranscript = MutableStateFlow("")
    val modelTranscript: StateFlow<String> = _modelTranscript.asStateFlow()

    private val _isAuraThinking = MutableStateFlow(false)
    val isAuraThinking: StateFlow<Boolean> = _isAuraThinking.asStateFlow()

    private val _isAuraActing = MutableStateFlow(false)
    val isAuraActing: StateFlow<Boolean> = _isAuraActing.asStateFlow()

    private val userTurnAccumulator = StringBuilder()
    private val modelTurnAccumulator = StringBuilder()

    // Config parameters cached for reconnection
    private var currentApiKey: String = ""
    private var currentModel: String = ""
    private var currentVoice: String = ""
    private var currentSystemPrompt: String = ""
    private var currentThinkingLevel: String = "LOW"

    fun connect(
        scope: CoroutineScope,
        apiKey: String,
        model: String,
        voice: String,
        systemPrompt: String,
        thinkingLevel: String = "LOW"
    ) {
        this.scope = scope
        this.currentApiKey = apiKey.trim()
        val rawModel = if (model.isBlank() || model == "gemini-2.0-flash-exp" || model == "gemini-live-2.5-flash-preview") {
            DEFAULT_FALLBACK_MODEL
        } else {
            model.trim()
        }
        this.currentModel = rawModel
        this.currentVoice = voice.ifBlank { "Aoede" }
        this.currentSystemPrompt = systemPrompt
        this.currentThinkingLevel = thinkingLevel.ifBlank { "LOW" }
        this.intentionalDisconnect = false
        this.terminalError = false
        this.reconnectAttempt = 0
        this.isSetupComplete = false

        diagnostics?.resetForNewConnection()
        diagnostics?.setAuthKey(this.currentApiKey)
        diagnostics?.setModel(this.currentModel)
        diagnostics?.setSetupComplete(false)
        diagnostics?.updateConnectionState("Connecting")
        diagnostics?.log(TAG, "Starting Live connection with model: $currentModel, voice: $currentVoice, thinking: $currentThinkingLevel, key: ${GeminiLiveHandshake.maskApiKey(currentApiKey)}")

        startWebSocketConnection()
    }

    private fun startWebSocketConnection() {
        if (currentApiKey.isBlank()) {
            val errorMsg = "Gemini API Key is missing. Set it in Settings."
            Log.e(TAG, errorMsg)
            terminalError = true
            _connectionState.value = LiveConnectionState.Error(errorMsg)
            _modelTranscript.value = errorMsg
            diagnostics?.updateConnectionState("Error: Missing API key")
            return
        }

        _connectionState.value = if (reconnectAttempt == 0) {
            LiveConnectionState.Connecting
        } else {
            LiveConnectionState.Reconnecting(reconnectAttempt)
        }

        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$currentApiKey"
        val request = Request.Builder().url(url).build()

        isSetupComplete = false
        diagnostics?.setSetupComplete(false)
        diagnostics?.updateConnectionState(if (reconnectAttempt == 0) "Connecting" else "Reconnecting ($reconnectAttempt/5)")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val httpCode = response.code
                diagnostics?.setSocketOpened(httpCode)
                Log.i(TAG, "WebSocket connected HTTP $httpCode. Sending setup message...")
                diagnostics?.log(TAG, "WebSocket connected HTTP $httpCode")
                scope?.launch(Dispatchers.IO) {
                    sendSetupMessage(webSocket)
                    startSetupWatchdog()
                }
            }

            // TEXT FRAME
            override fun onMessage(webSocket: WebSocket, text: String) {
                processIncomingFrame(text, isBinary = false)
            }

            // BINARY FRAME (OkHttp ByteString, handles JSON delivered as binary)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                processIncomingFrame(bytes.utf8(), isBinary = true)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                val hint = GeminiLiveHandshake.getAuthHintForError(currentApiKey, reason)
                val msg = buildString {
                    append("Server closed: $code $reason")
                    if (hint != null) append("\n$hint")
                }
                Log.e(TAG, "WebSocket onClosing: $msg")
                diagnostics?.setLastClose(code, reason)
                diagnostics?.log(TAG, msg)

                setupTimeoutJob?.cancel()

                val isTerminal = code in 1007..1008 || code == 400 || code == 401 || code == 403 ||
                        reason.contains("not found", ignoreCase = true) ||
                        reason.contains("invalid", ignoreCase = true) ||
                        reason.contains("API_KEY", ignoreCase = true) ||
                        reason.contains("API key", ignoreCase = true) ||
                        reason.contains("ACCESS_TOKEN_TYPE_UNSUPPORTED", ignoreCase = true)

                if (isTerminal) {
                    terminalError = true
                }

                _connectionState.value = LiveConnectionState.Error(msg)
                _modelTranscript.value = msg
                webSocket.close(code, null)

                if (!intentionalDisconnect && !isTerminal) {
                    scheduleReconnect("Server closed: $code $reason")
                } else {
                    diagnostics?.updateConnectionState("Error ($code): $reason")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket onClosed: $code $reason")
                diagnostics?.setLastClose(code, reason)
                setupTimeoutJob?.cancel()

                // Do NOT reconnect or touch the state if terminalError is set or the state is already Error
                if (terminalError || _connectionState.value is LiveConnectionState.Error) {
                    Log.w(TAG, "WebSocket onClosed: terminalError=$terminalError or state is Error. Not reconnecting.")
                    return
                }

                if (!intentionalDisconnect) {
                    scheduleReconnect("Connection closed: $reason")
                } else {
                    _connectionState.value = LiveConnectionState.Disconnected
                    diagnostics?.updateConnectionState("Disconnected")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val respBody = runCatching { response?.body?.string() }.getOrNull() ?: ""
                val httpStatus = response?.code
                val maskedKey = GeminiLiveHandshake.maskApiKey(currentApiKey)
                val hint = GeminiLiveHandshake.getAuthHintForError(currentApiKey, "$httpStatus $respBody ${t.message}")

                val errMessage = buildString {
                    if (httpStatus != null) append("HTTP $httpStatus: ")
                    append(t.message ?: "Network failure")
                    if (respBody.isNotBlank()) append(" - ").append(respBody.take(300))
                    append(" (Key: $maskedKey)")
                    if (hint != null) append("\n$hint")
                }
                Log.e(TAG, "WebSocket onFailure: $errMessage", t)
                diagnostics?.log(TAG, "onFailure: $errMessage")
                diagnostics?.setLastClose(httpStatus ?: -1, errMessage)

                setupTimeoutJob?.cancel()

                val isTerminal = (httpStatus != null && httpStatus in 400..499) ||
                        errMessage.contains("not found", ignoreCase = true) ||
                        errMessage.contains("invalid", ignoreCase = true) ||
                        errMessage.contains("API_KEY", ignoreCase = true) ||
                        errMessage.contains("API key", ignoreCase = true) ||
                        errMessage.contains("ACCESS_TOKEN_TYPE_UNSUPPORTED", ignoreCase = true)

                if (isTerminal) {
                    terminalError = true
                }

                _connectionState.value = LiveConnectionState.Error(errMessage)
                _modelTranscript.value = "Connection error: $errMessage"
                diagnostics?.updateConnectionState("Error: $errMessage")

                if (!intentionalDisconnect && !isTerminal) {
                    scheduleReconnect(errMessage)
                }
            }
        })
    }

    private fun startSetupWatchdog() {
        setupTimeoutJob?.cancel()
        setupTimeoutJob = scope?.launch {
            delay(10000L)
            if (!isSetupComplete && _connectionState.value !is LiveConnectionState.Error) {
                val framesCount = diagnostics?.framesReceivedCount?.value ?: 0
                val upgradeCode = diagnostics?.httpUpgradeResponseCode?.value ?: 101

                val baseTimeoutMsg = if (framesCount == 0) {
                    "Setup timeout: Socket open (HTTP $upgradeCode) but server sent nothing within 10s (check API key type / model)"
                } else {
                    val firstFrame = diagnostics?.firstFrameReceivedContent?.value ?: ""
                    "Setup timeout: Received $framesCount frame(s) but no setupComplete arrived within 10s. First frame: ${firstFrame.take(200)}"
                }

                val hint = GeminiLiveHandshake.getAuthHintForError(currentApiKey, baseTimeoutMsg)
                val fullTimeoutMsg = if (hint != null) "$baseTimeoutMsg\n$hint" else baseTimeoutMsg

                Log.e(TAG, fullTimeoutMsg)
                diagnostics?.log(TAG, fullTimeoutMsg)
                terminalError = true
                _connectionState.value = LiveConnectionState.Error(fullTimeoutMsg)
                _modelTranscript.value = fullTimeoutMsg
                diagnostics?.updateConnectionState("Error: Setup timeout")
                webSocket?.close(1000, "Setup timeout")
            }
        }
    }

    private suspend fun sendSetupMessage(ws: WebSocket) {
        val toolDeclarations = toolRegistry.getToolDeclarationsJson()
        val setupPayload = GeminiLiveHandshake.buildSetupMessageJson(
            model = currentModel,
            voice = currentVoice,
            systemPrompt = currentSystemPrompt,
            resumptionHandle = resumptionHandle,
            toolDeclarationsJson = toolDeclarations,
            thinkingLevel = currentThinkingLevel
        )

        Log.i(TAG, "Sending setup message for model $currentModel (thinkingLevel=$currentThinkingLevel) with ${toolRegistry.getAllTools().size} tools (voice=$currentVoice)")
        diagnostics?.log(TAG, "Sent setup JSON (${setupPayload.length} chars, model=$currentModel)")
        ws.send(setupPayload)
    }

    /**
     * Unified handler for incoming WebSocket frames (both text and binary utf8).
     */
    private fun processIncomingFrame(rawText: String, isBinary: Boolean) {
        val parsed = GeminiLiveHandshake.parseAndLogIncomingFrame(rawText, isBinary, diagnostics)

        if (parsed.isSetupComplete) {
            isSetupComplete = true
            reconnectAttempt = 0
            terminalError = false
            setupTimeoutJob?.cancel()
            _connectionState.value = LiveConnectionState.Connected
            diagnostics?.setSetupComplete(true)
            diagnostics?.updateConnectionState("Connected")
            diagnostics?.log(TAG, "setupComplete received successfully (${if (isBinary) "binary" else "text"} frame)")
            Log.i(TAG, "Gemini Live setupComplete received! Live session active.")
        }

        if (parsed.resumptionHandle != null) {
            resumptionHandle = parsed.resumptionHandle
            diagnostics?.log(TAG, "Session resumption handle: ${resumptionHandle.take(12)}...")
        }

        if (parsed.isGoAway) {
            Log.w(TAG, "Server issued goAway: ${parsed.goAwayReason}")
            diagnostics?.log(TAG, "Server goAway: ${parsed.goAwayReason}")
            disconnectInternal(reconnect = true)
            return
        }

        val root = parsed.rawJson ?: return

        try {
            // Process serverContent
            if (root.has("serverContent")) {
                val serverContent = root.getJSONObject("serverContent")

                // Accumulated input transcription (User speech recognized by server)
                if (serverContent.has("inputAudioTranscription")) {
                    val inTrans = serverContent.getJSONObject("inputAudioTranscription")
                    val transcript = inTrans.optString("text")
                    if (transcript.isNotBlank()) {
                        userTurnAccumulator.append(transcript)
                        _userTranscript.value = userTurnAccumulator.toString()
                    }
                }

                // Accumulated output transcription (Model speech converted to text)
                if (serverContent.has("outputAudioTranscription")) {
                    val outTrans = serverContent.getJSONObject("outputAudioTranscription")
                    val transcript = outTrans.optString("text")
                    if (transcript.isNotBlank()) {
                        modelTurnAccumulator.append(transcript)
                        _modelTranscript.value = modelTurnAccumulator.toString()
                    }
                }

                if (serverContent.has("modelTurn")) {
                    _isAuraThinking.value = false
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)

                            // Audio parts
                            if (part.has("inlineData")) {
                                val inlineData = part.getJSONObject("inlineData")
                                val base64Data = inlineData.optString("data")
                                if (base64Data.isNotBlank()) {
                                    val pcmBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                    diagnostics?.recordAudioReceived(pcmBytes.size.toLong())
                                    audioPlayer.enqueuePcm(pcmBytes)
                                }
                            }

                            // Text parts
                            if (part.has("text")) {
                                val chunkText = part.getString("text")
                                modelTurnAccumulator.append(chunkText)
                                _modelTranscript.value = modelTurnAccumulator.toString()
                            }
                        }
                    }
                }

                // User interrupted Aura speech
                if (serverContent.optBoolean("interrupted", false)) {
                    Log.i(TAG, "Model speech interrupted by user")
                    audioPlayer.flushAndInterrupt()
                    _isAuraThinking.value = false
                }

                // Turn completed by model
                if (serverContent.optBoolean("turnComplete", false)) {
                    _isAuraThinking.value = false
                    userTurnAccumulator.setLength(0)
                }

                if (serverContent.optBoolean("generationComplete", false)) {
                    _isAuraThinking.value = false
                }
            }

            // Handle tool calls / function calls
            if (root.has("toolCall")) {
                val toolCall = root.getJSONObject("toolCall")
                handleToolCall(toolCall)
            }

            if (root.has("toolCallCancellation")) {
                Log.w(TAG, "Tool calls cancelled by server")
                _isAuraActing.value = false
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing server message content: ${e.message}", e)
            diagnostics?.log(TAG, "Content dispatch error: ${e.message}")
        }
    }

    /**
     * Sends realtime PCM audio chunk (16kHz 16-bit mono)
     * Format: {"realtimeInput":{"audio":{"mimeType":"audio/pcm;rate=16000","data":"<base64>"}}}
     */
    fun sendAudioChunk(base64Pcm: String) {
        val ws = webSocket ?: return
        if (!isSetupComplete || _connectionState.value != LiveConnectionState.Connected) return

        try {
            val root = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("audio", JSONObject().apply {
                        put("mimeType", "audio/pcm;rate=16000")
                        put("data", base64Pcm)
                    })
                })
            }
            ws.send(root.toString())
            val byteCount = base64Pcm.length * 3 / 4
            diagnostics?.recordAudioSent(byteCount.toLong())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio chunk", e)
        }
    }

    /**
     * Sends user text turn into the Live session.
     */
    fun sendTextMessage(text: String) {
        val ws = webSocket ?: return
        if (!isSetupComplete || text.isBlank()) return

        try {
            userTurnAccumulator.setLength(0)
            userTurnAccumulator.append(text)
            _userTranscript.value = text

            val root = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", text) })
                            })
                        })
                    })
                    put("turnComplete", true)
                })
            }
            ws.send(root.toString())
            diagnostics?.recordMessage("clientContent(text)")
            Log.d(TAG, "Sent text message to Live: $text")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send text message", e)
        }
    }

    private fun handleToolCall(toolCall: JSONObject) {
        val functionCalls = toolCall.optJSONArray("functionCalls") ?: return
        val isExtendedThinking = currentModel.removePrefix("models/").trim().equals("gemini-3.8-live-extended-thinking", ignoreCase = true)

        if (!isExtendedThinking) {
            _isAuraActing.value = true
        }

        scope?.launch(Dispatchers.Default) {
            executeToolsAsync(functionCalls, isExtendedThinking)
        }
    }

    private suspend fun executeToolsAsync(functionCalls: JSONArray, isExtendedThinking: Boolean) = coroutineScope {
        val deferredResponses = (0 until functionCalls.length()).map { i ->
            val callObj = functionCalls.optJSONObject(i)
            callObj?.let { call ->
                val callId = call.optString("id")
                val toolName = call.optString("name")
                val argsObj = call.optJSONObject("args")
                val argsMap = mutableMapOf<String, Any>()
                if (argsObj != null) {
                    val keys = argsObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        argsMap[k] = argsObj.get(k)
                    }
                }

                async(Dispatchers.IO) {
                    try {
                        Log.i(TAG, "Executing tool '$toolName' id=$callId with args: $argsMap (nonBlocking=$isExtendedThinking)")
                        val result = toolRegistry.executeTool(toolName, argsMap)

                        val respPayload = JSONObject().apply {
                            put("id", callId)
                            put("name", toolName)
                            val responseObj = JSONObject().apply {
                                put("result", result.output)
                                put("success", result.success)
                                if (isExtendedThinking) {
                                    put("scheduling", "WHEN_IDLE")
                                }
                            }
                            put("response", responseObj)
                            if (isExtendedThinking) {
                                put("scheduling", "WHEN_IDLE")
                            }
                        }
                        diagnostics?.log(TAG, "Tool '$toolName' finished (${result.success})")
                        if (isExtendedThinking) {
                            sendToolResponse(listOf(respPayload))
                        }
                        respPayload
                    } catch (e: Exception) {
                        Log.e(TAG, "Tool execution failed for '$toolName'", e)
                        val errPayload = JSONObject().apply {
                            put("id", callId)
                            put("name", toolName)
                            val responseObj = JSONObject().apply {
                                put("error", e.message ?: "Execution failed")
                                put("success", false)
                                if (isExtendedThinking) {
                                    put("scheduling", "WHEN_IDLE")
                                }
                            }
                            put("response", responseObj)
                            if (isExtendedThinking) {
                                put("scheduling", "WHEN_IDLE")
                            }
                        }
                        if (isExtendedThinking) {
                            sendToolResponse(listOf(errPayload))
                        }
                        errPayload
                    }
                }
            }
        }.filterNotNull()

        val results = deferredResponses.awaitAll()
        if (!isExtendedThinking) {
            sendToolResponse(results)
        }
    }

    private fun sendToolResponse(responses: List<JSONObject>) {
        val ws = webSocket ?: return
        if (responses.isEmpty()) return

        try {
            val responsesArray = JSONArray()
            for (r in responses) {
                responsesArray.put(r)
            }

            val root = JSONObject().apply {
                put("toolResponse", JSONObject().apply {
                    put("functionResponses", responsesArray)
                })
            }

            ws.send(root.toString())
            _isAuraActing.value = false
            diagnostics?.recordMessage("toolResponse(${responses.size})")
            Log.i(TAG, "Sent tool responses for ${responses.size} tools to Live API")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send toolResponse", e)
            _isAuraActing.value = false
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (terminalError || _connectionState.value is LiveConnectionState.Error) {
            Log.w(TAG, "Bypassing reconnect: terminalError=$terminalError, state=${_connectionState.value}")
            return
        }

        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            val capMsg = "Reconnection failed after $MAX_RECONNECT_ATTEMPTS attempts: $reason"
            Log.e(TAG, capMsg)
            terminalError = true
            _connectionState.value = LiveConnectionState.Error(capMsg)
            diagnostics?.updateConnectionState("Error: Reconnect cap reached")
            diagnostics?.log(TAG, capMsg)
            return
        }

        reconnectAttempt++
        _connectionState.value = LiveConnectionState.Reconnecting(reconnectAttempt)
        diagnostics?.updateConnectionState("Reconnecting ($reconnectAttempt/5)")

        reconnectJob?.cancel()
        reconnectJob = scope?.launch {
            val backoffMs = (1000L * (1L shl (reconnectAttempt - 1))).coerceAtMost(30000L)
            Log.i(TAG, "Scheduling reconnect attempt $reconnectAttempt in ${backoffMs}ms due to: $reason")
            diagnostics?.log(TAG, "Reconnect in ${backoffMs}ms (attempt $reconnectAttempt/5)")
            delay(backoffMs)
            startWebSocketConnection()
        }
    }

    private fun disconnectInternal(reconnect: Boolean) {
        setupTimeoutJob?.cancel()
        reconnectJob?.cancel()

        try {
            webSocket?.close(1000, "Normal closure")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing websocket", e)
        }
        webSocket = null
        isSetupComplete = false

        if (reconnect) {
            scheduleReconnect("goAway received")
        } else {
            _connectionState.value = LiveConnectionState.Disconnected
            diagnostics?.updateConnectionState("Disconnected")
        }
    }

    fun disconnect() {
        intentionalDisconnect = true
        terminalError = false
        setupTimeoutJob?.cancel()
        reconnectJob?.cancel()

        try {
            webSocket?.close(1000, "User disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing websocket on disconnect", e)
        }
        webSocket = null
        isSetupComplete = false
        _connectionState.value = LiveConnectionState.Disconnected
        diagnostics?.updateConnectionState("Disconnected")
        diagnostics?.log(TAG, "User disconnected call")
    }
}
