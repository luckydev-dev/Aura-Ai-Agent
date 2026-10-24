package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneCallback
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.agent.PersonaBuilder
import com.example.core.audio.OwnerVoiceVerifier
import com.example.core.memory.MemoryRepository
import com.example.core.root.RootManager
import com.example.core.settings.AuraPreferences
import com.example.core.telephony.CallAnnouncementManager
import com.example.core.tools.AppInventory
import com.example.ui.overlay.CursorSpeed
import com.example.ui.overlay.CursorStyle
import com.example.ui.theme.AuraTheme
import kotlinx.coroutines.launch

@Composable
fun OwnerVoiceEnrollmentCard(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = AuraTheme.colors
    val typography = AuraTheme.typography

    val verifier = remember { OwnerVoiceVerifier(context) }
    var hasProfile by remember { mutableStateOf(verifier.hasProfile()) }
    var isRecording by remember { mutableStateOf(false) }
    var recordProgress by remember { mutableStateOf(0f) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var profileInfo by remember { mutableStateOf(verifier.getProfile()) }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, colors.border, RoundedCornerShape(20.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = Color(0xFF7085FF),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Owner Voice Recognition",
                    style = typography.titleMedium.copy(color = colors.textPrimary)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Record a 3-second voice sample so Maya only responds when you speak, ignoring unrecognized background voices.",
                style = typography.bodySmall.copy(color = colors.textSecondary)
            )

            Spacer(modifier = Modifier.height(14.dp))

            if (hasProfile && profileInfo != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E2640))
                        .padding(12.dp)
                ) {
                    Column {
                        Text(
                            text = "✓ Owner Voice Enrolled",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4CAF50)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Pitch F0: ${profileInfo?.f0Mean?.toInt()} Hz | Centroid: ${profileInfo?.spectralCentroid?.toInt()} Hz",
                            fontSize = 11.sp,
                            color = colors.textSecondary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (isRecording) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Recording 3s sample... Speak 'Hey Maya, this is Lucky'",
                        style = typography.labelMedium.copy(color = Color(0xFFFFB347))
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { recordProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = Color(0xFF7085FF)
                    )
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                isRecording = true
                                recordProgress = 0f
                                statusText = null
                                val result = verifier.enrollOwnerVoice { prog ->
                                    recordProgress = prog
                                }
                                isRecording = false
                                result.onSuccess { prof ->
                                    hasProfile = true
                                    profileInfo = prof
                                    statusText = "Voice sample saved! Maya will now only respond to you."
                                }.onFailure { err ->
                                    statusText = "Enrollment failed: ${err.message}"
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7085FF)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (hasProfile) "Re-record Sample" else "Record Voice Sample (3s)")
                    }

                    if (hasProfile) {
                        OutlinedButton(
                            onClick = {
                                verifier.deleteProfile()
                                hasProfile = false
                                profileInfo = null
                                statusText = "Owner voice profile deleted."
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(0.5f)
                        ) {
                            Text("Reset", color = colors.danger)
                        }
                    }
                }
            }

            statusText?.let { msg ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = msg,
                    style = typography.labelSmall.copy(color = if (msg.contains("failed")) colors.danger else colors.success)
                )
            }
        }
    }
}

@Composable
fun PersonaSettingsCard(
    preferences: AuraPreferences,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = AuraTheme.colors
    val typography = AuraTheme.typography

    val assistantName by preferences.assistantName.collectAsState(initial = "Maya")
    val selectedPersona by preferences.selectedPersona.collectAsState(initial = "maya")
    val customPrompt by preferences.getPersonaCustomPrompt(selectedPersona).collectAsState(initial = "")

    var inputName by remember(assistantName) { mutableStateOf(assistantName) }
    var inputPrompt by remember(customPrompt) { mutableStateOf(customPrompt) }
    var showEffectiveModal by remember { mutableStateOf(false) }

    val personaPresets = listOf("maya", "friday", "venom", "jarvis", "custom")

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, colors.border, RoundedCornerShape(20.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Face,
                    contentDescription = null,
                    tint = Color(0xFFFFB347),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Persona & Identity",
                    style = typography.titleMedium.copy(color = colors.textPrimary)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Choose a persona preset or write custom instructions. Maya is the warm, girlfriend-style voice companion.",
                style = typography.bodySmall.copy(color = colors.textSecondary)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Assistant Name Input
            Text("Assistant Name", style = typography.labelMedium.copy(color = colors.textPrimary))
            Spacer(modifier = Modifier.height(6.dp))
            AuraTextField(
                value = inputName,
                onValueChange = {
                    inputName = it
                    scope.launch { preferences.setAssistantName(it) }
                },
                placeholder = "Maya",
                testTag = "assistant_name_input"
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Persona Preset Selector
            Text("Preset Profile", style = typography.labelMedium.copy(color = colors.textPrimary))
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                personaPresets.forEach { preset ->
                    val isSelected = selectedPersona.equals(preset, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) Color(0xFF38436B) else Color(0xFF1B1E2E))
                            .border(1.dp, if (isSelected) Color(0xFF7085FF) else Color(0xFF282C3D), RoundedCornerShape(12.dp))
                            .clickable {
                                scope.launch {
                                    preferences.setSelectedPersona(preset)
                                }
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = preset.replaceFirstChar { it.uppercase() },
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) Color.White else Color(0xFFA0A5B9)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Editable Instructions
            Text("Active Persona Prompt Editor", style = typography.labelMedium.copy(color = colors.textPrimary))
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Placeholders: {assistant_name}, {owner_name}",
                style = typography.labelSmall.copy(color = colors.textTertiary)
            )
            Spacer(modifier = Modifier.height(6.dp))

            OutlinedTextField(
                value = inputPrompt.ifBlank { PersonaBuilder.getDefaultPromptForPersona(selectedPersona) },
                onValueChange = {
                    inputPrompt = it
                    scope.launch {
                        preferences.setPersonaCustomPrompt(selectedPersona, it)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                textStyle = typography.bodySmall.copy(color = colors.textPrimary, fontFamily = FontFamily.Monospace),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF7085FF),
                    unfocusedBorderColor = Color(0xFF282C3D)
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = { showEffectiveModal = true },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("View Effective Full System Instruction", color = Color(0xFF7085FF))
            }
        }
    }

    if (showEffectiveModal) {
        EffectiveInstructionDialog(
            preferences = preferences,
            onDismiss = { showEffectiveModal = false }
        )
    }
}

@Composable
fun EdgeLightingSettingsCard(
    preferences: AuraPreferences,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val colors = AuraTheme.colors
    val typography = AuraTheme.typography

    val edgeEnabled by preferences.edgeLightingEnabled.collectAsState(initial = true)
    val showInApp by preferences.edgeLightingShowInApp.collectAsState(initial = true)
    val intensity by preferences.edgeLightingIntensity.collectAsState(initial = 1.0f)
    val thickness by preferences.edgeLightingThickness.collectAsState(initial = 8)

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, colors.border, RoundedCornerShape(20.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Lightbulb,
                    contentDescription = null,
                    tint = Color(0xFF00F0FF),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Original Screen Edge Lighting",
                        style = typography.titleMedium.copy(color = colors.textPrimary)
                    )
                    Text(
                        text = "Glowing rotating ring along the phone border when Maya is speaking or listening.",
                        style = typography.bodySmall.copy(color = colors.textSecondary)
                    )
                }
                Switch(
                    checked = edgeEnabled,
                    onCheckedChange = { scope.launch { preferences.setEdgeLightingEnabled(it) } }
                )
            }

            if (edgeEnabled) {
                Spacer(modifier = Modifier.height(16.dp))

                // Show In App toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Show In-App Glow",
                            style = typography.labelMedium.copy(color = colors.textPrimary)
                        )
                        Text(
                            text = "Keep glowing border visible even while inside the Aura app.",
                            style = typography.bodySmall.copy(color = colors.textSecondary)
                        )
                    }
                    Switch(
                        checked = showInApp,
                        onCheckedChange = { scope.launch { preferences.setEdgeLightingShowInApp(it) } }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Intensity Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Glow Intensity", style = typography.labelMedium.copy(color = colors.textPrimary))
                    Text(
                        "${(intensity * 100).toInt()}%",
                        style = typography.labelSmall.copy(color = colors.accentStart, fontWeight = FontWeight.Bold)
                    )
                }
                Slider(
                    value = intensity,
                    onValueChange = { scope.launch { preferences.setEdgeLightingIntensity(it) } },
                    valueRange = 0.2f..1.5f,
                    colors = SliderDefaults.colors(
                        thumbColor = colors.accentStart,
                        activeTrackColor = colors.accentStart
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Thickness Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Border Thickness", style = typography.labelMedium.copy(color = colors.textPrimary))
                    Text(
                        "${thickness} dp",
                        style = typography.labelSmall.copy(color = colors.accentStart, fontWeight = FontWeight.Bold)
                    )
                }
                Slider(
                    value = thickness.toFloat(),
                    onValueChange = { scope.launch { preferences.setEdgeLightingThickness(it.toInt()) } },
                    valueRange = 4f..24f,
                    steps = 19,
                    colors = SliderDefaults.colors(
                        thumbColor = colors.accentStart,
                        activeTrackColor = colors.accentStart
                    )
                )
            }
        }
    }
}

@Composable
fun AgentCursorSettingsCard(
    preferences: AuraPreferences,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = AuraTheme.colors
    val typography = AuraTheme.typography

    val cursorEnabled by preferences.agentCursorEnabled.collectAsState(initial = true)
    val cursorStyle by preferences.agentCursorStyle.collectAsState(initial = "ARROW")
    val cursorSpeed by preferences.agentCursorSpeed.collectAsState(initial = "NORMAL")
    val cursorSize by preferences.agentCursorSizeDp.collectAsState(initial = 26f)
    val showTrail by preferences.agentCursorTrail.collectAsState(initial = true)
    val showRipple by preferences.agentCursorRipple.collectAsState(initial = true)
    val showLabel by preferences.agentCursorActionLabel.collectAsState(initial = true)
    val showUnlock by preferences.showCursorDuringUnlock.collectAsState(initial = false)

    fun triggerCursorPreview(
        previewStyle: CursorStyle? = null,
        previewSpeed: CursorSpeed? = null,
        previewSize: Float? = null
    ) {
        val controller = com.example.service.AuraForegroundService.currentService?.overlayController
        val cursorOverlay = controller?.agentCursorOverlay
            ?: com.example.ui.overlay.AgentCursorOverlay(context, com.example.AuraApplication.instance.inputController)
        cursorOverlay.previewAnimation(previewStyle, previewSpeed, previewSize)
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, colors.border, RoundedCornerShape(20.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Agent Mouse Pointer Cursor",
                        style = typography.titleMedium.copy(color = colors.textPrimary)
                    )
                    Text(
                        text = "Visible mouse cursor showing where the AI is touching, typing, and swiping in real time.",
                        style = typography.bodySmall.copy(color = colors.textSecondary)
                    )
                }
                Switch(
                    checked = cursorEnabled,
                    onCheckedChange = {
                        scope.launch {
                            preferences.setAgentCursorEnabled(it)
                            if (it) triggerCursorPreview()
                        }
                    }
                )
            }

            if (cursorEnabled) {
                Spacer(modifier = Modifier.height(16.dp))

                // Cursor Style: Arrow vs Dot vs Hand
                Text("Cursor Style", style = typography.labelMedium.copy(color = colors.textPrimary))
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf("ARROW" to "Arrow 🏹", "DOT" to "Dot ⚪", "HAND" to "Hand 👆").forEach { (styleKey, title) ->
                        val isSelected = cursorStyle.equals(styleKey, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) colors.accentStart.copy(alpha = 0.2f) else colors.surfaceRaised)
                                .border(1.dp, if (isSelected) colors.accentStart else colors.border, RoundedCornerShape(12.dp))
                                .clickable {
                                    scope.launch {
                                        preferences.setAgentCursorStyle(styleKey)
                                        val s = when (styleKey) {
                                            "DOT" -> CursorStyle.DOT
                                            "HAND" -> CursorStyle.HAND
                                            else -> CursorStyle.ARROW
                                        }
                                        triggerCursorPreview(previewStyle = s)
                                    }
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = title,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) colors.accentStart else colors.textSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Cursor Speed: Instant vs Fast vs Normal vs Slow
                Text("Movement Speed", style = typography.labelMedium.copy(color = colors.textPrimary))
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf("INSTANT" to "Instant", "FAST" to "Fast", "NORMAL" to "Normal", "SLOW" to "Cinematic").forEach { (spKey, title) ->
                        val isSelected = cursorSpeed.equals(spKey, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) colors.accentStart.copy(alpha = 0.2f) else colors.surfaceRaised)
                                .border(1.dp, if (isSelected) colors.accentStart else colors.border, RoundedCornerShape(10.dp))
                                .clickable {
                                    scope.launch {
                                        preferences.setAgentCursorSpeed(spKey)
                                        val sp = when (spKey) {
                                            "INSTANT" -> CursorSpeed.INSTANT
                                            "FAST" -> CursorSpeed.FAST
                                            "SLOW" -> CursorSpeed.SLOW
                                            else -> CursorSpeed.NORMAL
                                        }
                                        triggerCursorPreview(previewSpeed = sp)
                                    }
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = title,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) colors.accentStart else colors.textSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Size Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Cursor Size", style = typography.labelMedium.copy(color = colors.textPrimary))
                    Text(
                        "${cursorSize.toInt()} dp",
                        style = typography.labelSmall.copy(color = colors.accentStart, fontWeight = FontWeight.Bold)
                    )
                }
                Slider(
                    value = cursorSize,
                    onValueChange = { scope.launch { preferences.setAgentCursorSizeDp(it) } },
                    onValueChangeFinished = { triggerCursorPreview(previewSize = cursorSize) },
                    valueRange = 18f..42f,
                    colors = SliderDefaults.colors(
                        thumbColor = colors.accentStart,
                        activeTrackColor = colors.accentStart
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Visual effect toggles
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Motion Trails", style = typography.bodySmall.copy(color = colors.textPrimary))
                    Switch(checked = showTrail, onCheckedChange = { scope.launch { preferences.setAgentCursorTrail(it) } })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Tap Ripples", style = typography.bodySmall.copy(color = colors.textPrimary))
                    Switch(checked = showRipple, onCheckedChange = { scope.launch { preferences.setAgentCursorRipple(it) } })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Action Label Pills", style = typography.bodySmall.copy(color = colors.textPrimary))
                    Switch(checked = showLabel, onCheckedChange = { scope.launch { preferences.setAgentCursorActionLabel(it) } })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Show During Phone Unlock", style = typography.bodySmall.copy(color = colors.textPrimary))
                    Switch(checked = showUnlock, onCheckedChange = { scope.launch { preferences.setShowCursorDuringUnlock(it) } })
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Demo Test Button
                AuraButton(
                    text = "🎯 Test Agent Cursor Demo",
                    onClick = {
                        val controller = com.example.service.AuraForegroundService.currentService?.overlayController
                        if (controller != null) {
                            controller.agentCursorOverlay.testDemo()
                        } else {
                            val cursorOverlay = com.example.ui.overlay.AgentCursorOverlay(
                                context,
                                com.example.AuraApplication.instance.inputController
                            )
                            cursorOverlay.testDemo()
                        }
                    },
                    isPrimary = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun CallAnnouncementSettingsCard(
    preferences: AuraPreferences,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = AuraTheme.colors
    val typography = AuraTheme.typography

    val callEnabled by preferences.callAnnouncementEnabled.collectAsState(initial = true)
    val whatsappEnabled by preferences.callAnnounceWhatsapp.collectAsState(initial = true)

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, colors.border, RoundedCornerShape(20.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.PhoneCallback,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Incoming Call Announcements",
                    style = typography.titleMedium.copy(color = colors.textPrimary),
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = callEnabled,
                    onCheckedChange = { scope.launch { preferences.setCallAnnouncementEnabled(it) } }
                )
            }

            if (callEnabled) {
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "WhatsApp & Telegram VoIP Calls",
                        style = typography.bodyMedium.copy(color = colors.textPrimary),
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = whatsappEnabled,
                        onCheckedChange = { scope.launch { preferences.setCallAnnounceWhatsapp(it) } }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = {
                        val cam = CallAnnouncementManager.instance
                        if (cam != null) {
                            cam.simulateIncomingCall("Rohit Sharma", "+91 98765 43210")
                            Toast.makeText(context, "Simulated incoming call announcement", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Start Aura service first to test calls", Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E344D)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Test Incoming Call Announcement", color = Color.White)
                }
            }
        }
    }
}

@Composable
fun EffectiveInstructionDialog(
    preferences: AuraPreferences,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var fullPrompt by remember { mutableStateOf("Assembling prompt...") }

    LaunchedEffect(Unit) {
        val rootManager = RootManager(context)
        val memoryRepo = MemoryRepository(context)
        val appInv = AppInventory(context, rootManager)
        fullPrompt = PersonaBuilder.assembleSystemInstruction(context, preferences, memoryRepo, appInv)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Assembled System Instruction", fontWeight = FontWeight.Bold)
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF10121A))
                    .padding(12.dp)
            ) {
                Text(
                    text = fullPrompt,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFE2E4F0)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("Aura System Instruction", fullPrompt))
                    Toast.makeText(context, "System instruction copied!", Toast.LENGTH_SHORT).show()
                }
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Copy All")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
