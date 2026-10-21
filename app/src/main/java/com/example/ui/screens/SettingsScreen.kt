package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import org.json.JSONArray
import org.json.JSONObject
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
    val wakeEnabled by preferences.wakePhraseEnabled.collectAsState(initial = false)
    val confirmRisky by preferences.confirmRiskyActions.collectAsState(initial = true)
    val overlayEnabled by preferences.overlayEnabled.collectAsState(initial = false)
    val isWaBusiness by preferences.isWhatsappBusiness.collectAsState(initial = false)
    val themeMode by preferences.themeMode.collectAsState(initial = "dark")
    val systemInstruction by preferences.systemInstruction.collectAsState(initial = AuraPreferences.DEFAULT_SYSTEM_INSTRUCTION)

    var inputApiKey by remember(apiKey) { mutableStateOf(apiKey) }
    var inputModel by remember(modelName) { mutableStateOf(modelName) }
    var inputInstruction by remember(systemInstruction) { mutableStateOf(systemInstruction) }

    var testConnectionStatus by remember { mutableStateOf<String?>(null) }
    var isTestingConnection by remember { mutableStateOf(false) }
    var showDiagnosticsModal by remember { mutableStateOf(false) }

    val availableVoices = listOf("Aoede", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Zephyr")

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            AuraTopBar(
                title = "Settings",
                onBack = onBack
            )

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
                        Spacer(modifier = Modifier.height(6.dp))
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
                                        val result = testGeminiLiveWebSocket(inputApiKey, inputModel)
                                        testConnectionStatus = result
                                        isTestingConnection = false
                                    }
                                },
                                isPrimary = false,
                                enabled = !isTestingConnection && inputApiKey.isNotBlank(),
                                testTag = "test_connection_btn"
                            )
                            testConnectionStatus?.let { status ->
                                val isSuccess = status.startsWith("Live OK") || status.contains("Successful")
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
                        Spacer(modifier = Modifier.height(12.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(availableVoices) { voice ->
                                AuraChip(
                                    text = voice,
                                    selected = voice == voiceName,
                                    onClick = {
                                        scope.launch { preferences.setVoiceName(voice) }
                                    },
                                    testTag = "voice_chip_$voice"
                                )
                            }
                        }
                    }
                }

                // Model Configuration
                item {
                    AuraCard(modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.foundation.text.BasicText(
                            text = "Live Model Identifier",
                            style = typography.titleMedium.copy(color = colors.textPrimary)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        AuraTextField(
                            value = inputModel,
                            onValueChange = {
                                inputModel = it
                                scope.launch { preferences.setModelName(it) }
                            },
                            placeholder = "gemini-3.8-live",
                            testTag = "model_name_field"
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        androidx.compose.foundation.text.BasicText(
                            text = "Suggested models:",
                            style = typography.labelSmall.copy(color = colors.textTertiary)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(AuraPreferences.SUGGESTED_MODELS) { suggested ->
                                AuraChip(
                                    text = suggested,
                                    selected = inputModel == suggested,
                                    onClick = {
                                        inputModel = suggested
                                        scope.launch { preferences.setModelName(suggested) }
                                    }
                                )
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
                                    text = "Inspect WebSocket status, audio buffers, close codes, and logs.",
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
            val diagLastClose by diagnostics.lastCloseCodeAndReason.collectAsState()
            val diagSent by diagnostics.bytesAudioSent.collectAsState()
            val diagRecv by diagnostics.bytesAudioReceived.collectAsState()
            val diagSetupOk by diagnostics.setupCompleteArrived.collectAsState()
            val diagMic by diagnostics.micPermissionStatus.collectAsState()
            val diagRecState by diagnostics.audioRecordState.collectAsState()
            val diagTrackState by diagnostics.audioTrackState.collectAsState()
            val diagLogs by diagnostics.lastServerMessageTypes.collectAsState()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .clickable { showDiagnosticsModal = false },
                contentAlignment = Alignment.Center
            ) {
                AuraCard(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .fillMaxHeight(0.85f)
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
                            DiagnosticRow("Setup Complete", if (diagSetupOk) "YES (Received)" else "NO", if (diagSetupOk) colors.success else colors.warning)
                            DiagnosticRow("Last Close Code", diagLastClose, colors.textSecondary)
                            DiagnosticRow("Audio Bytes Sent", "$diagSent bytes", colors.textPrimary)
                            DiagnosticRow("Audio Bytes Received", "$diagRecv bytes", colors.textPrimary)
                            DiagnosticRow("Mic Permission", diagMic, colors.textPrimary)
                            DiagnosticRow("Audio Record State", diagRecState, colors.textPrimary)
                            DiagnosticRow("Audio Track State", diagTrackState, colors.textPrimary)
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
                            val text = """
                                Aura Diagnostics Report:
                                Connection State: $diagState
                                Model: $diagModel
                                Setup Complete: $diagSetupOk
                                Last Close: $diagLastClose
                                Sent: $diagSent bytes, Received: $diagRecv bytes
                                Record: $diagRecState, Track: $diagTrackState
                                Recent Events:
                                ${diagLogs.joinToString("\n")}
                            """.trimIndent()
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Aura Diagnostics", text))
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
        horizontalArrangement = Arrangement.SpaceBetween
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
 * Connects, sends setup, waits up to 10s for setupComplete, and verifies voice readiness.
 */
private suspend fun testGeminiLiveWebSocket(apiKey: String, model: String): String = withContext(Dispatchers.IO) {
    if (apiKey.isBlank()) return@withContext "API Key is empty"

    val modelName = if (model.isBlank() || model == "gemini-2.0-flash-exp") AuraPreferences.DEFAULT_MODEL else model.trim()
    val modelPath = if (modelName.startsWith("models/")) modelName else "models/$modelName"
    val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=${apiKey.trim()}"

    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    val resultDeferred = CompletableDeferred<String>()
    var ws: WebSocket? = null

    val request = Request.Builder().url(url).build()

    ws = client.newWebSocket(request, object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val setupObj = JSONObject().apply {
                put("model", modelPath)
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().apply { put("AUDIO") })
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", "Aoede")
                            })
                        })
                    })
                })
            }
            val root = JSONObject().apply {
                put("setup", setupObj)
            }
            webSocket.send(root.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val json = JSONObject(text)
                if (json.has("setupComplete")) {
                    resultDeferred.complete("Live OK ($modelName ready)")
                    webSocket.close(1000, "Test complete")
                }
            } catch (e: Exception) {
                resultDeferred.complete("Message parse error: ${e.message}")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
            resultDeferred.complete("Server closed: $code $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val body = runCatching { response?.body?.string() }.getOrNull() ?: ""
            val code = response?.code
            val msg = if (code != null) "HTTP $code: ${t.message} $body" else t.message ?: "Connection failure"
            resultDeferred.complete("Failed: $msg")
        }
    })

    val outcome = withTimeoutOrNull(10000L) {
        resultDeferred.await()
    } ?: "Timeout (10s) waiting for Live server"

    try {
        ws.close(1000, "Test finished")
    } catch (_: Exception) {}

    outcome
}
