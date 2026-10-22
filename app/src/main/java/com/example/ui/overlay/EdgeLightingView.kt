package com.example.ui.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Build
import android.view.View
import android.view.animation.LinearInterpolator
import com.example.core.live.CaptionState

enum class EdgeLightingState {
    HIDDEN,
    WAKE_LISTENING,
    CONNECTING,
    RECONNECTING,
    LISTENING,
    THINKING,
    SPEAKING,
    ACTING,
    ERROR,
    MUTED
}

class EdgeLightingView(context: Context) : View(context) {

    private var lightingState = EdgeLightingState.HIDDEN
    private var micAmplitude = 0f
    private var speakerAmplitude = 0f

    var strokeThicknessDp: Float = 8f
        set(value) {
            field = value.coerceIn(4f, 16f)
            updatePaintStroke()
        }

    var intensity: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.2f, 2.0f)
            invalidate()
        }

    var showCaptions: Boolean = true
    private var currentCaptionState = CaptionState()

    private val density = context.resources.displayMetrics.density
    private var cornerRadiusPx = 40f * density

    private val clipPath = Path()
    private val borderPath = Path()

    // Paints
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val captionBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 18, 18, 26)
        style = Paint.Style.FILL
    }

    private val captionBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    private val textPaintYou = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 160, 165, 185)
        textSize = 13f * density
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private val textPaintAura = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 255, 179, 71) // Accent amber
        textSize = 14f * density
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private var flowPhase = 0f
    private var phaseAnimator: ValueAnimator? = null
    private var globalAlpha = 0f
    private var alphaAnimator: ValueAnimator? = null

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        updatePaintStroke()
        startPhaseAnimation()
    }

    private fun updatePaintStroke() {
        val strokePx = strokeThicknessDp * density
        corePaint.strokeWidth = strokePx
        glowPaint.strokeWidth = strokePx * 2.2f
        glowPaint.maskFilter = BlurMaskFilter(strokePx * 1.5f, BlurMaskFilter.Blur.NORMAL)
    }

    private fun startPhaseAnimation() {
        phaseAnimator?.cancel()
        phaseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                flowPhase = it.animatedValue as Float
                if (lightingState != EdgeLightingState.HIDDEN) {
                    invalidate()
                }
            }
            start()
        }
    }

    fun setState(state: EdgeLightingState) {
        if (lightingState == state) return
        val wasHidden = lightingState == EdgeLightingState.HIDDEN
        lightingState = state

        if (state == EdgeLightingState.HIDDEN) {
            animateAlphaTo(0f, 400L)
        } else {
            if (wasHidden) {
                animateAlphaTo(1f, 250L)
            }
            invalidate()
        }
    }

    fun updateAmplitudes(mic: Float, speaker: Float) {
        micAmplitude = mic
        speakerAmplitude = speaker
        if (lightingState == EdgeLightingState.LISTENING || lightingState == EdgeLightingState.SPEAKING) {
            invalidate()
        }
    }

    fun updateCaptionState(caption: CaptionState) {
        currentCaptionState = caption
        if (showCaptions && lightingState != EdgeLightingState.HIDDEN) {
            invalidate()
        }
    }

    private fun animateAlphaTo(target: Float, durationMs: Long) {
        alphaAnimator?.cancel()
        alphaAnimator = ValueAnimator.ofFloat(globalAlpha, target).apply {
            duration = durationMs
            addUpdateListener {
                globalAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    @SuppressLint("DrawAllocation")
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        // Rounded corners discovery on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val insets = rootWindowInsets
            val corner = insets?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)
            if (corner != null && corner.radius > 0) {
                cornerRadiusPx = corner.radius.toFloat()
            }
        }

        val halfStroke = (strokeThicknessDp * density) / 2f
        borderPath.reset()
        val rectF = RectF(halfStroke, halfStroke, w - halfStroke, h - halfStroke)
        borderPath.addRoundRect(rectF, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (globalAlpha <= 0.01f || lightingState == EdgeLightingState.HIDDEN) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // Select color spectrum based on state
        val (colors, speedMult) = when (lightingState) {
            EdgeLightingState.CONNECTING, EdgeLightingState.RECONNECTING -> Pair(
                intArrayOf(Color.parseColor("#FFB347"), Color.parseColor("#4A90E2"), Color.parseColor("#FFB347")),
                0.7f
            )
            EdgeLightingState.LISTENING -> {
                val blueViolet = Color.parseColor("#7F5AF0")
                val cyan = Color.parseColor("#2CB67D")
                Pair(intArrayOf(blueViolet, cyan, blueViolet), 1.0f + micAmplitude * 2f)
            }
            EdgeLightingState.THINKING -> Pair(
                intArrayOf(Color.parseColor("#2CB67D"), Color.parseColor("#7F5AF0"), Color.parseColor("#FF8E53")),
                1.8f
            )
            EdgeLightingState.SPEAKING -> {
                val amber = Color.parseColor("#FFB347")
                val coral = Color.parseColor("#FF6B6B")
                Pair(intArrayOf(amber, coral, amber), 1.2f + speakerAmplitude * 2f)
            }
            EdgeLightingState.ACTING -> Pair(
                intArrayOf(Color.parseColor("#FFD166"), Color.parseColor("#EF476F"), Color.parseColor("#06D6A0")),
                2.5f
            )
            EdgeLightingState.ERROR -> Pair(
                intArrayOf(Color.parseColor("#FF3B30"), Color.parseColor("#D90429"), Color.parseColor("#FF3B30")),
                0.5f
            )
            EdgeLightingState.MUTED -> Pair(
                intArrayOf(Color.parseColor("#E0A96D"), Color.parseColor("#776B5D"), Color.parseColor("#E0A96D")),
                0.5f
            )
            else -> Pair(
                intArrayOf(Color.parseColor("#FFB347"), Color.parseColor("#FF6B6B"), Color.parseColor("#7F5AF0")),
                1.0f
            )
        }

        val dynamicAlpha = ((globalAlpha * intensity).coerceIn(0.2f, 1f) * 255).toInt()
        val sweepGradient = SweepGradient(w / 2f, h / 2f, colors, null)
        val matrix = Matrix()
        matrix.postRotate((flowPhase * 360f * speedMult) % 360f, w / 2f, h / 2f)
        sweepGradient.setLocalMatrix(matrix)

        glowPaint.shader = sweepGradient
        glowPaint.alpha = (dynamicAlpha * 0.65f).toInt()
        canvas.drawPath(borderPath, glowPaint)

        corePaint.shader = sweepGradient
        corePaint.alpha = dynamicAlpha
        canvas.drawPath(borderPath, corePaint)

        // Draw Caption Pill at bottom if enabled and text exists
        if (showCaptions && (currentCaptionState.auraText.isNotBlank() || currentCaptionState.userText.isNotBlank())) {
            drawBottomCaptionPill(canvas, w, h)
        }
    }

    private fun drawBottomCaptionPill(canvas: Canvas, w: Float, h: Float) {
        val pillMargin = 24f * density
        val pillMaxW = (w - (pillMargin * 2)).coerceAtMost(340f * density)
        val pillLeft = (w - pillMaxW) / 2f
        val pillBottom = h - (48f * density)
        val pillHeight = 56f * density
        val pillTop = pillBottom - pillHeight
        val pillRect = RectF(pillLeft, pillTop, pillLeft + pillMaxW, pillBottom)

        canvas.drawRoundRect(pillRect, 16f * density, 16f * density, captionBgPaint)
        canvas.drawRoundRect(pillRect, 16f * density, 16f * density, captionBorderPaint)

        val textPadX = pillLeft + (14f * density)
        var textY = pillTop + (20f * density)

        if (currentCaptionState.userText.isNotBlank()) {
            val userDisplay = "You: " + currentCaptionState.userText.take(45)
            canvas.drawText(userDisplay, textPadX, textY, textPaintYou)
            textY += 18f * density
        }

        if (currentCaptionState.auraText.isNotBlank()) {
            val auraDisplay = "Aura: " + currentCaptionState.auraText.take(45)
            canvas.drawText(auraDisplay, textPadX, textY, textPaintAura)
        }
    }

    fun release() {
        phaseAnimator?.cancel()
        phaseAnimator = null
        alphaAnimator?.cancel()
        alphaAnimator = null
    }
}
