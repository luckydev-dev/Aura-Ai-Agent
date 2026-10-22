package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import com.example.core.audio.VoskModelManager
import com.example.core.audio.VoskModelStatus
import com.example.core.live.GeminiLiveHandshake
import com.example.core.live.LiveDiagnostics
import com.example.core.live.VoiceCatalog
import com.example.core.root.PermissionCheckItem
import com.example.core.root.PermissionDoctor
import com.example.core.root.RootManager
import com.example.core.settings.AuraPreferences
import com.example.service.AuraForegroundService
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
    val rootManager = remember { RootManager(context) }
    val permissionDoctor = remember { PermissionDoctor(context, rootManager) }
    val voskModelManager = remember { VoskModelManager(context) }

    // Collected Preferences
    val apiKey by preferences.apiKey.collectAsState(initial = "")
    val modelName by preferences.modelName.collectAsState(initial = AuraPreferences.DEFAULT_MODEL)
    val voiceName by preferences.voiceName.collectAsState(initial = AuraPreferences.DEFAULT_VOICE)
    val thinkingLevel by preferences.thinkingLevel.collectAsState(initial = AuraPreferences.DEFAULT_THINKING_LEVEL)
    val cachedLiveModels by preferences.cachedLiveModels.collectAsState(initial = emptySet())

    val defaultLanguage by preferences.defaultLanguage.collectAsState(initial = AuraPreferences.DEFAULT_LANGUAGE)
    val greetOnConnect by preferences.greetOnConnect.collectAsState(initial = true)
    val showCaptions by preferences.showCaptions.collectAsState(initial = true)

    val allowInterrupting by preferences.allowInterrupting.collectAsState(initial = true)
    val interruptionSens by preferences.interruptionSensitivity.collectAsState(initial = "Low")
    val noiseGateStrength by preferences.noiseGateStrength.collectAsState(initial = "Medium")
    val autoHangupSec by preferences.autoHangupSeconds.collectAsState(initial = 30)

    val wakeEnabled by preferences.wakePhraseEnabled.collectAsState(initial = false)
    val wakeSensitivity by preferences.wakeWordSensitivity.collectAsState(initial = "normal")
    val voskStatus by voskModelManager.status.collectAsState()

    val edgeLightingEnabled by preferences.edgeLightingEnabled.collectAsState(initial = true)
    val edgeLightingThickness by preferences.edgeLightingThickness.collectAsState(initial = 8)
    val edgeLightingIntensity by preferences.edgeLightingIntensity.collectAsState(initial = 1.0f)
    val overlayEnabled by preferences.overlayEnabled.collectAsState(initial = false)

    val ownerName by preferences.ownerName.collectAsState(initial = AuraPreferences.DEFAULT_OWNER_NAME)
    val extraInstructions by preferences.extraInstructions.collectAsState(initial = "")
    val includeSystemApps by preferences.includeSystemApps.collectAsState(initial = true)

    val confirmRisky by preferences.confirmRiskyActions.collectAsState(initial = true)
    val isWaBusiness by preferences.isWhatsappBusiness.collectAsState(initial = false)

    // Local mutable state for text inputs
    var inputApiKey by remember(apiKey) { mutableStateOf(apiKey) }
    var inputModel by remember(modelName) { mutableStateOf(modelName) }
    var inputThinkingLevel by remember(thinkingLevel) { mutableStateOf(thinkingLevel) }
    var inputOwnerName by remember(ownerName) { mutableStateOf(ownerName) }
    var inputExtraInstructions by remember(extraInstructions) { mutableStateOf(extraInstructions) }

    // Voice catalog expansion state
    var showAllVoices by remember { mutableStateOf(false) }

    // Test connection state
    var isTestingConnection by remember { mutableStateOf(false) }
    var testConnectionStatus by remember { mutableStateOf<String?>(null) }
    var showDiagnosticsModal by remember { mutableStateOf(false) }

    // Model fetching state
    var isFetchingModels by remember { mutableStateOf(false) }
    var fetchModelsMessage by remember { mutableStateOf<String?>(null) }
    var fetchModelsSuccess by remember { mutableStateOf(true) }

    // Permission Doctor state
    var permissionItems by remember { mutableStateOf<List<PermissionCheckItem>>(emptyList()) }
    var isRunningDoctor by remember { mutableStateOf(false) }
    var doctorReportText by remember { mutableStateOf<String?>(null) }

    // Refresh permissions on initial compose
    LaunchedEffect(Unit) {
        permissionItems = permissionDoctor.getAllCheckItems()
    }

    // Keep diagnostics aware of currently configured key
    LaunchedEffect(inputApiKey) {
        if (inputApiKey.isNotBlank()) {
            diagnostics.setAuthKey(inputApiKey)
        }
    }

    val displayedModels = remember(cachedLiveModels) {
        if (cachedLiveModels.isNotEmpty()) cachedLiveModels.toList() else AuraPreferences.INITIAL_LIVE_MODELS
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
                // 1. API Key Card
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

                // 2. Language & Speech Settings
                item {
                    AuraCard(modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.foundation.text.BasicText(
                            text = "Language & Speech",
                            style = typography.titleMedium.copy(color = colors.textPrimary)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        androidx.compose.foundation.text.BasicText(
                            text = "Default language: Hinglish (natural Hindi + English mix with Indian context).",
                            style = typography.bodySmall.copy(color = colors.textSecondary)
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(AuraPreferences.LANGUAGE_OPTIONS) { lang ->
                                AuraChip(
                                    text = lang,
                                    selected = defaultLanguage == lang,
                                    onClick = {
                                        scope.launch {
                                            preferences.setDefaultLanguage(lang)
                                            diagnostics.setLanguage(lang)
                                        }
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        AuraToggleRow(
                            title = "Greet on Connect",
                            subtitle = "Aura speaks a brief friendly greeting upon opening the voice session.",
                            checked = greetOnConnect,
                            onCheckedChange = { scope.launch { preferences.setGreetOnConnect(it) } },
                            testTag = "toggle_greet_on_connect"
                        )

                        Spacer(modifier = Modifier.height(14.dp))
                        AuraToggleRow(
                            title = "Live Speech Captions",
                            subtitle = "Display live dual-line subtitles for you and Aura during the call.",
                            checked = showCaptions,
                            onCheckedChange = { scope.launch { preferences.setShowCaptions(it) } },
                            testTag = "toggle_show_captions"
                        )
                    }
                }

                // 3. Audio & Interruption Protection
                item {
                    AuraCard(modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.foundation.text.BasicText(
                            text = "Anti-Interruption & Audio Processing",
                            style = typography.titleMedium.copy(color = colors.textPrimary)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        androidx.compose.foundation.text.BasicText(
                            text = "Adaptive noise gate (120Hz filter) prevents accidental cuts from breathing or background noise.",
                            style = typography.bodySmall.copy(color = colors.textSecondary)
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        AuraToggleRow(
                            title = "Allow Interrupting Aura",
                            subtitle = "Allows your voice to cut off Aura while she is talking.",
                            checked = allowInterrupting,
                            onCheckedChange = { scope.launch { preferences.setAllowInterrupting(it) } },
                            testTag = "toggle_allow_interrupting"
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        androidx.compose.foundation.text.BasicText(
                            text = "Interruption Sensitivity",
                            style = typography.labelLarge.copy(color = colors.textPrimary)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(AuraPreferences.INTERRUPTION_SENSITIVITY_OPTIONS) { opt ->
                                AuraChip(
                                    text = opt,
                                    selected = interruptionSens == opt,
                                    onClick = { scope.launch { preferences.setInterruptionSensitivity(opt) } }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        androidx.compose.foundation.text.BasicText(
                            text = "Noise Gate Strength",
                            style = typography.labelLarge.copy(color = colors.textPrimary)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(AuraPreferences.NOISE_GATE_OPTIONS) { gateOpt ->
                                AuraChip(
                                    text = gateOpt,
                                    selected = noiseGateStrength == gateOpt,
                                    onClick = { scope.launch { preferences.setNoiseGateStrength(gateOpt) } }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        androidx.compose.foundation.text.BasicText(
                            text = "Auto-Hangup on Silence",
                            style = typography.labelLarge.copy(color = colors.textPrimary)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val hangupOptions = listOf(15 to "15s", 30 to "30s (Default)", 60 to "60s", 0 to "Disabled")
                            items(hangupOptions) { (sec, label) ->
                                AuraChip(
                                    text = label,
                                    selected = autoHangupSec == sec,
                                    onClick = { scope.launch { preferences.setAutoHangupSeconds(sec) } }
                                )
                            }
                        }
                    }
                }

                // 4. Real Vosk "Hey Aura" Wake Word Card
                item {
                    AuraCard(modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.foundation.text.BasicText(
                            text = "Offline Wake Word (\"Hey Aura\")",
                            style = typography.titleMedium.copy(color = colors.textPrimary)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        androidx.compose.foundation.text.BasicText(
                            text = "Powered by local Vosk Indian-English acoustic model (36 MB). Zero cloud audio transmission until activated.",
                            style = typography.bodySmall.copy(color = colors.textSecondary)
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        AuraToggleRow(
                            title = "Listen for \"Hey Aura\"",
                            subtitle = "Continuously listens for wake trigger in background service.",
                            checked = wakeEnabled,
                            onCheckedChange = { scope.launch { preferences.setWakePhraseEnabled(it) } },
                            testTag = "toggle_wake_phrase"
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        androidx.compose.foundation.text.BasicText(
                            text = "Wake Word Sensitivity",
                            style = typography.labelLarge.copy(color = colors.textPrimary)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(AuraPreferences.WAKE_SENSITIVITY_OPTIONS) { s ->
                                AuraChip(
                                    text = s.replaceFirstChar { it.uppercase() },
                                    selected = wakeSensitivity == s,
                                    onClick = { scope.launch { preferences.setWakeWordSensitivity(s) } }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        // Vosk Model Download / Readiness status
                        when (val status = voskStatus) {
                            is VoskModelStatus.Ready -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AuraBadge(text = "✓ Vosk Model Ready (Offline)", isSuccess = true)
                                    AuraButton(
                                        text = "Test Wake Detection",
                                        onClick = {
                                            Toast.makeText(
                                                context,
                                                "Say 'Hey Aura' or 'Aura' clearly to test.",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        },
                                        isPrimary = false
                                    )
                                }
                            }
                            is VoskModelStatus.Downloading -> {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        androidx.compose.foundation.text.BasicText(
                                            text = "Downloading Model (${status.progressPercent}%)...",
                                            style = typography.labelSmall.copy(color = colors.accentStart)
                                        )
                                        androidx.compose.foundation.text.BasicText(
                                            text = "${status.bytesDownloaded / 1024 / 1024}MB / ${status.totalBytes / 1024 / 1024}MB",
                                            style = typography.labelSmall.copy(color = colors.textSecondary)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(colors.surfaceRaised)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(status.progressPercent / 100f)
                                                .fillMaxHeight()
                                                .background(colors.accentMiddle)
                                        )
                                    }
                                }
                            }
                            is VoskModelStatus.NotDownloaded, is VoskModelStatus.Error -> {
                                if (status is VoskModelStatus.Error) {
                                    androidx.compose.foundation.text.BasicText(
                                        text = "Error: ${status.message}",
                                        style = typography.labelSmall.copy(color = colors.danger)
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                                AuraButton(
                                    text = "Download Wake Model (~36 MB)",
                                    onClick = {
                                        scope.launch {
                                            val res = voskModelManager.downloadAndExtractModel()
                                            if (res.isSuccess) {
                                                Toast.makeText(context, "Vosk model downloaded successfully", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "Download failed: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                    isPrimary = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                // 5. Edge Lighting & Overlay Controls
                item {
                    AuraCard(modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.foundation.text.BasicText(
                            text = "Edge Lighting & Floating Overlays",
                            style = typography.titleMedium.copy(color = colors.textPrimary)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        androidx.compose.foundation.text.BasicText(
                            text = "Pulsing screen perimeter glow indicates Aura's state (listening, thinking, speaking) over any app.",
                            style = typography.bodySmall.copy(color = colors.textSecondary)
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        AuraToggleRow(
                            title = "Edge Lighting Overlay",
                            subtitle = "Full-screen border glow with caption pill.",
                            checked = edgeLightingEnabled,
                            onCheckedChange = { scope.launch { preferences.setEdgeLightingEnabled(it) } },
                            testTag = "toggle_edge_lighting"
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        androidx.compose.foundation.text.BasicText(
                            text = "Border Thickness",
                            style = typography.labelLarge.copy(color = colors.textPrimary)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val thicknesses = listOf(4 to "4dp", 8 to "8dp (Default)", 12 to "12dp", 16 to "16dp")
                            items(thicknesses) { (dpVal, lbl) ->
                                AuraChip(
                                    text = lbl,
                                    selected = edgeLightingThickness == dpVal,
                                    onClick = { scope.launch { preferences.setEdgeLightingThickness(dpVal) } }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        AuraToggleRow(
                            title = "Floating Mini-Orb Overlay",
                            subtitle = "Draggable floating orb outside the app for quick control.",
                            checked = overlayEnabled,
                            onCheckedChange = { scope.launch { preferences.setOverlayEnabled(it) } },
                            testTag = "toggle_mini_orb"
                        )
                    }
                }

                // 6. Agent Voice Selection (All 30 Voices Catalog)
                item {
                    val activeVoiceInfo = VoiceCatalog.findVoice(voiceName) ?: VoiceCatalog.VOICES.first()
                    AuraCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                androidx.compose.foundation.text.BasicText(
                                    text = "Agent Voice (${VoiceCatalog.VOICES.size} Available)",
                                    style = typography.titleMedium.copy(color = colors.textPrimary)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                androidx.compose.foundation.text.BasicText(
                                    text = "Selected: ${activeVoiceInfo.name} (${activeVoiceInfo.gender}) - ${activeVoiceInfo.styleNote}",
                                    style = typography.bodySmall.copy(color = colors.accentStart)
                                )
                            }
                            AuraButton(
                                text = if (showAllVoices) "Collapse" else "Show All",
                                onClick = { showAllVoices = !showAllVoices },
                                isPrimary = false
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        val displayList = if (showAllVoices) VoiceCatalog.VOICES else VoiceCatalog.VOICES.take(8)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(displayList) { v ->
                                AuraChip(
                                    text = "${v.name} (${v.gender.first()})",
                                    selected = voiceName.equals(v.name, ignoreCase = true),
                                    onClick = {
                                        scope.launch {
                                            preferences.setVoiceName(v.name)
                                            diagnostics.setVoice(v.name)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // 7. Gemini Live Model Selection
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
                            text = "Select active model supporting bidirectional streaming (bidiGenerateContent):",
                            style = typography.bodySmall.copy(color = colors.textSecondary)
                        )
                        Spacer(modifier = Modifier.height(10.dp))

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

                        if (displayedModels.isNotEmpty() && !displayedModels.contains(inputModel)) {
                            Spacer(modifier = Modifier.height(8.dp))
                            androidx.compose.foundation.text.BasicText(
                                text = "⚠️ This model is not available for your key. Pick one from the fetched list above.",
                                style = typography.labelSmall.copy(color = colors.warning)
                            )
                        }

                        if (inputModel.equals("gemini-3.8-live-extended-thinking", ignoreCase = true)) {
                            Spacer(modifier = Modifier.height(14.dp))
                            androidx.compose.foundation.text.BasicText(
                                text = "Thinking Level (Required for Extended Thinking)",
                                style = typography.labelLarge.copy(color = colors.accentStart)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
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

                // 8. Persona & Owner Customization
                item {
                    AuraCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.foundation.text.BasicText(
                                text = "Owner & Persona Customization",
                                style = typography.titleMedium.copy(color = colors.textPrimary)
                            )
                            AuraButton(
                                text = "Reset Extra",
                                onClick = {
                                    scope.launch {
                                        preferences.resetExtraInstructions()
                                        inputExtraInstructions = ""
                                    }
                                },
                                isPrimary = false
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))

                        androidx.compose.foundation.text.BasicText(
                            text = "Owner Name / Nickname",
                            style = typography.labelLarge.copy(color = colors.textSecondary)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        AuraTextField(
                            value = inputOwnerName,
                            onValueChange = {
                                inputOwnerName = it
                                scope.launch { preferences.setOwnerName(it) }
                            },
                            placeholder = AuraPreferences.DEFAULT_OWNER_NAME,
                            testTag = "owner_name_field"
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        androidx.compose.foundation.text.BasicText(
                            text = "Custom / Extra Instructions",
                            style = typography.labelLarge.copy(color = colors.textSecondary)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        AuraTextField(
                            value = inputExtraInstructions,
                            onValueChange = {
                                inputExtraInstructions = it
                                scope.launch { preferences.setExtraInstructions(it) }
                            },
                            singleLine = false,
                            placeholder = "Optional preferences (e.g. 'Speak extra concisely', 'Always confirm before sending emails')...",
                            testTag = "extra_instructions_field"
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        AuraToggleRow(
                            title = "Include System Apps in Inventory",
                            subtitle = "Feeds full package inventory to Aura for direct opening without guessing.",
                            checked = includeSystemApps,
                            onCheckedChange = { scope.launch { preferences.setIncludeSystemApps(it) } }
                        )
                    }
                }

                // 9. Permission Doctor & Root Health Check Card
                item {
                    AuraCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                androidx.compose.foundation.text.BasicText(
                                    text = "Permission Doctor",
                                    style = typography.titleMedium.copy(color = colors.textPrimary)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                androidx.compose.foundation.text.BasicText(
                                    text = "Diagnose and auto-grant permissions via Root/SU.",
                                    style = typography.bodySmall.copy(color = colors.textSecondary)
                                )
                            }
                            AuraButton(
                                text = if (isRunningDoctor) "Fixing..." else "Auto-Fix All",
                                onClick = {
                                    scope.launch {
                                        isRunningDoctor = true
                                        val isRoot = rootManager.isRootAvailable()
                                        if (isRoot) {
                                            rootManager.autoGrantEverything()
                                            permissionDoctor.runSilentHealthCheck()
                                            permissionDoctor.applyOemFixes()
                                            doctorReportText = "Auto-granted all system permissions via root."
                                        } else {
                                            doctorReportText = "Root not detected. Please grant permissions manually."
                                        }
                                        permissionItems = permissionDoctor.getAllCheckItems()
                                        isRunningDoctor = false
                                    }
                                },
                                isPrimary = false,
                                enabled = !isRunningDoctor
                            )
                        }

                        doctorReportText?.let { report ->
                            Spacer(modifier = Modifier.height(6.dp))
                            androidx.compose.foundation.text.BasicText(
                                text = report,
                                style = typography.labelSmall.copy(color = colors.accentStart)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Check items list
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            permissionItems.forEach { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(colors.surfaceRaised)
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                        androidx.compose.foundation.text.BasicText(
                                            text = item.title,
                                            style = typography.bodyMedium.copy(color = colors.textPrimary)
                                        )
                                        androidx.compose.foundation.text.BasicText(
                                            text = item.description,
                                            style = typography.labelSmall.copy(color = colors.textTertiary)
                                        )
                                    }
                                    if (item.isGranted) {
                                        AuraBadge(text = "✓ Granted", isSuccess = true)
                                    } else {
                                        AuraButton(
                                            text = "Fix",
                                            onClick = {
                                                scope.launch {
                                                    item.fixAction()
                                                    permissionItems = permissionDoctor.getAllCheckItems()
                                                }
                                            },
                                            isPrimary = false
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 10. General System Toggles
                item {
                    AuraCard(modifier = Modifier.fillMaxWidth()) {
                        AuraToggleRow(
                            title = "Confirm Risky Actions",
                            subtitle = "Voice confirmation required before delete, reboot, or critical changes.",
                            checked = confirmRisky,
                            onCheckedChange = { scope.launch { preferences.setConfirmRiskyActions(it) } },
                            testTag = "toggle_confirm_risky"
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        AuraToggleRow(
                            title = "Target WhatsApp Business",
                            subtitle = "Automate messages through com.whatsapp.w4b rather than standard WhatsApp.",
                            checked = isWaBusiness,
                            onCheckedChange = { scope.launch { preferences.setWhatsappBusiness(it) } },
                            testTag = "toggle_wa_business"
                        )
                    }
                }

                // 11. Live Diagnostics Panel Trigger
                item {
                    AuraCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                androidx.compose.foundation.text.BasicText(
                                    text = "Live Diagnostics & Telemetry",
                                    style = typography.titleMedium.copy(color = colors.textPrimary)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                androidx.compose.foundation.text.BasicText(
                                    text = "Inspect WebSocket frames, noise floor, gate state, and recent events.",
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

                // 12. Setup Wizard Launcher
                item {
                    AuraButton(
                        text = "Re-run Setup Wizard & Hardware Test",
                        onClick = onNavigateToSetup,
                        isPrimary = false,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "rerun_setup_btn"
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }

        // Live Diagnostics Full Modal
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
            val diagNoiseFloor by diagnostics.noiseFloor.collectAsState()
            val diagNoiseGate by diagnostics.noiseGateStatus.collectAsState()
            val diagInterruptions by diagnostics.recentInterruptionsCount.collectAsState()
            val diagCaptionTurn by diagnostics.captionTurnInfo.collectAsState()
            val diagOverlayState by diagnostics.overlayStateInfo.collectAsState()
            val diagWakeState by diagnostics.wakeWordState.collectAsState()
            val diagAppInv by diagnostics.appInventorySize.collectAsState()
            val diagThoughts by diagnostics.thoughtHistory.collectAsState()
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
                        .fillMaxWidth(0.95f)
                        .fillMaxHeight(0.92f)
                        .clickable(enabled = false) {}
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.foundation.text.BasicText(
                            text = "Live Diagnostics & Telemetry",
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
                        verticalArrangement = Arrangement.spacedBy(6.dp)
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
                            DiagnosticRow("Noise Floor", diagNoiseFloor, colors.textPrimary)
                            DiagnosticRow("Noise Gate", diagNoiseGate, if (diagNoiseGate.startsWith("OPEN")) colors.success else colors.textSecondary)
                            DiagnosticRow("Interruptions", "$diagInterruptions", if (diagInterruptions > 0) colors.warning else colors.textPrimary)
                            DiagnosticRow("Caption State", diagCaptionTurn, colors.accentStart)
                            DiagnosticRow("Overlay State", diagOverlayState, colors.textPrimary)
                            DiagnosticRow("Wake Word Engine", diagWakeState, colors.textPrimary)
                            DiagnosticRow("App Inventory", diagAppInv, colors.textPrimary)
                            DiagnosticRow("Last Close Code", diagLastClose, colors.textSecondary)
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

                        // Recent Thoughts Section (Filtered from captions)
                        if (diagThoughts.isNotEmpty()) {
                            item {
                                Spacer(modifier = Modifier.height(8.dp))
                                androidx.compose.foundation.text.BasicText(
                                    text = "Model Internal Thoughts (Filtered):",
                                    style = typography.titleMedium.copy(color = colors.accentStart)
                                )
                            }
                            items(diagThoughts.takeLast(5)) { thought ->
                                androidx.compose.foundation.text.BasicText(
                                    text = "🧠 $thought",
                                    style = typography.labelSmall.copy(color = colors.textSecondary)
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
