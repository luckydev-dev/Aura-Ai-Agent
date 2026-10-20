package com.example.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.example.core.settings.AuraPreferences
import com.example.ui.components.*
import com.example.ui.theme.AuraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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

    val availableVoices = listOf("Aoede", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Zephyr")

    Column(
        modifier = modifier
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
                            text = if (isTestingConnection) "Testing..." else "Test Connection",
                            onClick = {
                                scope.launch {
                                    isTestingConnection = true
                                    val ok = testGeminiApi(inputApiKey)
                                    testConnectionStatus = if (ok) "Connection Successful!" else "Connection Failed. Check key."
                                    isTestingConnection = false
                                }
                            },
                            isPrimary = false,
                            enabled = !isTestingConnection && inputApiKey.isNotBlank(),
                            testTag = "test_connection_btn"
                        )
                        testConnectionStatus?.let { status ->
                            androidx.compose.foundation.text.BasicText(
                                text = status,
                                style = typography.labelSmall.copy(
                                    color = if (status.contains("Successful")) colors.success else colors.danger
                                )
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
                        placeholder = "gemini-2.5-flash-native-audio-preview",
                        testTag = "model_name_field"
                    )
                }
            }

            // Toggles
            item {
                AuraCard(modifier = Modifier.fillMaxWidth()) {
                    // Confirm risky actions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            androidx.compose.foundation.text.BasicText(
                                text = "Confirm Risky Actions",
                                style = typography.labelLarge.copy(color = colors.textPrimary)
                            )
                            androidx.compose.foundation.text.BasicText(
                                text = "Ask for voice confirmation before delete, reboot, or shutdown.",
                                style = typography.bodySmall.copy(color = colors.textSecondary)
                            )
                        }
                        AuraToggle(
                            checked = confirmRisky,
                            onCheckedChange = { scope.launch { preferences.setConfirmRiskyActions(it) } },
                            testTag = "toggle_confirm_risky"
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Wake phrase
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            androidx.compose.foundation.text.BasicText(
                                text = "Wake Phrase Detection",
                                style = typography.labelLarge.copy(color = colors.textPrimary)
                            )
                            androidx.compose.foundation.text.BasicText(
                                text = "Listen for voice trigger in background service.",
                                style = typography.bodySmall.copy(color = colors.textSecondary)
                            )
                        }
                        AuraToggle(
                            checked = wakeEnabled,
                            onCheckedChange = { scope.launch { preferences.setWakePhraseEnabled(it) } },
                            testTag = "toggle_wake_phrase"
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // WhatsApp Business
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            androidx.compose.foundation.text.BasicText(
                                text = "Target WhatsApp Business",
                                style = typography.labelLarge.copy(color = colors.textPrimary)
                            )
                            androidx.compose.foundation.text.BasicText(
                                text = "Send messages via com.whatsapp.w4b instead of standard WhatsApp.",
                                style = typography.bodySmall.copy(color = colors.textSecondary)
                            )
                        }
                        AuraToggle(
                            checked = isWaBusiness,
                            onCheckedChange = { scope.launch { preferences.setWhatsappBusiness(it) } },
                            testTag = "toggle_wa_business"
                        )
                    }
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
                            style = typography.titleMedium.copy(color = colors.textPrimary)
                        )
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
}

private suspend fun testGeminiApi(apiKey: String): Boolean = withContext(Dispatchers.IO) {
    if (apiKey.isBlank()) return@withContext false
    try {
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
            .build()
        val response = client.newCall(request).execute()
        response.isSuccessful
    } catch (_: Exception) {
        false
    }
}
