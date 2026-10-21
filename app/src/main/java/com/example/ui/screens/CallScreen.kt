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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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

    // Effective call UI state: call active AND not in terminal/error state
    val isCallUiActive = isCallActive && connectionState !is LiveConnectionState.Error

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
            }
        }
    }

    // Call duration timer
    var callSeconds by remember { mutableStateOf(0) }
    LaunchedEffect(isCallUiActive) {
        if (isCallUiActive) {
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
        connectionState is LiveConnectionState.Error -> OrbState.ERROR
        connectionState is LiveConnectionState.Reconnecting -> OrbState.RECONNECTING
        connectionState is LiveConnectionState.Connecting -> OrbState.IDLE
        connectionState !is LiveConnectionState.Connected || !isCallUiActive -> OrbState.IDLE
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
                        text = if (isCallUiActive) "%02d:%02d".format(callSeconds / 60, callSeconds % 60) else "Aura Ready",
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

            Spacer(modifier = Modifier.weight(0.12f))

            // The Hero Aura Living Orb
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (!isCallUiActive) {
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

            // Orb State Status Label - derived strictly from connectionState
            val stateLabel = when (connectionState) {
                is LiveConnectionState.Connecting -> "Connecting..."
                is LiveConnectionState.Reconnecting -> "Reconnecting..."
                is LiveConnectionState.Connected -> when {
                    speakerAmp > 0.04f -> "Speaking..."
                    isAuraActing -> "Operating device..."
                    isAuraThinking -> "Thinking..."
                    micAmp > 0.04f && !isMuted -> "Hearing you..."
                    else -> "Listening for you..."
                }
                is LiveConnectionState.Error -> "Connection error"
                LiveConnectionState.Disconnected -> if (isCallUiActive) "Connecting..." else "Tap orb or button to start call"
            }

            Spacer(modifier = Modifier.height(16.dp))
            androidx.compose.foundation.text.BasicText(
                text = stateLabel,
                style = typography.labelLarge.copy(
                    color = if (orbState == OrbState.ERROR) colors.danger else colors.accentStart
                ),
                maxLines = 1
            )

            Spacer(modifier = Modifier.weight(0.12f))

            // Realtime Captions OR Full Scrollable Error Card
            if (connectionState is LiveConnectionState.Error) {
                val errorMsg = (connectionState as LiveConnectionState.Error).message
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surface.copy(alpha = 0.85f))
                        .border(1.dp, colors.danger.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(colors.danger)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        androidx.compose.foundation.text.BasicText(
                            text = "Error details",
                            style = typography.labelLarge.copy(color = colors.danger)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 130.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        androidx.compose.foundation.text.BasicText(
                            text = errorMsg,
                            style = typography.bodySmall.copy(color = colors.textPrimary)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AuraButton(
                            text = "Copy Error",
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("Aura Error", errorMsg)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Error copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            icon = AuraIcons.Copy,
                            isPrimary = false,
                            testTag = "copy_error_btn"
                        )
                        AuraButton(
                            text = "Retry",
                            onClick = {
                                startCallWithPermission()
                            },
                            modifier = Modifier.weight(1f),
                            icon = AuraIcons.Refresh,
                            isPrimary = true,
                            testTag = "retry_call_btn"
                        )
                    }
                }
            } else {
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
                            text = if (isCallUiActive) "Speak naturally. You can interrupt Aura anytime." else "Say 'Open WhatsApp', 'Turn on Bluetooth', 'Read screen'...",
                            style = typography.bodySmall.copy(color = colors.textTertiary)
                        )
                    }
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
                                        // Held down for 350ms -> Push-to-Talk unmutes
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
                        .background(if (isCallUiActive) colors.danger else colors.accentStart)
                        .clickable {
                            if (isCallUiActive) {
                                AuraForegroundService.endCall(context)
                            } else {
                                startCallWithPermission()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.Image(
                        imageVector = if (isCallUiActive) AuraIcons.CallEnd else AuraIcons.Mic,
                        contentDescription = if (isCallUiActive) "End Call" else "Start Call",
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
                    contentDescription = if (isSpeakerOn) "Switch to Earpiece" else "Switch to Speaker",
                    backgroundColor = if (isSpeakerOn) colors.surfaceRaised else colors.accentStart.copy(alpha = 0.2f),
                    tint = if (isSpeakerOn) colors.textPrimary else colors.accentStart,
                    size = 52.dp,
                    testTag = "speaker_toggle_btn"
                )
            }
        }

        // Slide-up Keyboard Chat Sheet
        AnimatedVisibility(
            visible = showKeyboardInput,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(colors.surfaceRaised)
                    .border(1.dp, colors.border, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.foundation.text.BasicText(
                        text = "Type to Aura",
                        style = typography.titleMedium.copy(color = colors.textPrimary)
                    )
                    AuraIconButton(
                        icon = AuraIcons.Close,
                        onClick = { showKeyboardInput = false },
                        contentDescription = "Close text input",
                        size = 32.dp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AuraTextField(
                        value = keyboardText,
                        onValueChange = { keyboardText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = "Ask Aura something or give a command...",
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (keyboardText.isNotBlank()) {
                                    val msg = keyboardText.trim()
                                    keyboardText = ""
                                    showKeyboardInput = false
                                    service?.liveClient?.sendTextMessage(msg)
                                }
                            }
                        ),
                        testTag = "keyboard_input_field"
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    AuraIconButton(
                        icon = AuraIcons.Send,
                        onClick = {
                            if (keyboardText.isNotBlank()) {
                                val msg = keyboardText.trim()
                                keyboardText = ""
                                showKeyboardInput = false
                                service?.liveClient?.sendTextMessage(msg)
                            }
                        },
                        contentDescription = "Send Message",
                        backgroundColor = colors.accentStart,
                        tint = Color.White,
                        size = 48.dp,
                        testTag = "keyboard_send_btn"
                    )
                }
            }
        }
    }
}
