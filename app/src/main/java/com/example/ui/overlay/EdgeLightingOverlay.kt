package com.example.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.WindowManager

/**
 * Manages the original screen-edge lighting overlay window.
 */
class EdgeLightingOverlay(private val context: Context) {

    companion object {
        private const val TAG = "Aura"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var edgeView: EdgeLightingView? = null
    private var isOverlayAttached = false

    private var currentIntensity = 1.0f
    private var currentThicknessDp = 8f

    fun show(): Boolean {
        var result = false
        OverlayGuard.run("edge_lighting", context) {
            if (isOverlayAttached) {
                result = true
                return@run
            }
            if (!Settings.canDrawOverlays(context)) {
                Log.w(TAG, "Cannot show EdgeLightingOverlay: SYSTEM_ALERT_WINDOW not granted")
                result = false
                return@run
            }

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
                intensity = currentIntensity
                strokeThicknessDp = currentThicknessDp
            }
            windowManager.addView(view, params)
            edgeView = view
            isOverlayAttached = true
            Log.i(TAG, "EdgeLightingOverlay attached to windowManager successfully")
            result = true
        }
        return result
    }

    fun setState(state: EdgeLightingState) {
        OverlayGuard.run("edge_lighting", context) {
            if (!isOverlayAttached && state != EdgeLightingState.HIDDEN) {
                show()
            }
            edgeView?.setState(state)
        }
    }

    fun updateAmplitudes(mic: Float, speaker: Float) {
        OverlayGuard.run("edge_lighting", context) {
            edgeView?.updateAmplitudes(mic, speaker)
        }
    }

    fun setIntensity(intensity: Float) {
        OverlayGuard.run("edge_lighting", context) {
            currentIntensity = intensity
            edgeView?.intensity = intensity
        }
    }

    fun setThicknessDp(thicknessDp: Float) {
        OverlayGuard.run("edge_lighting", context) {
            currentThicknessDp = thicknessDp
            edgeView?.strokeThicknessDp = thicknessDp
        }
    }

    fun hide() {
        setState(EdgeLightingState.HIDDEN)
    }

    fun destroy() {
        OverlayGuard.run("edge_lighting", context) {
            if (isOverlayAttached && edgeView != null) {
                try {
                    edgeView?.release()
                    windowManager.removeView(edgeView)
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing EdgeLightingOverlay", e)
                } finally {
                    edgeView = null
                    isOverlayAttached = false
                }
            }
        }
    }
}
