package com.example.ui.overlay

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.example.AuraApplication
import com.example.core.lifecycle.AppForegroundState
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
    private val edgeLightingOverlay: EdgeLightingOverlay,
    private val floatingCaptionOverlay: FloatingCaptionOverlay = FloatingCaptionOverlay(context)
) {
    companion object {
        private const val TAG = "Aura"
    }

    private val controllerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Cached states
    private var isCallActive = false
    private var connectionState: LiveConnectionState = LiveConnectionState.Disconnected
    private var isAuraActing = false
    private var isAuraThinking = false
    private var isMuted = false
    private var speakerAmp = 0f
    private var latestCaption = CaptionState()

    fun startObserving() {
        controllerScope.launch {
            preferences.edgeLightingDepthPercent.collectLatest { depth ->
                edgeLightingOverlay.setDepthPercent(depth)
            }
        }
        controllerScope.launch {
            preferences.edgeLightingTheme.collectLatest { theme ->
                edgeLightingOverlay.setColorTheme(theme)
            }
        }
        controllerScope.launch {
            preferences.edgeLightingIntensity.collectLatest { inten ->
                edgeLightingOverlay.setIntensity(inten)
            }
        }
        controllerScope.launch {
            preferences.edgeLightingStyle.collectLatest { styleStr ->
                val style = if (styleStr.equals("SOFT_GRADIENT", ignoreCase = true)) {
                    EdgeStyle.SOFT_GRADIENT
                } else {
                    EdgeStyle.NEON_RAINBOW
                }
                edgeLightingOverlay.setStyle(style)
            }
        }
        controllerScope.launch {
            preferences.edgeLightingCometCount.collectLatest { count ->
                edgeLightingOverlay.setCometCount(count)
            }
        }
        controllerScope.launch {
            preferences.edgeLightingFlowSpeed.collectLatest { speed ->
                edgeLightingOverlay.setFlowSpeed(speed)
            }
        }
        controllerScope.launch {
            preferences.edgeLightingEnabled.collectLatest {
                reEvaluateOverlays(AppForegroundState.current)
            }
        }
        controllerScope.launch {
            preferences.edgeLightingShowInApp.collectLatest {
                reEvaluateOverlays(AppForegroundState.current)
            }
        }

        // Re-evaluate overlays whenever Foreground State changes!
        controllerScope.launch {
            AppForegroundState.isForeground.collectLatest { isForeground ->
                Log.d(TAG, "AppForegroundState changed: isForeground=$isForeground. Re-evaluating overlays...")
                reEvaluateOverlays(isForeground)
            }
        }
    }

    fun updateAmplitudes(micAmp: Float, speakerAmp: Float) {
        this.speakerAmp = speakerAmp
        edgeLightingOverlay.updateAmplitudes(micAmp, speakerAmp)
    }

    fun updateCaptions(caption: CaptionState) {
        latestCaption = caption
        val isForeground = AppForegroundState.current
        if (!isForeground && isCallActive && !AuraApplication.isSafeMode.value) {
            controllerScope.launch {
                val floatingCaptionsOn = preferences.floatingCaptionsEnabled.first()
                if (floatingCaptionsOn) {
                    floatingCaptionOverlay.updateCaptions(caption)
                }
            }
        } else {
            floatingCaptionOverlay.hideImmediate()
        }
    }

    suspend fun updateCallState(
        isCallActive: Boolean,
        connectionState: LiveConnectionState,
        isAuraActing: Boolean,
        isAuraThinking: Boolean,
        isMuted: Boolean,
        speakerAmp: Float
    ) {
        this.isCallActive = isCallActive
        this.connectionState = connectionState
        this.isAuraActing = isAuraActing
        this.isAuraThinking = isAuraThinking
        this.isMuted = isMuted
        this.speakerAmp = speakerAmp

        reEvaluateOverlays(AppForegroundState.current)
    }

    private suspend fun reEvaluateOverlays(isAppInForeground: Boolean) {
        // In Safe Mode, never display overlays
        if (AuraApplication.isSafeMode.value) {
            floatingCaptionOverlay.hideImmediate()
            overlayManager.hideOverlay()
            edgeLightingOverlay.setState(EdgeLightingState.HIDDEN)
            return
        }

        val edgeEnabled = preferences.edgeLightingEnabled.first()
        val showEdgeInApp = preferences.edgeLightingShowInApp.first()
        val miniOrbEnabled = preferences.overlayEnabled.first()
        val floatingCaptionsOn = preferences.floatingCaptionsEnabled.first()

        // Auto-grant overlay permission via root if needed
        if ((edgeEnabled || miniOrbEnabled || floatingCaptionsOn) && !Settings.canDrawOverlays(context)) {
            runCatching {
                com.topjohnwu.superuser.Shell.cmd("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow").exec()
            }
        }

        // 1. Manage Floating Captions & Mini Orb:
        // Inside Aura app: NO floating captions and NO mini orb
        // Outside Aura app: appear immediately and disappear when returning to app
        if (isAppInForeground || !isCallActive) {
            floatingCaptionOverlay.hideImmediate()
            overlayManager.hideOverlay()
        } else {
            if (floatingCaptionsOn && (latestCaption.auraText.isNotBlank() || latestCaption.userText.isNotBlank())) {
                floatingCaptionOverlay.updateCaptions(latestCaption)
            }
            if (miniOrbEnabled) {
                overlayManager.showOverlay()
            } else {
                overlayManager.hideOverlay()
            }
        }

        // 2. Manage Edge Lighting
        val shouldShowEdge = edgeEnabled && isCallActive && (!isAppInForeground || showEdgeInApp)
        if (shouldShowEdge) {
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
    }

    fun hideAll() {
        edgeLightingOverlay.hide()
        overlayManager.hideOverlay()
        floatingCaptionOverlay.hideImmediate()
    }

    fun destroy() {
        controllerScope.cancel()
        hideAll()
        floatingCaptionOverlay.destroy()
    }
}
