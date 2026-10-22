package com.example.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.WindowManager

class EdgeLightingOverlay(private val context: Context) {

    companion object {
        private const val TAG = "Aura"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var edgeView: EdgeLightingView? = null
    private var isOverlayAttached = false

    private var currentDepthPercent = 22
    private var currentIntensity = 1.0f
    private var currentColorTheme = "Aura"

    fun show(): Boolean {
        if (isOverlayAttached) return true
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Cannot show EdgeLightingOverlay: SYSTEM_ALERT_WINDOW not granted")
            return false
        }

        try {
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                flags,
                PixelFormat.TRANSLUCENT
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            val view = EdgeLightingView(context).apply {
                depthPercent = currentDepthPercent
                intensity = currentIntensity
                colorTheme = currentColorTheme
            }
            windowManager.addView(view, params)
            edgeView = view
            isOverlayAttached = true
            Log.i(TAG, "EdgeLightingOverlay attached to windowManager successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach EdgeLightingOverlay: ${e.message}", e)
            return false
        }
    }

    fun setState(state: EdgeLightingState) {
        if (!isOverlayAttached && state != EdgeLightingState.HIDDEN) {
            show()
        }
        edgeView?.setState(state)
    }

    fun updateAmplitudes(mic: Float, speaker: Float) {
        edgeView?.updateAmplitudes(mic, speaker)
    }

    fun setDepthPercent(depth: Int) {
        currentDepthPercent = depth
        edgeView?.depthPercent = depth
    }

    fun setIntensity(intensity: Float) {
        currentIntensity = intensity
        edgeView?.intensity = intensity
    }

    fun setColorTheme(theme: String) {
        currentColorTheme = theme
        edgeView?.colorTheme = theme
    }

    fun hide() {
        if (!isOverlayAttached || edgeView == null) return
        try {
            edgeView?.setState(EdgeLightingState.HIDDEN)
            windowManager.removeView(edgeView)
            edgeView?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error detaching EdgeLightingOverlay: ${e.message}", e)
        } finally {
            edgeView = null
            isOverlayAttached = false
            Log.i(TAG, "EdgeLightingOverlay detached")
        }
    }
}
