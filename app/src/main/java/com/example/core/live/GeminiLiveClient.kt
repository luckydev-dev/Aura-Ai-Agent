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

class GeminiLiveClient(
    private val toolRegistry: ToolRegistry,
    private val audioPlayer: AudioTrackPlayer,
    private val diagnostics: LiveDiagnostics? = runCatching { AuraApplication.instance.liveDiagnostics }.getOrNull()
) {
    companion object {
        private const val TAG = "Aura"
        private const val DEFAULT_FALLBACK_MODEL = "gemini-3.8-live"
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var scope: CoroutineScope? = null
    private var reconnectJob: Job? = null
    private var setupTimeoutJob: Job? = null
    private var reconnectAttempt = 0
    private var intentionalDisconnect = false
    private var isSetupComplete = false
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
        diagnostics?.updateConnectionState(if (reconnectAttempt == 0) "Connecting" else "Reconnecting ($reconnectAttempt)")

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
                val msg = "Server closing: $code $reason"
                Log.e(TAG, "WebSocket onClosing: $msg")
                diagnostics?.setLastClose(code, reason)
                diagnostics?.log(TAG, msg)

                setupTimeoutJob?.cancel()
                _connectionState.value = LiveConnectionState.Error(msg)
                _modelTranscript.value = "Server closed connection: $reason"
                webSocket.close(code, null)

                // Do not reconnect on permanent client/model/auth rejection codes
                val isTerminal = code in 1007..1008 || code == 400 || code == 403 ||
                        reason.contains("not found", ignoreCase = true) ||
                        reason.contains("invalid", ignoreCase = true) ||
                        reason.contains("API_KEY", ignoreCase = true)

                if (!intentionalDisconnect && !isTerminal) {
                    scheduleReconnect("Server closed: $code $reason")
                } else {
                    _connectionState.value = LiveConnectionState.Error("Closed ($code): $reason")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket onClosed: $code $reason")
                diagnostics?.setLastClose(code, reason)
                setupTimeoutJob?.cancel()
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
                    if (respBody.isNotBlank()) append(" - ").append(respBody.take(150))
                }
                Log.e(TAG, "WebSocket onFailure: $errMessage", t)
                diagnostics?.log(TAG, "onFailure: $errMessage")
                diagnostics?.setLastClose(httpStatus ?: -1, errMessage)

                setupTimeoutJob?.cancel()
                _connectionState.value = LiveConnectionState.Error(errMessage)
                _modelTranscript.value = "Connection error: $errMessage"

                val isTerminal = httpStatus in 400..499
                if (!intentionalDisconnect && !isTerminal) {
                    scheduleReconnect(errMessage)
                } else {
                    diagnostics?.updateConnectionState("Error: $errMessage")
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
                _connectionState.value = LiveConnectionState.Error(timeoutMsg)
                _modelTranscript.value = timeoutMsg
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
            // Voice activity detection
            put("realtimeInputConfig", JSONObject().apply {
                put("automaticActivityDetection", JSONObject().apply {
                    put("startOfSpeechSensitivity", "START_SENSITIVITY_DEFAULT")
                    put("endOfSpeechSensitivity", "END_SENSITIVITY_DEFAULT")
                })
            })
            // Resumption
            if (resumptionHandle.isNotBlank()) {
                put("sessionResumption", JSONObject().apply {
                    put("handle", resumptionHandle)
                })
            } else {
                put("sessionResumption", JSONObject())
            }
            // Sliding window context compression
            put("contextWindowCompression", JSONObject().apply {
                put("slidingWindow", JSONObject())
            })

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
            diagnostics?.addAudioSentBytes(base64Pcm.length.toLong())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio chunk", e)
        }
    }

    /**
     * Sends client textual content turn
     */
    fun sendTextMessage(text: String) {
        val ws = webSocket ?: return
        if (!isSetupComplete || _connectionState.value != LiveConnectionState.Connected) return

        _userTranscript.value = text
        userTurnAccumulator.clear()
        userTurnAccumulator.append(text)

        try {
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
            diagnostics?.log(TAG, "Sent text message: $text")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending text message", e)
        }
    }

    private fun handleServerMessage(jsonText: String) {
        try {
            val json = JSONObject(jsonText)

            // 1. Check for setupComplete
            if (json.has("setupComplete")) {
                setupTimeoutJob?.cancel()
                isSetupComplete = true
                reconnectAttempt = 0
                _connectionState.value = LiveConnectionState.Connected
                _modelTranscript.value = "Aura connected. Speak naturally!"
                diagnostics?.setSetupComplete(true)
                diagnostics?.updateConnectionState("Connected")
                diagnostics?.recordServerMessageType("setupComplete")
                Log.i(TAG, "Gemini Live setupComplete confirmed. Ready for voice interaction!")
                return
            }

            // 2. Check for sessionResumptionUpdate
            if (json.has("sessionResumptionUpdate")) {
                val update = json.optJSONObject("sessionResumptionUpdate")
                val handle = update?.optString("resumptionHandle")
                if (!handle.isNullOrBlank()) {
                    resumptionHandle = handle
                    diagnostics?.recordServerMessageType("sessionResumptionUpdate")
                    Log.d(TAG, "Stored session resumption handle")
                }
            }

            // 3. Check for goAway
            if (json.has("goAway")) {
                diagnostics?.recordServerMessageType("goAway")
                Log.w(TAG, "Server issued goAway. Reconnecting gracefully...")
                scope?.launch {
                    disconnectInternal(reconnect = true)
                }
                return
            }

            // 4. Check for serverContent
            val serverContent = json.optJSONObject("serverContent")
            if (serverContent != null) {
                diagnostics?.recordServerMessageType("serverContent")

                // Handle barge-in / interrupted
                if (serverContent.optBoolean("interrupted", false)) {
                    Log.d(TAG, "Model response interrupted by user speech")
                    audioPlayer.flushAndInterrupt()
                    _isAuraThinking.value = false
                    _isAuraActing.value = false
                    modelTurnAccumulator.clear()
                    return
                }

                // Input audio transcription (what the server heard from user)
                val inTranscription = serverContent.optJSONObject("inputAudioTranscription")
                    ?: serverContent.optJSONObject("inputTranscription")
                if (inTranscription != null) {
                    val recognizedText = inTranscription.optString("text")
                    if (recognizedText.isNotBlank()) {
                        userTurnAccumulator.append(recognizedText)
                        _userTranscript.value = userTurnAccumulator.toString()
                        Log.d(TAG, "User input transcript: $recognizedText")
                    }
                }

                // Output audio transcription (what Aura is saying)
                val outTranscription = serverContent.optJSONObject("outputAudioTranscription")
                    ?: serverContent.optJSONObject("outputTranscription")
                if (outTranscription != null) {
                    val auraText = outTranscription.optString("text")
                    if (auraText.isNotBlank()) {
                        modelTurnAccumulator.append(auraText)
                        _modelTranscript.value = modelTurnAccumulator.toString()
                    }
                }

                // Model turn parts (Audio inlineData & text parts)
                val modelTurn = serverContent.optJSONObject("modelTurn")
                if (modelTurn != null) {
                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.optJSONObject(i) ?: continue

                            // Audio part
                            val inlineData = part.optJSONObject("inlineData")
                            if (inlineData != null) {
                                val audioBase64 = inlineData.optString("data")
                                if (audioBase64.isNotBlank()) {
                                    _isAuraThinking.value = false
                                    audioPlayer.enqueueBase64Chunk(audioBase64)
                                    diagnostics?.addAudioReceivedBytes(audioBase64.length.toLong())
                                }
                            }

                            // Text part
                            val text = part.optString("text")
                            if (text.isNotBlank()) {
                                modelTurnAccumulator.append(text)
                                _modelTranscript.value = modelTurnAccumulator.toString()
                            }
                        }
                    }
                }

                if (serverContent.optBoolean("turnComplete", false)) {
                    _isAuraThinking.value = false
                    userTurnAccumulator.clear()
                    modelTurnAccumulator.clear()
                }
            }

            // 5. Check for toolCall
            val toolCall = json.optJSONObject("toolCall")
            if (toolCall != null) {
                diagnostics?.recordServerMessageType("toolCall")
                _isAuraActing.value = true
                val functionCalls = toolCall.optJSONArray("functionCalls")
                if (functionCalls != null) {
                    scope?.launch(Dispatchers.Default) {
                        handleFunctionCalls(functionCalls)
                    }
                }
            }

            // 6. Check for toolCallCancellation
            if (json.has("toolCallCancellation")) {
                diagnostics?.recordServerMessageType("toolCallCancellation")
                _isAuraActing.value = false
                Log.d(TAG, "Tool calls cancelled by model")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing server message: ${e.message}", e)
        }
    }

    private suspend fun handleFunctionCalls(functionCalls: JSONArray) {
        val callsList = mutableListOf<JSONObject>()
        for (i in 0 until functionCalls.length()) {
            val call = functionCalls.optJSONObject(i) ?: continue
            callsList.add(call)
        }

        // Execute function calls in parallel using async
        val deferredResponses = callsList.map { call ->
            scope?.async(Dispatchers.IO) {
                val callId = call.optString("id")
                val toolName = call.optString("name")
                val argsObj = call.optJSONObject("args")

                val argsMap = mutableMapOf<String, Any?>()
                if (argsObj != null) {
                    val keys = argsObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        argsMap[k] = argsObj.get(k)
                    }
                }

                try {
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
        reconnectAttempt++
        val attempt = reconnectAttempt
        _connectionState.value = LiveConnectionState.Reconnecting(attempt)
        diagnostics?.updateConnectionState("Reconnecting ($attempt): $reason")
        reconnectJob?.cancel()
        reconnectJob = scope?.launch {
            val backoffMs = (1000L * (1 shl (attempt - 1).coerceAtMost(5))).coerceAtMost(30000L)
            Log.w(TAG, "Scheduling reconnect attempt $attempt in ${backoffMs}ms due to: $reason")
            delay(backoffMs)
            if (!intentionalDisconnect) {
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
        if (reconnect) {
            startWebSocketConnection()
        }
    }

    fun disconnect() {
        intentionalDisconnect = true
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
        userTurnAccumulator.clear()
        modelTurnAccumulator.clear()
        diagnostics?.updateConnectionState("Disconnected")
        audioPlayer.stop()
    }
}
