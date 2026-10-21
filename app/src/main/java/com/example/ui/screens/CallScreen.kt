package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.core.live.LiveConnectionState
import com.example.core.tools.ActionFeedItem
import com.example.core.tools.ActionStatus
import com.example.service.AuraForegroundService
import com.example.ui.components.*
import com.example.ui.theme.AuraTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    val coroutineScope = rememberCoroutineScope()
    val colors = AuraTheme.colors
    val typography = AuraTheme.typography

    val isCallActive by AuraForegroundService.isCallActive.collectAsState()
    val service = AuraForegroundService.currentService

    // Permission launcher for microphone
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            AuraForegroundService.startCall(context)
        } else {
            Toast.makeText(context, "Microphone permission is required for Aura voice call", Toast.LENGTH_LONG).show()
        }
    }

    fun startCallWithPermission() {
        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (hasMic) {
            AuraForegroundService.startCall(context)
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

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

    // Synchronize speaker state from preferences
    LaunchedEffect(service) {
        if (service != null) {
            val prefSpeaker = service.preferences.useSpeaker.first()
            isSpeakerOn = prefSpeaker
        }
    }

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
        speakerAmp > 0.04f -> OrbState.SPEAKING
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
                            startCallWithPermission()
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
                OrbState.ERROR -> (connectionState as? LiveConnectionState.Error)?.message ?: "Connection error"
                OrbState.RECONNECTING -> "Reconnecting to Live..."
            }
            Spacer(modifier = Modifier.height(16.dp))
            androidx.compose.foundation.text.BasicText(
                text = stateLabel,
                style = typography.labelLarge.copy(
                    color = if (orbState == OrbState.ERROR) colors.danger else colors.accentStart
                ),
                maxLines = 2
            )

            Spacer(modifier = Modifier.weight(0.15f))

            // Realtime Live Captions
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 70.dp, max = 120.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface.copy(alpha = 0.6f))
                    .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (connectionState is LiveConnectionState.Error) {
                    val err = (connectionState as LiveConnectionState.Error).message
                    androidx.compose.foundation.text.BasicText(
                        text = "Error: $err",
                        style = typography.bodyMedium.copy(color = colors.danger),
                        maxLines = 3
                    )
                } else if (modelTranscript.isNotBlank()) {
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
                        service?.audioPlayer?.flushAndInterrupt()
                        if (isCallActive) {
                            AuraForegroundService.endCall(context)
                        }
                    },
                    contentDescription = "Stop speech and active actions",
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
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val startTime = System.currentTimeMillis()
                                var isHolding = false

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.any { it.pressed }
                                    if (!pressed) {
                                        val duration = System.currentTimeMillis() - startTime
                                        if (isHolding) {
                                            // Released hold-to-talk -> mute back
                                            service?.audioStreamer?.setMuted(true)
                                        } else if (duration < 350) {
                                            // Short tap -> toggle mute
                                            val cur = service?.audioStreamer?.isMuted?.value ?: false
                                            service?.audioStreamer?.setMuted(!cur)
                                        }
                                        break
                                    } else if (!isHolding && System.currentTimeMillis() - startTime >= 350) {
                                        // Held down for 350ms -> Push-to-Talk unmutes!
                                        isHolding = true
                                        service?.audioStreamer?.setMuted(false)
                                    }
                                }
                            }
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
                                startCallWithPermission()
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
                        val newSpeakerState = !isSpeakerOn
                        isSpeakerOn = newSpeakerState
                        service?.audioPlayer?.setSpeakerphoneOn(newSpeakerState)
                        coroutineScope.launch {
                            service?.preferences?.setUseSpeaker(newSpeakerState)
                        }
                    },
                    contentDescription = "Speakerphone",
                    backgroundColor = if (isSpeakerOn) colors.accentStart.copy(alpha = 0.25f) else colors.surfaceRaised,
                    tint = if (isSpeakerOn) colors.accentStart else colors.textPrimary,
                    size = 52.dp,
                    testTag = "speaker_toggle_btn"
                )
            }
        }

        // Keyboard text overlay sheet (with proper imePadding so it sits on top of soft keyboard)
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
                        .navigationBarsPadding()
                        .imePadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AuraTextField(
                        value = keyboardText,
                        onValueChange = { keyboardText = it },
                        placeholder = "Type command or message to Aura...",
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (keyboardText.isNotBlank()) {
                                    val textToSend = keyboardText
                                    keyboardText = ""
                                    showKeyboardInput = false
                                    if (!isCallActive) {
                                        startCallWithPermission()
                                    }
                                    service?.liveClient?.sendTextMessage(textToSend)
                                }
                            }
                        ),
                        testTag = "keyboard_input_field"
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    AuraIconButton(
                        icon = AuraIcons.Send,
                        onClick = {
                            if (keyboardText.isNotBlank()) {
                                val textToSend = keyboardText
                                keyboardText = ""
                                showKeyboardInput = false
                                if (!isCallActive) {
                                    startCallWithPermission()
                                }
                                service?.liveClient?.sendTextMessage(textToSend)
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
