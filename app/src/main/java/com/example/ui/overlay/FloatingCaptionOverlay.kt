package com.example.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.example.core.live.CaptionState
import kotlinx.coroutines.*

class FloatingCaptionOverlay(
    private val context: Context,
    private val onStopRequested: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "Aura"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: FrameLayout? = null
    private var containerLayout: LinearLayout? = null
    private var userTextView: TextView? = null
    private var auraTextView: TextView? = null
    private var stopButton: ImageButton? = null

    private var isAttached = false
    private var isVisible = false
    private val density = context.resources.displayMetrics.density

    private var autoHideJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var currentCaption = CaptionState()

    fun show(): Boolean {
        if (isAttached) {
            fadeIn()
            return true
        }
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Cannot show FloatingCaptionOverlay: SYSTEM_ALERT_WINDOW not granted")
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
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

            val screenW = context.resources.displayMetrics.widthPixels
            val maxW = (screenW * 0.90f).toInt().coerceAtMost((480 * density).toInt())

            val params = WindowManager.LayoutParams(
                maxW,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = (48 * density).toInt()
            }

            createView(maxW)
            windowManager.addView(rootView, params)
            isAttached = true
            isVisible = true
            rootView?.alpha = 0f
            fadeIn()
            Log.i(TAG, "FloatingCaptionOverlay attached successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed attaching FloatingCaptionOverlay: ${e.message}", e)
            return false
        }
    }

    private fun createView(maxW: Int) {
        val root = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val backgroundDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f * density
            setColor(Color.argb(230, 18, 18, 26)) // #E612121A
            setStroke((1f * density).toInt(), Color.argb(60, 255, 255, 255))
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = backgroundDrawable
            setPadding(
                (16 * density).toInt(),
                (12 * density).toInt(),
                (16 * density).toInt(),
                (12 * density).toInt()
            )
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Header row with "Aura Live" badge & Stop button
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (6 * density).toInt()
            }
        }

        val badgeText = TextView(context).apply {
            text = "● AURA LIVE"
            textSize = 10f
            setTextColor(Color.parseColor("#FFB347"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        headerRow.addView(badgeText)

        val btn = ImageButton(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.parseColor("#A0A5B9"))
            setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
            setOnClickListener {
                onStopRequested?.invoke()
                hideImmediate()
            }
        }
        stopButton = btn
        headerRow.addView(btn)
        container.addView(headerRow)

        // User speech line
        val userTv = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#A0A5B9"))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (4 * density).toInt()
            }
        }
        userTextView = userTv
        container.addView(userTv)

        // Aura response speech line
        val auraTv = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        auraTextView = auraTv
        container.addView(auraTv)

        root.addView(container)
        rootView = root
        containerLayout = container
    }

    fun updateCaptions(caption: CaptionState) {
        currentCaption = caption
        val userText = caption.userText.trim()
        val auraText = caption.auraText.trim()

        if (userText.isBlank() && auraText.isBlank()) {
            scheduleAutoHide(1000L)
            return
        }

        if (!isAttached) {
            show()
        } else if (!isVisible) {
            fadeIn()
        }

        userTextView?.apply {
            if (userText.isNotBlank()) {
                text = "You: $userText"
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }

        auraTextView?.apply {
            if (auraText.isNotBlank()) {
                text = "Aura: $auraText"
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }

        // Keep visible while talking, fade out ~3s after silence/completion
        if (!caption.isAuraSpeaking) {
            scheduleAutoHide(3200L)
        } else {
            autoHideJob?.cancel()
        }
    }

    private fun scheduleAutoHide(delayMs: Long) {
        autoHideJob?.cancel()
        autoHideJob = scope.launch {
            delay(delayMs)
            fadeOut()
        }
    }

    private fun fadeIn() {
        isVisible = true
        rootView?.let { view ->
            ValueAnimator.ofFloat(view.alpha, 1f).apply {
                duration = 150L
                addUpdateListener { view.alpha = it.animatedValue as Float }
                start()
            }
        }
    }

    private fun fadeOut() {
        isVisible = false
        rootView?.let { view ->
            ValueAnimator.ofFloat(view.alpha, 0f).apply {
                duration = 250L
                addUpdateListener {
                    val a = it.animatedValue as Float
                    view.alpha = a
                    if (a <= 0.01f) {
                        view.visibility = View.GONE
                    }
                }
                start()
            }
        }
    }

    fun hideImmediate() {
        isVisible = false
        autoHideJob?.cancel()
        rootView?.let { view ->
            ValueAnimator.ofFloat(view.alpha, 0f).apply {
                duration = 150L
                addUpdateListener {
                    val a = it.animatedValue as Float
                    view.alpha = a
                    if (a <= 0.01f) {
                        view.visibility = View.GONE
                    }
                }
                start()
            }
        }
    }

    fun destroy() {
        autoHideJob?.cancel()
        scope.cancel()
        if (isAttached && rootView != null) {
            try {
                windowManager.removeView(rootView)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing FloatingCaptionOverlay", e)
            } finally {
                rootView = null
                isAttached = false
                isVisible = false
            }
        }
    }
}
