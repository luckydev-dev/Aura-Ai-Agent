package com.example.ui.overlay

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.example.AuraApplication
import com.example.core.live.CaptionState
import com.example.core.live.LiveConnectionState
import com.example.core.settings.AuraPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

class OverlayController(
    private val context: Context,
    private val preferences: AuraPreferences,
    private val overlayManager: OverlayManager,
    private val edgeLightingOverlay: EdgeLightingOverlay
) {
    companion object {
        private const val TAG = "Aura"
    }

    private val controllerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun startObserving() {
        // 1. Edge Lighting Settings
        controllerScope.launch {
            preferences.edgeLightingThickness.collectLatest { th ->
                edgeLightingOverlay.setThickness(th.toFloat())
            }
        }
        controllerScope.launch {
            preferences.edgeLightingIntensity.collectLatest { inten ->
                edgeLightingOverlay.setIntensity(inten)
            }
        }
        controllerScope.launch {
            preferences.showCaptions.collectLatest { show ->
                edgeLightingOverlay.setShowCaptions(show)
            }
        }
    }

    fun updateAmplitudes(micAmp: Float, speakerAmp: Float) {
        edgeLightingOverlay.updateAmplitudes(micAmp, speakerAmp)
    }

    fun updateCaptions(caption: CaptionState) {
        edgeLightingOverlay.updateCaptionState(caption)
        overlayManager.updateCaption(caption.auraText.ifBlank { caption.userText })
    }

    suspend fun updateCallState(
        isCallActive: Boolean,
        connectionState: LiveConnectionState,
        isAuraActing: Boolean,
        isAuraThinking: Boolean,
        isMuted: Boolean,
        speakerAmp: Float
    ) {
        val edgeEnabled = preferences.edgeLightingEnabled.first()
        val miniOrbEnabled = preferences.overlayEnabled.first()
        val isAppInForeground = AuraApplication.isAppInForeground

        // Check/Request overlay permission via root if needed
        if ((edgeEnabled || miniOrbEnabled) && !Settings.canDrawOverlays(context)) {
            runCatching {
                com.topjohnwu.superuser.Shell.cmd("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow").exec()
            }
        }

        // 1. Manage Edge Lighting
        if (edgeEnabled && isCallActive) {
            val targetState = when {
                isMuted -> EdgeLightingState.MUTED
                connectionState is LiveConnectionState.Error -> EdgeLightingState.ERROR
                connectionState is LiveConnectionState.Connecting -> EdgeLightingState.CONNECTING
                connectionState is LiveConnectionState.Reconnecting -> EdgeLightingState.RECONNECTING
                connectionState is LiveConnectionState.Connected -> when {
                    isAuraActing -> EdgeLightingState.ACTING
                    speakerAmp > 0.05f -> EdgeLightingState.SPEAKING
                    isAuraThinking -> EdgeLightingState.THINKING
                    else -> EdgeLightingState.LISTENING
                }
                else -> EdgeLightingState.HIDDEN
            }
            edgeLightingOverlay.setState(targetState)
        } else {
            edgeLightingOverlay.setState(EdgeLightingState.HIDDEN)
        }

        // 2. Manage Floating Mini-Orb
        if (miniOrbEnabled && isCallActive && !isAppInForeground) {
            overlayManager.showOverlay()
        } else {
            overlayManager.hideOverlay()
        }
    }

    fun hideAll() {
        edgeLightingOverlay.hide()
        overlayManager.hideOverlay()
    }

    fun destroy() {
        controllerScope.cancel()
        hideAll()
    }
}
