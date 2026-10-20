package com.example.core.live

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
    private val audioPlayer: AudioTrackPlayer
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var scope: CoroutineScope? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var intentionalDisconnect = false

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
        this.currentApiKey = apiKey
        this.currentModel = if (model.isBlank()) "gemini-2.0-flash-exp" else model
        this.currentVoice = voice
        this.currentSystemPrompt = systemPrompt
        this.intentionalDisconnect = false
        this.reconnectAttempt = 0

        startWebSocketConnection()
    }

    private fun startWebSocketConnection() {
        if (currentApiKey.isBlank()) {
            _connectionState.value = LiveConnectionState.Error("API Key is missing. Set it in Settings.")
            _modelTranscript.value = "Error: Gemini API Key is missing. Please set it in Settings."
            return
        }

        _connectionState.value = if (reconnectAttempt == 0) {
            LiveConnectionState.Connecting
        } else {
            LiveConnectionState.Reconnecting(reconnectAttempt)
        }

        val modelParam = if (currentModel.startsWith("models/")) currentModel else "models/$currentModel"
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$currentApiKey"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope?.launch {
                    sendSetupMessage(webSocket)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _modelTranscript.value = "Connection failed: ${t.message}"
                if (!intentionalDisconnect) {
                    scheduleReconnect(t.message ?: "Connection failure")
                } else {
                    _connectionState.value = LiveConnectionState.Disconnected
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!intentionalDisconnect) {
                    scheduleReconnect("Connection closed: $reason")
                } else {
                    _connectionState.value = LiveConnectionState.Disconnected
                }
            }
        })
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

        ws.send(root.toString())
    }

    fun sendAudioChunk(base64Pcm: String) {
        val ws = webSocket ?: return
        if (_connectionState.value != LiveConnectionState.Connected) return

        try {
            val root = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("mediaChunks", JSONArray().apply {
                        put(JSONObject().apply {
                            put("mimeType", "audio/pcm")
                            put("data", base64Pcm)
                        })
                    })
                })
            }
            ws.send(root.toString())
        } catch (_: Exception) {}
    }

    fun sendTextMessage(text: String) {
        val ws = webSocket ?: return
        if (_connectionState.value != LiveConnectionState.Connected) return

        _userTranscript.value = text

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
        } catch (_: Exception) {}
    }

    private fun handleServerMessage(jsonText: String) {
        try {
            val json = JSONObject(jsonText)

            // 1. Check for setupComplete
            if (json.has("setupComplete")) {
                reconnectAttempt = 0
                _connectionState.value = LiveConnectionState.Connected
                _modelTranscript.value = "Aura connected. Speak naturally!"
                return
            }

            // 2. Check for serverContent
            val serverContent = json.optJSONObject("serverContent")
            if (serverContent != null) {
                if (serverContent.optBoolean("interrupted", false)) {
                    audioPlayer.flushAndInterrupt()
                    _isAuraThinking.value = false
                    _isAuraActing.value = false
                    return
                }

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
                                }
                            }

                            // Text transcription part
                            val text = part.optString("text")
                            if (text.isNotBlank()) {
                                _modelTranscript.value = text
                            }
                        }
                    }
                }

                if (serverContent.optBoolean("turnComplete", false)) {
                    _isAuraThinking.value = false
                }
            }

            // 3. Check for toolCall
            val toolCall = json.optJSONObject("toolCall")
            if (toolCall != null) {
                _isAuraActing.value = true
                val functionCalls = toolCall.optJSONArray("functionCalls")
                if (functionCalls != null) {
                    scope?.launch {
                        handleFunctionCalls(functionCalls)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun handleFunctionCalls(functionCalls: JSONArray) {
        val functionResponses = JSONArray()

        for (i in 0 until functionCalls.length()) {
            val call = functionCalls.optJSONObject(i) ?: continue
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

            val result = toolRegistry.executeTool(toolName, argsMap)

            val respPayload = JSONObject().apply {
                put("success", result.success)
                put("output", result.output)
                if (result.requiresConfirmation) {
                    put("requiresConfirmation", true)
                    put("confirmationPrompt", result.confirmationPrompt)
                }
            }

            functionResponses.put(JSONObject().apply {
                put("id", callId)
                put("name", toolName)
                put("response", JSONObject().apply {
                    put("output", respPayload)
                })
            })
        }

        // Send toolResponse back to Gemini Live
        val responseRoot = JSONObject().apply {
            put("toolResponse", JSONObject().apply {
                put("functionResponses", functionResponses)
            })
        }

        webSocket?.send(responseRoot.toString())
        _isAuraActing.value = false
    }

    private fun scheduleReconnect(reason: String) {
        _connectionState.value = LiveConnectionState.Reconnecting(reconnectAttempt + 1)
        reconnectJob?.cancel()
        reconnectJob = scope?.launch {
            reconnectAttempt++
            val backoffMs = (1000L * (1 shl (reconnectAttempt - 1).coerceAtMost(5))).coerceAtMost(30000L)
            delay(backoffMs)
            if (!intentionalDisconnect) {
                startWebSocketConnection()
            }
        }
    }

    fun disconnect() {
        intentionalDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        try {
            webSocket?.close(1000, "User ended call")
        } catch (_: Exception) {}
        webSocket = null
        _connectionState.value = LiveConnectionState.Disconnected
        _isAuraThinking.value = false
        _isAuraActing.value = false
        audioPlayer.stop()
    }
}
