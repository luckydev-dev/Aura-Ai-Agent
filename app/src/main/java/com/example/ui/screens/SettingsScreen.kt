package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.AuraApplication
import com.example.core.live.GeminiLiveHandshake
import com.example.core.live.LiveDiagnostics
import com.example.core.settings.AuraPreferences
import com.example.ui.components.*
import com.example.ui.theme.AuraTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToSetup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = AuraTheme.colors
    val typography = AuraTheme.typography

    val preferences = remember { AuraPreferences(context) }
    val diagnostics = remember { AuraApplication.instance.liveDiagnostics }

    val apiKey by preferences.apiKey.collectAsState(initial = "")
    val modelName by preferences.modelName.collectAsState(initial = AuraPreferences.DEFAULT_MODEL)
    val voiceName by preferences.voiceName.collectAsState(initial = AuraPreferences.DEFAULT_VOICE)
    val thinkingLevel by preferences.thinkingLevel.collectAsState(initial = AuraPreferences.DEFAULT_THINKING_LEVEL)
    val cachedLiveModels by preferences.cachedLiveModels.collectAsState(initial = emptySet())
    val wakeEnabled by preferences.wakePhraseEnabled.collectAsState(initial = false)
    val confirmRisky by preferences.confirmRiskyActions.collectAsState(initial = true)
    val overlayEnabled by preferences.overlayEnabled.collectAsState(initial = false)
    val isWaBusiness by preferences.isWhatsappBusiness.collectAsState(initial = false)
    val systemInstruction by preferences.systemInstruction.collectAsState(initial = AuraPreferences.DEFAULT_SYSTEM_INSTRUCTION)

    var inputApiKey by remember(apiKey) { mutableStateOf(apiKey) }
    var inputModel by remember(modelName) { mutableStateOf(modelName) }
    var inputThinkingLevel by remember(thinkingLevel) { mutableStateOf(thinkingLevel) }
    var inputInstruction by remember(systemInstruction) { mutableStateOf(systemInstruction) }

    var isTestingConnection by remember { mutableStateOf(false) }
    var testConnectionStatus by remember { mutableStateOf<String?>(null) }
    var showDiagnosticsModal by remember { mutableStateOf(false) }

    var isFetchingModels by remember { mutableStateOf(false) }
    var fetchModelsMessage by remember { mutableStateOf<String?>(null) }
    var fetchModelsSuccess by remember { mutableStateOf(true) }

    // Display models: either fetched live list or baseline defaults
    val displayedModels = remember(cachedLiveModels) {
        if (cachedLiveModels.isNotEmpty()) {
            cachedLiveModels.toList()
        } else {
            AuraPreferences.INITIAL_LIVE_MODELS
        }
    }

    // Keep diagnostics aware of currently configured key
    LaunchedEffect(inputApiKey) {
        if (inputApiKey.isNotBlank()) {
            diagnostics.setAuthKey(inputApiKey)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            AuraTopBar(
                title = "Settings & AI Configuration",
                onBack = onBack
            )

            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // API Key Section
                item {
                    AuraCard(modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.foundation.text.BasicText(
                            text = "Gemini API Key",
                            style = typography.titleMedium.copy(color = colors.textPrimary)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        androidx.compose.foundation.text.BasicText(
                            text = "Required for Gemini Live bidirectional voice streaming.",
                            style = typography.bodySmall.copy(color = colors.textSecondary)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        AuraTextField(
                            value = inputApiKey,
                            onValueChange = {
                                inputApiKey = it
                                scope.launch { preferences.setApiKey(it) }
                            },
                            placeholder = "AIzaSy...",
                            testTag = "api_key_field"
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        androidx.compose.foundation.text.BasicText(
                            text = "Format: ${GeminiLiveHandshake.getKeyTypeDescription(inputApiKey)}",
                            style = typography.labelSmall.copy(color = colors.textTertiary)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AuraButton(
                                text = if (isTestingConnection) "Testing Live..." else "Test Connection",
                                onClick = {
                                    scope.launch {
                                        isTestingConnection = true
                                        testConnectionStatus = "Connecting to Live WebSocket..."
                                        val result = testGeminiLiveWebSocket(
                                            apiKey = inputApiKey,
                                            model = inputModel,
                                            thinkingLevel = inputThinkingLevel,
                                            diagnostics = diagnostics
                                        )
                                        testConnectionStatus = result
                                        isTestingConnection = false
                                    }
                                },
                                isPrimary = false,
                                enabled = !isTestingConnection && inputApiKey.isNotBlank(),
                                testTag = "test_connection_btn"
                            )
                            testConnectionStatus?.let { status ->
                                val isSuccess = status.startsWith("Live OK") || status.contains("ready")
                                androidx.compose.foundation.text.BasicText(
                                    text = status,
                                    style = typography.labelSmall.copy(
                                        color = if (isSuccess) colors.success else colors.danger
                                    ),
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }
                }

                // Voice Selection Section
                item {
                    AuraCard(modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.foundation.text.BasicText(
                            text = "Agent Voice",
                            style = typography.titleMedium.copy(color = colors.textPrimary)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        androidx.compose.foundation.text.BasicText(
                            text = "Select Aura's speech synthesis voice for Gemini Live.",
                            style = typography.bodySmall.copy(color = colors.textSecondary)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(listOf("Aoede", "Charon", "Fenrir", "Kore", "Puck")) { voice ->
                                AuraChip(
                                    text = voice,
                                    selected = voiceName == voice,
                                    onClick = { scope.launch { preferences.setVoiceName(voice) } }
                                )
                            }
                        }
                    }
                }

                // Model Selection Section (Dynamically fetched from server)
                item {
                    AuraCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.foundation.text.BasicText(
                                text = "Gemini Live Model",
                                style = typography.titleMedium.copy(color = colors.textPrimary)
                            )
                            AuraButton(
                                text = if (isFetchingModels) "Fetching..." else "Fetch available Live models",
                                onClick = {
                                    scope.launch {
                                        isFetchingModels = true
                                        fetchModelsMessage = "Querying supported bidi models..."
                                        val result = GeminiLiveHandshake.fetchAvailableLiveModels(inputApiKey)
                                        result.onSuccess { models ->
                                            preferences.setCachedLiveModels(models.toSet())
                                            fetchModelsSuccess = true
                                            fetchModelsMessage = "Fetched ${models.size} Live model(s)"
                                        }.onFailure { err ->
                                            fetchModelsSuccess = false
                                            fetchModelsMessage = "Fetch failed: ${err.message}"
                                        }
                                        isFetchingModels = false
                                    }
                                },
                                isPrimary = false,
                                enabled = !isFetchingModels && inputApiKey.isNotBlank()
                            )
                        }

                        fetchModelsMessage?.let { msg ->
                            Spacer(modifier = Modifier.height(4.dp))
                            androidx.compose.foundation.text.BasicText(
                                text = msg,
                                style = typography.labelSmall.copy(
                                    color = if (fetchModelsSuccess) colors.success else colors.danger
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        androidx.compose.foundation.text.BasicText(
                            text = "Select an active model supporting bidirectional streaming (bidiGenerateContent):",
                            style = typography.bodySmall.copy(color = colors.textSecondary)
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        // Model Chips
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(displayedModels) { model ->
                                AuraChip(
                                    text = model,
                                    selected = inputModel == model,
                                    onClick = {
                                        inputModel = model
                                        scope.launch { preferences.setModelName(model) }
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        AuraTextField(
                            value = inputModel,
                            onValueChange = {
                                inputModel = it
                                scope.launch { preferences.setModelName(it) }
                            },
                            placeholder = "gemini-3.8-live",
                            testTag = "model_field"
                        )

                        // Warning if saved/entered model is not in available models list
                        if (displayedModels.isNotEmpty() && !displayedModels.contains(inputModel)) {
                            Spacer(modifier = Modifier.height(8.dp))
                            androidx.compose.foundation.text.BasicText(
                                text = "⚠️ This model is not available for your key. Pick one from the fetched list above.",
                                style = typography.labelSmall.copy(color = colors.warning)
                            )
                        }

                        // Thinking Level options if model is extended thinking
                        if (inputModel.equals("gemini-3.8-live-extended-thinking", ignoreCase = true)) {
                            Spacer(modifier = Modifier.height(14.dp))
                            androidx.compose.foundation.text.BasicText(
                                text = "Thinking Level (Required for Extended Thinking)",
                                style = typography.labelLarge.copy(color = colors.accentStart)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            androidx.compose.foundation.text.BasicText(
                                text = "Higher levels provide more thorough reasoning before tool calls.",
                                style = typography.bodySmall.copy(color = colors.textSecondary)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(AuraPreferences.THINKING_LEVEL_OPTIONS) { level ->
                                    AuraChip(
                                        text = level,
                                        selected = inputThinkingLevel == level,
                                        onClick = {
                                            inputThinkingLevel = level
                                            scope.launch { preferences.setThinkingLevel(level) }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Diagnostics Panel Launcher
                item {
                    AuraCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                androidx.compose.foundation.text.BasicText(
                                    text = "Live Diagnostics",
                                    style = typography.titleMedium.copy(color = colors.textPrimary)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                androidx.compose.foundation.text.BasicText(
                                    text = "Inspect WebSocket frames, text/binary counters, and server status.",
                                    style = typography.bodySmall.copy(color = colors.textSecondary)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            AuraButton(
                                text = "View Logs",
                                onClick = { showDiagnosticsModal = true },
                                isPrimary = false,
                                testTag = "open_diagnostics_btn"
                            )
                        }
                    }
                }

                // Toggles
                item {
                    AuraCard(modifier = Modifier.fillMaxWidth()) {
                        AuraToggleRow(
                            title = "Confirm Risky Actions",
                            subtitle = "Ask for voice confirmation before delete, reboot, or shutdown.",
                            checked = confirmRisky,
                            onCheckedChange = { scope.launch { preferences.setConfirmRiskyActions(it) } },
                            testTag = "toggle_confirm_risky"
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        AuraToggleRow(
                            title = "Wake Phrase Detection",
                            subtitle = "Listen for voice trigger in background service.",
                            checked = wakeEnabled,
                            onCheckedChange = { scope.launch { preferences.setWakePhraseEnabled(it) } },
                            testTag = "toggle_wake_phrase"
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        AuraToggleRow(
                            title = "Target WhatsApp Business",
                            subtitle = "Send messages via com.whatsapp.w4b instead of standard WhatsApp.",
                            checked = isWaBusiness,
                            onCheckedChange = { scope.launch { preferences.setWhatsappBusiness(it) } },
                            testTag = "toggle_wa_business"
                        )
                    }
                }

                // Persona & System Prompt Editor
                item {
                    AuraCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.foundation.text.BasicText(
                                text = "Agent Persona Prompt",
                                style = typography.titleMedium.copy(color = colors.textPrimary),
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            AuraButton(
                                text = "Reset",
                                onClick = {
                                    inputInstruction = AuraPreferences.DEFAULT_SYSTEM_INSTRUCTION
                                    scope.launch { preferences.setSystemInstruction(inputInstruction) }
                                },
                                isPrimary = false
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        AuraTextField(
                            value = inputInstruction,
                            onValueChange = {
                                inputInstruction = it
                                scope.launch { preferences.setSystemInstruction(it) }
                            },
                            singleLine = false,
                            placeholder = "System persona instruction...",
                            testTag = "persona_prompt_field"
                        )
                    }
                }

                // Setup Wizard Button
                item {
                    AuraButton(
                        text = "Re-run Setup Wizard & Root Checks",
                        onClick = onNavigateToSetup,
                        isPrimary = false,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "rerun_setup_btn"
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }

        // Live Diagnostics Modal
        if (showDiagnosticsModal) {
            val diagState by diagnostics.connectionState.collectAsState()
            val diagModel by diagnostics.currentModel.collectAsState()
            val diagAuth by diagnostics.authKeyType.collectAsState()
            val diagHttpCode by diagnostics.httpUpgradeResponseCode.collectAsState()
            val diagFramesCount by diagnostics.framesReceivedCount.collectAsState()
            val diagTextFrames by diagnostics.textFramesCount.collectAsState()
            val diagBinaryFrames by diagnostics.binaryFramesCount.collectAsState()
            val diagLastFrame by diagnostics.lastFramePreview.collectAsState()
            val diagLastClose by diagnostics.lastCloseCodeAndReason.collectAsState()
            val diagSent by diagnostics.bytesAudioSent.collectAsState()
            val diagRecv by diagnostics.bytesAudioReceived.collectAsState()
            val diagSetupOk by diagnostics.setupCompleteArrived.collectAsState()
            val diagMic by diagnostics.micPermissionStatus.collectAsState()
            val diagRecState by diagnostics.audioRecordState.collectAsState()
            val diagTrackState by diagnostics.audioTrackState.collectAsState()
            val diagLogs by diagnostics.lastServerMessageTypes.collectAsState()
            val diagUptime = diagnostics.getUptimeFormatted()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.75f))
                    .clickable { showDiagnosticsModal = false },
                contentAlignment = Alignment.Center
            ) {
                AuraCard(
                    modifier = Modifier
                        .fillMaxWidth(0.94f)
                        .fillMaxHeight(0.90f)
                        .clickable(enabled = false) {}
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.foundation.text.BasicText(
                            text = "Live Diagnostics & Logs",
                            style = typography.titleMedium.copy(color = colors.textPrimary)
                        )
                        AuraButton(
                            text = "Close",
                            onClick = { showDiagnosticsModal = false },
                            isPrimary = false
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            DiagnosticRow("Live State", diagState, colors.accentStart)
                            DiagnosticRow("Model", diagModel, colors.textPrimary)
                            DiagnosticRow("Auth Key Type", diagAuth, if (diagAuth.startsWith("AQ")) colors.warning else colors.textPrimary)
                            DiagnosticRow("Setup Complete", if (diagSetupOk) "YES (Received)" else "NO", if (diagSetupOk) colors.success else colors.warning)
                            DiagnosticRow("HTTP Upgrade", if (diagHttpCode > 0) "$diagHttpCode" else "None", if (diagHttpCode == 101) colors.success else colors.textPrimary)
                            DiagnosticRow("Socket Connected", diagUptime, colors.textPrimary)
                            DiagnosticRow("Frames Received", "$diagFramesCount (text $diagTextFrames / binary $diagBinaryFrames)", if (diagFramesCount > 0) colors.success else colors.textSecondary)
                            DiagnosticRow("Audio Bytes Sent", "$diagSent bytes", if (diagSent > 0) colors.success else colors.textPrimary)
                            DiagnosticRow("Audio Bytes Received", "$diagRecv bytes", if (diagRecv > 0) colors.success else colors.textPrimary)
                            DiagnosticRow("Last Close Code", diagLastClose, colors.textSecondary)
                            DiagnosticRow("Mic Permission", diagMic, colors.textPrimary)
                            DiagnosticRow("Audio Record", diagRecState, colors.textPrimary)
                            DiagnosticRow("Audio Track", diagTrackState, colors.textPrimary)
                        }

                        // Last Frame Preview
                        item {
                            Spacer(modifier = Modifier.height(6.dp))
                            androidx.compose.foundation.text.BasicText(
                                text = "Last Frame Preview:",
                                style = typography.labelLarge.copy(color = colors.textPrimary)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.surfaceRaised)
                                    .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                                    .padding(8.dp)
                            ) {
                                androidx.compose.foundation.text.BasicText(
                                    text = diagLastFrame.ifBlank { "No frame received yet." },
                                    style = typography.bodySmall.copy(color = colors.textSecondary)
                                )
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            androidx.compose.foundation.text.BasicText(
                                text = "Recent Server Events:",
                                style = typography.titleMedium.copy(color = colors.textPrimary)
                            )
                        }

                        if (diagLogs.isEmpty()) {
                            item {
                                androidx.compose.foundation.text.BasicText(
                                    text = "No server events recorded yet. Start a call to begin streaming.",
                                    style = typography.bodySmall.copy(color = colors.textTertiary)
                                )
                            }
                        } else {
                            items(diagLogs) { logEntry ->
                                androidx.compose.foundation.text.BasicText(
                                    text = logEntry,
                                    style = typography.labelSmall.copy(color = colors.textSecondary)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    AuraButton(
                        text = "Copy Diagnostics to Clipboard",
                        onClick = {
                            val fullText = diagnostics.getFullLogText()
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Aura Diagnostics", fullText))
                            Toast.makeText(context, "Diagnostics copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "copy_diagnostics_btn"
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String, valueColor: Color) {
    val colors = AuraTheme.colors
    val typography = AuraTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.foundation.text.BasicText(
            text = label,
            style = typography.bodyMedium.copy(color = colors.textSecondary)
        )
        androidx.compose.foundation.text.BasicText(
            text = value,
            style = typography.bodyMedium.copy(color = valueColor)
        )
    }
}

/**
 * Tests the REAL Gemini Live BidiGenerateContent WebSocket connection end-to-end.
 * Uses shared GeminiLiveHandshake for setup payload creation and incoming frame parsing,
 * supporting both text frames and OkHttp ByteString binary frames.
 */
private suspend fun testGeminiLiveWebSocket(
    apiKey: String,
    model: String,
    thinkingLevel: String,
    diagnostics: LiveDiagnostics?
): String = withContext(Dispatchers.IO) {
    if (apiKey.isBlank()) return@withContext "API Key is empty"

    val modelName = if (model.isBlank() || model == "gemini-2.0-flash-exp" || model == "gemini-live-2.5-flash-preview") {
        AuraPreferences.DEFAULT_MODEL
    } else {
        model.trim()
    }
    val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=${apiKey.trim()}"

    diagnostics?.resetForNewConnection()
    diagnostics?.setAuthKey(apiKey)
    diagnostics?.setModel(modelName)
    diagnostics?.updateConnectionState("Testing Connection")

    val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    val resultDeferred = CompletableDeferred<String>()
    var ws: WebSocket? = null

    val request = Request.Builder().url(url).build()

    ws = client.newWebSocket(request, object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            diagnostics?.setSocketOpened(response.code)
            val setupPayload = GeminiLiveHandshake.buildSetupMessageJson(
                model = modelName,
                voice = "Aoede",
                thinkingLevel = thinkingLevel
            )
            diagnostics?.log("Aura", "Test: WebSocket connected HTTP ${response.code}, sending setup for $modelName (thinkingLevel=$thinkingLevel)")
            webSocket.send(setupPayload)
        }

        // TEXT FRAME
        override fun onMessage(webSocket: WebSocket, text: String) {
            handleFrame(webSocket, text, isBinary = false)
        }

        // BINARY FRAME (OkHttp ByteString)
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            handleFrame(webSocket, bytes.utf8(), isBinary = true)
        }

        private fun handleFrame(webSocket: WebSocket, rawText: String, isBinary: Boolean) {
            val parsed = GeminiLiveHandshake.parseAndLogIncomingFrame(rawText, isBinary, diagnostics)
            if (parsed.isSetupComplete) {
                diagnostics?.setSetupComplete(true)
                diagnostics?.updateConnectionState("Connected")
                resultDeferred.complete("Live OK ($modelName ready)")
                webSocket.close(1000, "Test complete")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            diagnostics?.setLastClose(code, reason)
            webSocket.close(code, null)
            val hint = when (code) {
                1007 -> "Invalid argument: check thinkingConfig or model parameters"
                1008 -> "Model not found: pick an available model from 'Fetch available Live models'"
                else -> GeminiLiveHandshake.getAuthHintForError(apiKey, reason)
            }
            val msg = if (hint != null) "Server closed: $code $reason\nHint: $hint" else "Server closed: $code $reason"
            resultDeferred.complete(msg)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val body = runCatching { response?.body?.string() }.getOrNull() ?: ""
            val code = response?.code
            diagnostics?.setLastClose(code ?: -1, "${t.message} $body")
            val baseMsg = if (code != null) "HTTP $code: ${t.message} $body" else t.message ?: "Connection failure"
            val hint = when (code) {
                404, 1008 -> "Model not found. Try 'Fetch available Live models' in Settings."
                400, 1007 -> "Invalid setup argument for $modelName."
                else -> GeminiLiveHandshake.getAuthHintForError(apiKey, "$code $body ${t.message}")
            }
            val finalMsg = if (hint != null) "Failed: $baseMsg\nHint: $hint" else "Failed: $baseMsg"
            resultDeferred.complete(finalMsg)
        }
    })

    val outcome = withTimeoutOrNull(10000L) {
        resultDeferred.await()
    } ?: run {
        val framesCount = diagnostics?.framesReceivedCount?.value ?: 0
        if (framesCount == 0) {
            val hint = GeminiLiveHandshake.getAuthHintForError(apiKey, "Setup timeout")
            "Timeout (10s): Server sent nothing. Check API key & model.\n${hint ?: ""}"
        } else {
            val firstFrame = diagnostics?.firstFrameReceivedContent?.value ?: ""
            "Timeout (10s): Received $framesCount frame(s) but no setupComplete. Preview: ${firstFrame.take(150)}"
        }
    }

    try {
        ws.close(1000, "Test finished")
    } catch (e: Exception) {
        Log.e("Aura", "Error closing test websocket", e)
    }

    outcome
}
