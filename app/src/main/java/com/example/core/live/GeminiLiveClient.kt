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
 * Handles setup handshake, realtime PCM audio I/O, server turns, tool calls, and diagnostics.
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

    fun connect(
        scope: CoroutineScope,
        apiKey: String,
        model: String,
        voice: String,
        systemPrompt: String
    ) {
        this.scope = scope
        this.currentApiKey = apiKey.trim()
        val rawModel = if (model.isBlank() || model == "gemini-2.0-flash-exp") DEFAULT_FALLBACK_MODEL else model.trim()
        this.currentModel = rawModel
        this.currentVoice = voice.ifBlank { "Aoede" }
        this.currentSystemPrompt = systemPrompt
        this.intentionalDisconnect = false
        this.terminalError = false
        this.reconnectAttempt = 0
        this.isSetupComplete = false

        diagnostics?.setModel(this.currentModel)
        diagnostics?.setSetupComplete(false)
        diagnostics?.updateConnectionState("Connecting")
        diagnostics?.log(TAG, "Starting Live connection with model: $currentModel, voice: $currentVoice")

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
                Log.i(TAG, "WebSocket connected. Sending setup message...")
                diagnostics?.log(TAG, "WebSocket connected HTTP ${response.code}")
                scope?.launch(Dispatchers.IO) {
                    sendSetupMessage(webSocket)
                    startSetupWatchdog()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                val msg = "Server closed: $code $reason"
                Log.e(TAG, "WebSocket onClosing: $msg")
                diagnostics?.setLastClose(code, reason)
                diagnostics?.log(TAG, msg)

                setupTimeoutJob?.cancel()

                val isTerminal = code in 1007..1008 || code == 400 || code == 403 ||
                        reason.contains("not found", ignoreCase = true) ||
                        reason.contains("invalid", ignoreCase = true) ||
                        reason.contains("API_KEY", ignoreCase = true) ||
                        reason.contains("API key", ignoreCase = true)

                if (isTerminal) {
                    terminalError = true
                }

                _connectionState.value = LiveConnectionState.Error(msg)
                _modelTranscript.value = "Server closed connection: $reason"
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
                val errMessage = buildString {
                    if (httpStatus != null) append("HTTP $httpStatus: ")
                    append(t.message ?: "Network failure")
                    if (respBody.isNotBlank()) append(" - ").append(respBody.take(200))
                }
                Log.e(TAG, "WebSocket onFailure: $errMessage", t)
                diagnostics?.log(TAG, "onFailure: $errMessage")
                diagnostics?.setLastClose(httpStatus ?: -1, errMessage)

                setupTimeoutJob?.cancel()

                val isTerminal = (httpStatus != null && httpStatus in 400..499) ||
                        errMessage.contains("not found", ignoreCase = true) ||
                        errMessage.contains("invalid", ignoreCase = true) ||
                        errMessage.contains("API_KEY", ignoreCase = true) ||
                        errMessage.contains("API key", ignoreCase = true)

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
                val timeoutMsg = "Setup timeout: setupComplete not received from server within 10s"
                Log.e(TAG, timeoutMsg)
                diagnostics?.log(TAG, timeoutMsg)
                terminalError = true
                _connectionState.value = LiveConnectionState.Error(timeoutMsg)
                _modelTranscript.value = timeoutMsg
                diagnostics?.updateConnectionState("Error: $timeoutMsg")
                webSocket?.close(1000, "Setup timeout")
            }
        }
    }

    private suspend fun sendSetupMessage(ws: WebSocket) {
        val modelPath = if (currentModel.startsWith("models/")) currentModel else "models/$currentModel"

        val setupObj = JSONObject().apply {
            put("model", modelPath)
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().apply { put("AUDIO") })
                put("speechConfig", JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().apply {
                            put("voiceName", currentVoice)
                        })
                    })
                })
            })

            // Transcriptions
            put("inputAudioTranscription", JSONObject())
            put("outputAudioTranscription", JSONObject())

            // Resumption (only if handle was provided by previous server session)
            if (resumptionHandle.isNotBlank()) {
                put("sessionResumption", JSONObject().apply {
                    put("handle", resumptionHandle)
                })
            }

            if (currentSystemPrompt.isNotBlank()) {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", currentSystemPrompt) })
                    })
                })
            }

            // Function declarations for tools
            val toolDeclarations = toolRegistry.getToolDeclarationsJson()
            if (toolDeclarations.isNotEmpty()) {
                val declarationsArray = JSONArray()
                for (tool in toolDeclarations) {
                    declarationsArray.put(JSONObject(tool))
                }
                put("tools", JSONArray().apply {
                    put(JSONObject().apply {
                        put("functionDeclarations", declarationsArray)
                    })
                })
            }
        }

        val root = JSONObject().apply {
            put("setup", setupObj)
        }

        Log.i(TAG, "Sending setup message for model $modelPath with ${toolRegistry.getAllTools().size} tools")
        ws.send(root.toString())
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

    private fun handleServerMessage(text: String) {
        try {
            val root = JSONObject(text)
            val rootKeys = root.keys().asSequence().toList()
            for (key in rootKeys) {
                diagnostics?.recordMessage(key)
            }

            if (root.has("setupComplete")) {
                isSetupComplete = true
                reconnectAttempt = 0
                terminalError = false
                setupTimeoutJob?.cancel()
                _connectionState.value = LiveConnectionState.Connected
                diagnostics?.setSetupComplete(true)
                diagnostics?.updateConnectionState("Connected")
                diagnostics?.log(TAG, "setupComplete received successfully")
                Log.i(TAG, "Gemini Live setupComplete received! Live session active.")
            }

            // Session resumption update
            if (root.has("sessionResumptionUpdate")) {
                val sru = root.getJSONObject("sessionResumptionUpdate")
                val handle = sru.optString("handle")
                if (handle.isNotBlank()) {
                    resumptionHandle = handle
                    diagnostics?.log(TAG, "Session resumption handle received: ${handle.take(12)}...")
                }
            }

            // Server GoAway (server requests reconnection)
            if (root.has("goAway")) {
                val goAway = root.getJSONObject("goAway")
                val reason = goAway.optString("reason", "Server requested goAway")
                Log.w(TAG, "Server issued goAway: $reason")
                diagnostics?.log(TAG, "Server goAway: $reason")
                disconnectInternal(reconnect = true)
                return
            }

            // Process serverContent
            if (root.has("serverContent")) {
                val serverContent = root.getJSONObject("serverContent")

                // Accumulated input transcription (User's speech recognized by server)
                if (serverContent.has("inputAudioTranscription")) {
                    val inTrans = serverContent.getJSONObject("inputAudioTranscription")
                    val transcript = inTrans.optString("text")
                    if (transcript.isNotBlank()) {
                        userTurnAccumulator.append(transcript)
                        _userTranscript.value = userTurnAccumulator.toString()
                    }
                }

                // Accumulated output transcription (Model's speech converted to text)
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

                // User interrupted Aura's speech
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
            Log.e(TAG, "Error handling server message: ${e.message}", e)
        }
    }

    private fun handleToolCall(toolCall: JSONObject) {
        val functionCalls = toolCall.optJSONArray("functionCalls") ?: return
        _isAuraActing.value = true

        scope?.launch(Dispatchers.Default) {
            executeToolsAsync(functionCalls)
        }
    }

    private suspend fun executeToolsAsync(functionCalls: JSONArray) = coroutineScope {
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
                        Log.i(TAG, "Executing tool '$toolName' id=$callId with args: $argsMap")
                        val result = toolRegistry.executeTool(toolName, argsMap)
                        val respPayload = JSONObject().apply {
                            put("success", result.success)
                            put("output", result.output)
                            if (result.requiresConfirmation) {
                                put("requiresConfirmation", true)
                                put("confirmationPrompt", result.confirmationPrompt)
                            }
                        }
                        JSONObject().apply {
                            put("id", callId)
                            put("name", toolName)
                            put("response", JSONObject().apply {
                                put("output", respPayload)
                            })
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Tool $toolName execution threw exception", e)
                        val errorPayload = JSONObject().apply {
                            put("success", false)
                            put("output", "Error: ${e.message}")
                        }
                        JSONObject().apply {
                            put("id", callId)
                            put("name", toolName)
                            put("response", JSONObject().apply {
                                put("output", errorPayload)
                            })
                        }
                    }
                }
            } ?: CompletableDeferred(JSONObject())
        }

        val responses = deferredResponses.awaitAll()
        val functionResponses = JSONArray()
        responses.forEach { functionResponses.put(it) }

        // Send toolResponse back to Gemini Live
        val responseRoot = JSONObject().apply {
            put("toolResponse", JSONObject().apply {
                put("functionResponses", functionResponses)
            })
        }

        try {
            webSocket?.send(responseRoot.toString())
            Log.d(TAG, "Sent tool responses: ${functionResponses.length()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send toolResponse", e)
        } finally {
            _isAuraActing.value = false
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (terminalError || (_connectionState.value is LiveConnectionState.Error && reconnectAttempt >= MAX_RECONNECT_ATTEMPTS)) {
            Log.w(TAG, "Not scheduling reconnect because terminalError=$terminalError or already max attempts reached.")
            return
        }

        reconnectAttempt++
        val attempt = reconnectAttempt

        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            val errorMsg = "Reconnection failed after $MAX_RECONNECT_ATTEMPTS attempts: $reason"
            Log.e(TAG, errorMsg)
            terminalError = true
            _connectionState.value = LiveConnectionState.Error(errorMsg)
            diagnostics?.updateConnectionState(errorMsg)
            diagnostics?.log(TAG, errorMsg)
            return
        }

        _connectionState.value = LiveConnectionState.Reconnecting(attempt)
        diagnostics?.updateConnectionState("Reconnecting ($attempt/$MAX_RECONNECT_ATTEMPTS): $reason")
        reconnectJob?.cancel()
        reconnectJob = scope?.launch {
            val backoffMs = (1000L * (1 shl (attempt - 1).coerceAtMost(5))).coerceAtMost(30000L)
            Log.w(TAG, "Scheduling reconnect attempt $attempt/$MAX_RECONNECT_ATTEMPTS in ${backoffMs}ms due to: $reason")
            delay(backoffMs)
            if (!intentionalDisconnect && !terminalError) {
                startWebSocketConnection()
            }
        }
    }

    private fun disconnectInternal(reconnect: Boolean) {
        setupTimeoutJob?.cancel()
        try {
            webSocket?.close(1000, "Reconnecting")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing websocket", e)
        }
        webSocket = null
        if (reconnect && !terminalError) {
            startWebSocketConnection()
        }
    }

    fun disconnect() {
        intentionalDisconnect = true
        terminalError = false
        setupTimeoutJob?.cancel()
        reconnectJob?.cancel()
        reconnectJob = null
        try {
            webSocket?.close(1000, "User ended call")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing websocket on disconnect", e)
        }
        webSocket = null
        isSetupComplete = false
        _connectionState.value = LiveConnectionState.Disconnected
        _isAuraThinking.value = false
        _isAuraActing.value = false
        userTurnAccumulator.setLength(0)
        modelTurnAccumulator.setLength(0)
        diagnostics?.updateConnectionState("Disconnected")
        audioPlayer.stop()
    }
}
