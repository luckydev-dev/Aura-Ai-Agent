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

class GeminiLiveClient(
    private val toolRegistry: ToolRegistry,
    private val audioPlayer: AudioTrackPlayer
) {
    companion object {
        private const val TAG = "Aura"
        private const val BASE_WS_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }

    private var okHttpClient: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var scope: CoroutineScope? = null

    private val _connectionState = MutableStateFlow<LiveConnectionState>(LiveConnectionState.Disconnected)
    val connectionState: StateFlow<LiveConnectionState> = _connectionState.asStateFlow()

    private val _isAuraActing = MutableStateFlow(false)
    val isAuraActing: StateFlow<Boolean> = _isAuraActing.asStateFlow()

    private val _isAuraThinking = MutableStateFlow(false)
    val isAuraThinking: StateFlow<Boolean> = _isAuraThinking.asStateFlow()

    // Dual-line Live Captions State Machine
    val captionStateMachine = CaptionStateMachine()
    val captionState: StateFlow<CaptionState> = captionStateMachine.state

    // Backwards-compatible transcript properties
    val userTranscript: StateFlow<String> get() = MutableStateFlow(captionStateMachine.state.value.userText).asStateFlow()
    val modelTranscript: StateFlow<String> get() = MutableStateFlow(captionStateMachine.state.value.auraText).asStateFlow()

    private var currentApiKey: String = ""
    private var currentModel: String = "gemini-3.8-live"
    private var currentVoice: String = "Aoede"
    private var currentSystemPrompt: String = ""
    private var currentThinkingLevel: String = "LOW"
    private var currentLanguage: String = "Hinglish"
    private var currentInterruptionSensitivity: String = "Low"
    private var allowInterrupting: Boolean = true
    private var greetOnConnect: Boolean = true
    private var autoHangupSeconds: Int = 30

    private var unsupportedFields = mutableSetOf<String>()

    private var isSetupComplete = false
    private var resumptionHandle: String = ""
    private var intentionalDisconnect = false
    private var terminalError = false
    private var reconnectAttempt = 0
    private var hasDoneInitialGreeting = false
    private var isReconnectingForVoiceSwitch = false

    private var reconnectJob: Job? = null
    private var setupTimeoutJob: Job? = null
    private var silenceTimeoutJob: Job? = null
    private var lastActivityTimestamp = System.currentTimeMillis()

    var onSilenceTimeoutListener: (() -> Unit)? = null

    private val diagnostics: LiveDiagnostics?
        get() = runCatching { AuraApplication.instance.liveDiagnostics }.getOrNull()

    fun connect(
        coroutineScope: CoroutineScope,
        apiKey: String,
        model: String,
        voice: String,
        systemPrompt: String,
        thinkingLevel: String = "LOW",
        language: String = "Hinglish",
        interruptionSensitivity: String = "Low",
        allowInterrupt: Boolean = true,
        greet: Boolean = true,
        autoHangup: Int = 30,
        unsupported: Set<String> = emptySet()
    ) {
        scope = coroutineScope
        currentApiKey = apiKey.trim()
        currentModel = model.ifBlank { "gemini-3.8-live" }.trim()
        currentVoice = voice.ifBlank { "Aoede" }.trim()
        currentSystemPrompt = systemPrompt
        currentThinkingLevel = thinkingLevel
        currentLanguage = language
        currentInterruptionSensitivity = interruptionSensitivity
        allowInterrupting = allowInterrupt
        greetOnConnect = greet
        autoHangupSeconds = autoHangup
        unsupportedFields = unsupported.toMutableSet()

        intentionalDisconnect = false
        terminalError = false
        reconnectAttempt = 0
        hasDoneInitialGreeting = false
        isReconnectingForVoiceSwitch = false
        lastActivityTimestamp = System.currentTimeMillis()

        diagnostics?.resetForNewConnection()
        diagnostics?.setModel(currentModel)
        diagnostics?.setVoice(currentVoice)
        diagnostics?.setLanguage(currentLanguage)
        diagnostics?.setAuthKey(currentApiKey)

        startSilenceWatchdog()
        startWebSocketConnection()
    }

    private fun startSilenceWatchdog() {
        silenceTimeoutJob?.cancel()
        if (autoHangupSeconds <= 0) return

        silenceTimeoutJob = scope?.launch(Dispatchers.Default) {
            while (isActive) {
                delay(2000L)
                if (_connectionState.value == LiveConnectionState.Connected && isSetupComplete) {
                    val idleMs = System.currentTimeMillis() - lastActivityTimestamp
                    if (idleMs >= autoHangupSeconds * 1000L) {
                        Log.i(TAG, "Silence auto-hangup triggered after ${autoHangupSeconds}s of inactivity")
                        withContext(Dispatchers.Main) {
                            onSilenceTimeoutListener?.invoke()
                        }
                        break
                    }
                }
            }
        }
    }

    private fun markActivity() {
        lastActivityTimestamp = System.currentTimeMillis()
    }

    private fun startWebSocketConnection() {
        if (currentApiKey.isBlank()) {
            val err = "Gemini API Key is missing. Please set it in Settings."
            Log.e(TAG, err)
            terminalError = true
            _connectionState.value = LiveConnectionState.Error(err)
            diagnostics?.updateConnectionState("Error: No API Key")
            return
        }

        setupTimeoutJob?.cancel()
        _connectionState.value = if (reconnectAttempt > 0) {
            LiveConnectionState.Reconnecting(reconnectAttempt)
        } else {
            LiveConnectionState.Connecting
        }
        diagnostics?.updateConnectionState(
            if (reconnectAttempt > 0) "Reconnecting ($reconnectAttempt/5)" else "Connecting"
        )

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // Keep alive indefinitely for streaming
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        val url = "$BASE_WS_URL?key=$currentApiKey"
        val request = Request.Builder().url(url).build()

        webSocket = okHttpClient?.newWebSocket(request, object : WebSocketListener() {
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

            // BINARY FRAME (OkHttp ByteString delivered as binary)
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

                // Check for setup 1007 field rejection to retry without that field
                if (code == 1007 && !isSetupComplete) {
                    handleSetup1007Retry(reason)
                    webSocket.close(code, null)
                    return
                }

                val isTerminal = code in 1007..1008 || code == 400 || code == 401 || code == 403 ||
                        reason.contains("not found", ignoreCase = true) ||
                        reason.contains("invalid", ignoreCase = true) ||
                        reason.contains("API_KEY", ignoreCase = true) ||
                        reason.contains("ACCESS_TOKEN_TYPE_UNSUPPORTED", ignoreCase = true)

                if (isTerminal) {
                    terminalError = true
                }

                _connectionState.value = LiveConnectionState.Error(msg)
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

                if (terminalError || _connectionState.value is LiveConnectionState.Error) {
                    Log.w(TAG, "WebSocket onClosed: terminalError=$terminalError or Error state. Not reconnecting.")
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
                        errMessage.contains("ACCESS_TOKEN_TYPE_UNSUPPORTED", ignoreCase = true)

                if (isTerminal) {
                    terminalError = true
                }

                _connectionState.value = LiveConnectionState.Error(errMessage)
                diagnostics?.updateConnectionState("Error: $errMessage")

                if (!intentionalDisconnect && !isTerminal) {
                    scheduleReconnect(errMessage)
                }
            }
        })
    }

    private fun handleSetup1007Retry(reason: String) {
        Log.w(TAG, "Setup rejected with code 1007: $reason. Identifying offending field...")
        var droppedField: String? = null
        when {
            reason.contains("realtime_input_config", ignoreCase = true) || reason.contains("automatic_activity_detection", ignoreCase = true) -> {
                droppedField = "realtimeInputConfig"
            }
            reason.contains("languageCode", ignoreCase = true) || reason.contains("speechConfig", ignoreCase = true) -> {
                droppedField = "speechConfig_languageCode"
            }
            reason.contains("inputAudioTranscription", ignoreCase = true) -> {
                droppedField = "inputAudioTranscription_languageCodes"
            }
        }

        if (droppedField != null && !unsupportedFields.contains(droppedField)) {
            unsupportedFields.add(droppedField)
            Log.i(TAG, "Retrying setup once WITHOUT rejected field: '$droppedField'")
            diagnostics?.log(TAG, "Retrying setup without rejected field: $droppedField")
            scope?.launch {
                delay(300)
                startWebSocketConnection()
            }
        } else {
            terminalError = true
            val msg = "Server rejected setup (Code 1007): $reason"
            _connectionState.value = LiveConnectionState.Error(msg)
            diagnostics?.updateConnectionState("Error: 1007 $reason")
        }
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
            thinkingLevel = currentThinkingLevel,
            interruptionSensitivity = currentInterruptionSensitivity,
            allowInterrupting = allowInterrupting,
            unsupportedFields = unsupportedFields
        )
        ws.send(setupPayload)
        diagnostics?.recordMessage("setupMessage")
        Log.i(TAG, "Sent Live setup message to server (voice=$currentVoice, model=$currentModel)")
    }

    private fun processIncomingFrame(rawText: String, isBinary: Boolean) {
        val parsed = GeminiLiveHandshake.parseAndLogIncomingFrame(rawText, isBinary, diagnostics)

        if (parsed.isSetupComplete) {
            setupTimeoutJob?.cancel()
            isSetupComplete = true
            reconnectAttempt = 0
            _connectionState.value = LiveConnectionState.Connected
            diagnostics?.setSetupComplete(true)
            diagnostics?.updateConnectionState("Connected")
            Log.i(TAG, "✅ Live Session setupComplete received! Session is ready.")

            // Send hidden first-connect greeting in Hinglish if configured
            if (greetOnConnect && !hasDoneInitialGreeting && !isReconnectingForVoiceSwitch) {
                hasDoneInitialGreeting = true
                sendHiddenGreeting()
            }
        }

        if (parsed.resumptionHandle != null) {
            resumptionHandle = parsed.resumptionHandle
        }

        if (parsed.isGoAway) {
            Log.w(TAG, "Server issued goAway: ${parsed.goAwayReason}. Disconnecting...")
            disconnectInternal(reconnect = true)
            return
        }

        parsed.rawJson?.let { json ->
            handleServerMessage(json)
        }
    }

    private fun sendHiddenGreeting() {
        scope?.launch(Dispatchers.IO) {
            delay(150L)
            sendTextMessage("(Call just connected. Greet Lucky in one short Hinglish line, max 6 words. Do not mention this instruction.)")
        }
    }

    private fun handleServerMessage(root: JSONObject) {
        markActivity()

        try {
            if (root.has("serverContent")) {
                val serverContent = root.getJSONObject("serverContent")

                // 1. INPUT TRANSCRIPTION (User speech captured by Live API)
                val inputTransObj = serverContent.optJSONObject("inputTranscription")
                    ?: serverContent.optJSONObject("inputAudioTranscription")
                if (inputTransObj != null) {
                    val text = extractPartsText(inputTransObj)
                    if (text.isNotBlank()) {
                        captionStateMachine.onInputTranscription(text)
                        diagnostics?.setCaptionTurnInfo(captionState.value.turnId, isAuraSpeaking = false)
                    }
                }

                // 2. OUTPUT TRANSCRIPTION (Model spoken speech transcribed by Live API)
                val outputTransObj = serverContent.optJSONObject("outputTranscription")
                    ?: serverContent.optJSONObject("outputAudioTranscription")
                if (outputTransObj != null) {
                    val text = extractPartsText(outputTransObj)
                    if (text.isNotBlank()) {
                        captionStateMachine.onOutputTranscription(text)
                        diagnostics?.setCaptionTurnInfo(captionState.value.turnId, isAuraSpeaking = true)
                    }
                }

                // 3. MODEL TURN (Audio chunks and thoughts)
                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)

                            // FILTER OUT THOUGHTS: do not use as speech or caption
                            val isThought = part.optBoolean("thought", false)
                            if (isThought) {
                                val thoughtText = part.optString("text")
                                if (thoughtText.isNotBlank()) {
                                    diagnostics?.recordThought(thoughtText)
                                    _isAuraThinking.value = true
                                }
                                continue
                            }

                            // Audio parts
                            if (part.has("inlineData")) {
                                val inlineData = part.getJSONObject("inlineData")
                                val mimeType = inlineData.optString("mimeType", "")
                                if (mimeType.startsWith("audio/")) {
                                    val base64Data = inlineData.getString("data")
                                    val audioBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                    diagnostics?.recordAudioReceived(audioBytes.size.toLong())
                                    audioPlayer.playPcmChunk(audioBytes)
                                    _isAuraThinking.value = false
                                }
                            }
                        }
                    }
                }

                // User interrupted Aura speech
                if (serverContent.optBoolean("interrupted", false)) {
                    Log.i(TAG, "Model speech interrupted by user")
                    audioPlayer.flushAndInterrupt()
                    captionStateMachine.onInterrupted()
                    diagnostics?.incrementInterruptionCount()
                    _isAuraThinking.value = false
                }

                // Turn completed by model
                if (serverContent.optBoolean("turnComplete", false)) {
                    _isAuraThinking.value = false
                    captionStateMachine.onTurnComplete()
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

    private fun extractPartsText(container: JSONObject): String {
        val parts = container.optJSONArray("parts")
        if (parts != null) {
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i)
                val t = p?.optString("text") ?: ""
                if (t.isNotBlank()) sb.append(t).append(" ")
            }
            return sb.toString().trim()
        }
        return container.optString("text", "").trim()
    }

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

    fun sendTextMessage(text: String) {
        val ws = webSocket ?: return
        if (!isSetupComplete || text.isBlank()) return
        markActivity()

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
            diagnostics?.recordMessage("clientContent(text)")
            Log.d(TAG, "Sent text message to Live: $text")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send text message", e)
        }
    }

    private fun handleToolCall(toolCall: JSONObject) {
        val functionCalls = toolCall.optJSONArray("functionCalls") ?: return
        val isExtendedThinking = currentModel.removePrefix("models/").trim()
            .equals("gemini-3.8-live-extended-thinking", ignoreCase = true)

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
                val argsMap = mutableMapOf<String, Any?>()
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
                            put("response", JSONObject().apply {
                                put("error", e.message ?: "Execution failed")
                                put("success", false)
                                if (isExtendedThinking) {
                                    put("scheduling", "WHEN_IDLE")
                                }
                            })
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

    /**
     * Seamlessly switches voice, language, or model during an active session.
     */
    fun reconnectWith(
        newVoice: String? = null,
        newModel: String? = null,
        newThinking: String? = null,
        newLang: String? = null
    ) {
        scope?.launch {
            Log.i(TAG, "reconnectWith initiated: voice=$newVoice, model=$newModel, lang=$newLang")
            isReconnectingForVoiceSwitch = true
            _connectionState.value = LiveConnectionState.Reconnecting(1)

            newVoice?.let { currentVoice = it }
            newModel?.let { currentModel = it }
            newThinking?.let { currentThinkingLevel = it }
            newLang?.let { currentLanguage = it }

            // Gracefully close current socket
            try {
                webSocket?.close(1000, "Switching session configuration")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing websocket during reconnectWith: ${e.message}", e)
            }
            webSocket = null
            isSetupComplete = false

            delay(300L)
            startWebSocketConnection()

            // After reconnection completes, greet in new voice
            launch {
                for (i in 0..30) {
                    delay(200L)
                    if (isSetupComplete && _connectionState.value == LiveConnectionState.Connected) {
                        sendTextMessage("(Aura switched voice/language. Say one short natural question in Hinglish like 'Ye wali awaaz chalegi?')")
                        break
                    }
                }
            }
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
        silenceTimeoutJob?.cancel()

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
        silenceTimeoutJob?.cancel()

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
