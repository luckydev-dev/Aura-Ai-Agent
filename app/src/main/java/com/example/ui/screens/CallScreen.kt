package com.example.ui.screens

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.core.live.LiveConnectionState
import com.example.core.tools.ActionFeedItem
import com.example.core.tools.ActionStatus
import com.example.service.AuraForegroundService
import com.example.ui.components.*
import com.example.ui.theme.AuraTheme
import kotlinx.coroutines.delay

@Composable
fun CallScreen(
    onNavigateToActivity: () -> Unit,
    onNavigateToMemory: () -> Unit,
    onNavigateToTools: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToSetup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = AuraTheme.colors
    val typography = AuraTheme.typography

    val isCallActive by AuraForegroundService.isCallActive.collectAsState()
    val service = AuraForegroundService.currentService

    // Safe produceState observables to prevent NullPointerException when service is null
    val connectionState by produceState<LiveConnectionState>(initialValue = LiveConnectionState.Disconnected, key1 = service) {
        val flow = service?.liveClient?.connectionState
        if (flow != null) {
            flow.collect { value = it }
        } else {
            value = LiveConnectionState.Disconnected
        }
    }

    val micAmp by produceState<Float>(initialValue = 0f, key1 = service) {
        val flow = service?.audioStreamer?.micAmplitude
        if (flow != null) {
            flow.collect { value = it }
        } else {
            value = 0f
        }
    }

    val speakerAmp by produceState<Float>(initialValue = 0f, key1 = service) {
        val flow = service?.audioPlayer?.speakerAmplitude
        if (flow != null) {
            flow.collect { value = it }
        } else {
            value = 0f
        }
    }

    val isMuted by produceState<Boolean>(initialValue = false, key1 = service) {
        val flow = service?.audioStreamer?.isMuted
        if (flow != null) {
            flow.collect { value = it }
        } else {
            value = false
        }
    }

    val isAuraActing by produceState<Boolean>(initialValue = false, key1 = service) {
        val flow = service?.liveClient?.isAuraActing
        if (flow != null) {
            flow.collect { value = it }
        } else {
            value = false
        }
    }

    val isAuraThinking by produceState<Boolean>(initialValue = false, key1 = service) {
        val flow = service?.liveClient?.isAuraThinking
        if (flow != null) {
            flow.collect { value = it }
        } else {
            value = false
        }
    }

    val userTranscript by produceState<String>(initialValue = "", key1 = service) {
        val flow = service?.liveClient?.userTranscript
        if (flow != null) {
            flow.collect { value = it }
        } else {
            value = ""
        }
    }

    val modelTranscript by produceState<String>(initialValue = "", key1 = service) {
        val flow = service?.liveClient?.modelTranscript
        if (flow != null) {
            flow.collect { value = it }
        } else {
            value = ""
        }
    }

    var isSpeakerOn by remember { mutableStateOf(true) }

    // Action feed items
    val actionFeed = remember { mutableStateListOf<ActionFeedItem>() }
    LaunchedEffect(service) {
        service?.toolRegistry?.actionFeed?.collect { item ->
            val index = actionFeed.indexOfFirst { it.id == item.id }
            if (index >= 0) {
                actionFeed[index] = item
            } else {
                actionFeed.add(0, item)
                if (actionFeed.size > 20) actionFeed.removeLast()
            }
        }
    }

    // Call duration timer
    var callSeconds by remember { mutableStateOf(0) }
    LaunchedEffect(isCallActive) {
        if (isCallActive) {
            callSeconds = 0
            while (true) {
                delay(1000)
                callSeconds++
            }
        } else {
            callSeconds = 0
        }
    }

    // Determine current Orb state
    val orbState = when {
        !isCallActive -> OrbState.IDLE
        connectionState is LiveConnectionState.Reconnecting -> OrbState.RECONNECTING
        connectionState is LiveConnectionState.Error -> OrbState.ERROR
        isAuraActing -> OrbState.ACTING
        speakerAmp > 0.05f -> OrbState.SPEAKING
        isAuraThinking -> OrbState.THINKING
        micAmp > 0.04f && !isMuted -> OrbState.LISTENING
        else -> OrbState.IDLE
    }

    val activeAmplitude = if (orbState == OrbState.SPEAKING) speakerAmp else micAmp

    // Keyboard chat sheet toggle
    var showKeyboardInput by remember { mutableStateOf(false) }
    var keyboardText by remember { mutableStateOf("") }

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
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Bar with Timer and Quick Nav Actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Connection/Call status indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceRaised)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when (connectionState) {
                                    is LiveConnectionState.Connected -> colors.success
                                    is LiveConnectionState.Connecting, is LiveConnectionState.Reconnecting -> colors.warning
                                    is LiveConnectionState.Error -> colors.danger
                                    else -> colors.textTertiary
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    androidx.compose.foundation.text.BasicText(
                        text = if (isCallActive) "%02d:%02d".format(callSeconds / 60, callSeconds % 60) else "Aura Ready",
                        style = typography.labelSmall.copy(color = colors.textPrimary)
                    )
                }

                // Quick access navigation buttons
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AuraIconButton(
                        icon = AuraIcons.History,
                        onClick = onNavigateToActivity,
                        contentDescription = "Activity",
                        size = 38.dp,
                        testTag = "nav_activity_btn"
                    )
                    AuraIconButton(
                        icon = AuraIcons.Memory,
                        onClick = onNavigateToMemory,
                        contentDescription = "Memory",
                        size = 38.dp,
                        testTag = "nav_memory_btn"
                    )
                    AuraIconButton(
                        icon = AuraIcons.Tools,
                        onClick = onNavigateToTools,
                        contentDescription = "Tools",
                        size = 38.dp,
                        testTag = "nav_tools_btn"
                    )
                    AuraIconButton(
                        icon = AuraIcons.Settings,
                        onClick = onNavigateToSettings,
                        contentDescription = "Settings",
                        size = 38.dp,
                        testTag = "nav_settings_btn"
                    )
                }
            }

            Spacer(modifier = Modifier.weight(0.15f))

            // The Hero Aura Living Orb
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (!isCallActive) {
                            AuraForegroundService.startCall(context)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                AuraOrb(
                    state = orbState,
                    amplitude = activeAmplitude,
                    size = 280.dp
                )
            }

            // Orb State Status Label
            val stateLabel = when (orbState) {
                OrbState.IDLE -> if (isCallActive) "Listening for you..." else "Tap orb or button to start call"
                OrbState.LISTENING -> "Hearing you..."
                OrbState.THINKING -> "Thinking..."
                OrbState.SPEAKING -> "Speaking..."
                OrbState.ACTING -> "Operating device..."
                OrbState.ERROR -> "Connection error"
                OrbState.RECONNECTING -> "Reconnecting..."
            }
            Spacer(modifier = Modifier.height(16.dp))
            androidx.compose.foundation.text.BasicText(
                text = stateLabel,
                style = typography.labelLarge.copy(color = colors.accentStart)
            )

            Spacer(modifier = Modifier.weight(0.15f))

            // Realtime Live Captions
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 70.dp, max = 110.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface.copy(alpha = 0.6f))
                    .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (modelTranscript.isNotBlank()) {
                    androidx.compose.foundation.text.BasicText(
                        text = modelTranscript,
                        style = typography.bodyLarge.copy(color = colors.textPrimary),
                        maxLines = 3
                    )
                } else if (userTranscript.isNotBlank()) {
                    androidx.compose.foundation.text.BasicText(
                        text = userTranscript,
                        style = typography.bodyMedium.copy(color = colors.textSecondary),
                        maxLines = 2
                    )
                } else {
                    androidx.compose.foundation.text.BasicText(
                        text = if (isCallActive) "Speak naturally. You can interrupt Aura anytime." else "Say 'Open WhatsApp', 'Turn on Bluetooth', 'Read screen'...",
                        style = typography.bodySmall.copy(color = colors.textTertiary)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Realtime Action Feed Chips
            if (actionFeed.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(actionFeed, key = { it.id }) { item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.surfaceRaised)
                                .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (item.status) {
                                            ActionStatus.RUNNING -> colors.warning
                                            ActionStatus.SUCCESS -> colors.success
                                            ActionStatus.FAILED -> colors.danger
                                        }
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            androidx.compose.foundation.text.BasicText(
                                text = item.description,
                                style = typography.labelSmall.copy(color = colors.textSecondary)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            // Bottom Call Control Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Emergency Stop Button
                AuraIconButton(
                    icon = AuraIcons.Stop,
                    onClick = {
                        service?.agentLoop?.stop()
                        service?.liveClient?.disconnect()
                        service?.audioPlayer?.flushAndInterrupt()
                        if (isCallActive) {
                            AuraForegroundService.endCall(context)
                        }
                    },
                    contentDescription = "Stop all actions",
                    backgroundColor = colors.danger.copy(alpha = 0.2f),
                    tint = colors.danger,
                    size = 52.dp,
                    testTag = "emergency_stop_btn"
                )

                // Mute / Hold-to-Talk Button
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(if (isMuted) colors.warning.copy(alpha = 0.25f) else colors.surfaceRaised)
                        .border(1.dp, colors.border, CircleShape)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    service?.audioStreamer?.setMuted(!isMuted)
                                },
                                onPress = {
                                    // Press to unmute (push-to-talk)
                                    service?.audioStreamer?.setMuted(false)
                                    tryAwaitRelease()
                                    // Release to mute
                                    service?.audioStreamer?.setMuted(true)
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.Image(
                        imageVector = if (isMuted) AuraIcons.MicOff else AuraIcons.Mic,
                        contentDescription = if (isMuted) "Unmute / Hold to Talk" else "Mute / Hold to Talk",
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(if (isMuted) colors.warning else colors.textPrimary),
                        modifier = Modifier.size(26.dp)
                    )
                }

                // Master Call Orb Button
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(if (isCallActive) colors.danger else colors.accentStart)
                        .clickable {
                            if (isCallActive) {
                                AuraForegroundService.endCall(context)
                            } else {
                                AuraForegroundService.startCall(context)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.Image(
                        imageVector = if (isCallActive) AuraIcons.CallEnd else AuraIcons.Mic,
                        contentDescription = if (isCallActive) "End Call" else "Start Call",
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White),
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Keyboard Input Toggle
                AuraIconButton(
                    icon = AuraIcons.Keyboard,
                    onClick = { showKeyboardInput = !showKeyboardInput },
                    contentDescription = "Type to Aura",
                    size = 52.dp,
                    testTag = "keyboard_toggle_btn"
                )

                // Speaker / Routing Toggle Button
                AuraIconButton(
                    icon = AuraIcons.Speaker,
                    onClick = {
                        isSpeakerOn = !isSpeakerOn
                        service?.audioPlayer?.setSpeakerphoneOn(isSpeakerOn)
                    },
                    contentDescription = "Speakerphone",
                    backgroundColor = if (isSpeakerOn) colors.accentStart.copy(alpha = 0.25f) else colors.surfaceRaised,
                    tint = if (isSpeakerOn) colors.accentStart else colors.textPrimary,
                    size = 52.dp,
                    testTag = "speaker_toggle_btn"
                )
            }
        }

        // Keyboard text overlay sheet
        if (showKeyboardInput) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { showKeyboardInput = false },
                contentAlignment = Alignment.BottomCenter
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                        .background(colors.surface)
                        .clickable(enabled = false) {}
                        .padding(16.dp)
                        .navigationBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AuraTextField(
                        value = keyboardText,
                        onValueChange = { keyboardText = it },
                        placeholder = "Type command or message to Aura...",
                        modifier = Modifier.weight(1f),
                        testTag = "keyboard_input_field"
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    AuraIconButton(
                        icon = AuraIcons.Send,
                        onClick = {
                            if (keyboardText.isNotBlank()) {
                                service?.liveClient?.sendTextMessage(keyboardText)
                                keyboardText = ""
                                showKeyboardInput = false
                            }
                        },
                        contentDescription = "Send",
                        backgroundColor = colors.accentStart,
                        tint = Color.White,
                        size = 46.dp,
                        testTag = "send_text_btn"
                    )
                }
            }
        }
    }
}
