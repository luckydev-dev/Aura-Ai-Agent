package com.example.ui.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.*

enum class EdgeStyle {
    NEON_RAINBOW,
    SOFT_GRADIENT
}

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

    var style: EdgeStyle = EdgeStyle.NEON_RAINBOW
        set(value) {
            field = value
            invalidate()
        }

    var cometCount: Int = 1
        set(value) {
            field = value.coerceIn(1, 2)
            invalidate()
        }

    var flowSpeedMultiplier: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.2f, 3.0f)
            invalidate()
        }

    var depthPercent: Int = 7 // Default ~7% of shorter side
        set(value) {
            field = value.coerceIn(2, 30)
            invalidate()
        }

    var intensity: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.2f, 2.0f)
            invalidate()
        }

    var colorTheme: String = "Aura" // "Aura", "Ocean", "Sunset", "Mono"
        set(value) {
            field = value
            invalidate()
        }

    private val density = context.resources.displayMetrics.density
    private var cornerRadiusPx = 36f * density
    private var coreWidthPx = 3.5f * density

    // Animation & Breathing
    private var timeSeconds = 0f
    private var phaseAnimator: ValueAnimator? = null
    private var globalAlpha = 0f
    private var alphaAnimator: ValueAnimator? = null

    // Target and smoothly interpolated state dynamics
    private var targetLevel = 0f
    private var currentLevel = 0f
    private var currentFlowSpeed = 1.0f
    private var targetFlowSpeed = 1.0f

    // AGSL RuntimeShader for API 33+
    private var agslShader: Any? = null
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Fallback Canvas structures for API 26-32
    private val borderPath = Path()
    private val glowPath = Path()
    private val fallbackBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = Paint.Style.STROKE
    }
    private val fallbackGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = Paint.Style.STROKE
    }
    private val cometPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val matrix = Matrix()
    private var sweepGradient: SweepGradient? = null

    private val isReduceMotionEnabled: Boolean
        get() {
            return try {
                val scale = Settings.Global.getFloat(
                    context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1.0f
                )
                scale == 0f
            } catch (e: Exception) {
                false
            }
        }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        initShaderIfSupported()
        startPhaseAnimation()
    }

    private fun initShaderIfSupported() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val shaderCode = """
                    uniform float2 resolution;
                    uniform float time;
                    uniform float coreWidth;
                    uniform float glowDepth;
                    uniform float intensity;
                    uniform float level;
                    uniform float cornerRadius;
                    uniform float cometCount;
                    uniform float flowSpeed;
                    uniform int style;
                    uniform half4 c1;
                    uniform half4 c2;
                    uniform half4 c3;

                    float sdRoundRect(float2 p, float2 hs, float r) {
                        float2 q = abs(p) - hs + r;
                        return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
                    }

                    half3 rainbowCol(float t) {
                        float k = fract(t) * 6.0;
                        if (k < 1.0) return mix(half3(0.0, 0.95, 1.0), half3(0.2, 0.35, 1.0), k);
                        if (k < 2.0) return mix(half3(0.2, 0.35, 1.0), half3(0.85, 0.1, 0.95), k - 1.0);
                        if (k < 3.0) return mix(half3(0.85, 0.1, 0.95), half3(1.0, 0.2, 0.2), k - 2.0);
                        if (k < 4.0) return mix(half3(1.0, 0.2, 0.2), half3(1.0, 0.85, 0.1), k - 3.0);
                        if (k < 5.0) return mix(half3(1.0, 0.85, 0.1), half3(0.1, 0.95, 0.4), k - 4.0);
                        return mix(half3(0.1, 0.95, 0.4), half3(0.0, 0.95, 1.0), k - 5.0);
                    }

                    half4 main(float2 fc) {
                        float2 center = resolution * 0.5;
                        float2 p = fc - center;
                        float d = max(-sdRoundRect(p, center, cornerRadius), 0.0);

                        if (style == 1) {
                            float reach = glowDepth * (0.7 + 0.9 * level);
                            if (reach <= 0.0) reach = 1.0;
                            float f = exp(-d / reach) * smoothstep(reach * 3.0, 0.0, d);
                            float t = fract(atan(p.y, p.x) / 6.2831853 + time * 0.08);
                            float k = t * 3.0;
                            half3 col = k < 1.0 ? mix(c1.rgb, c2.rgb, k) : (k < 2.0 ? mix(c2.rgb, c3.rgb, k - 1.0) : mix(c3.rgb, c1.rgb, k - 2.0));
                            float a = clamp(f * intensity, 0.0, 1.0);
                            return half4(col * a, a);
                        }

                        float normAngle = fract(atan(p.y, p.x) / 6.2831853 + 0.5);
                        float angleFlow = fract(normAngle + time * flowSpeed * 0.12);
                        half3 baseColor = rainbowCol(angleFlow);

                        float dynamicDepth = glowDepth * (1.0 + 0.65 * level);
                        float core = smoothstep(coreWidth, 0.0, d);
                        float glow = exp(-d / max(dynamicDepth, 1.0));

                        float alpha = clamp((core * 1.25 + glow * 0.85) * intensity, 0.0, 1.0);
                        return half4(baseColor * alpha, alpha);
                    }
                """.trimIndent()
                agslShader = android.graphics.RuntimeShader(shaderCode)
                shaderPaint.shader = agslShader as android.graphics.Shader
                Log.d("Aura", "AGSL Neon Rainbow RuntimeShader compiled successfully")
            } catch (e: Exception) {
                Log.e("Aura", "Failed compiling AGSL RuntimeShader: ${e.message}", e)
                agslShader = null
            }
        }
    }

    private fun startPhaseAnimation() {
        phaseAnimator?.cancel()
        phaseAnimator = ValueAnimator.ofFloat(0f, 1000f).apply {
            duration = 1000_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (!isReduceMotionEnabled) {
                    timeSeconds += 0.016f * currentFlowSpeed
                }
                currentLevel += (targetLevel - currentLevel) * 0.15f
                currentFlowSpeed += (targetFlowSpeed - currentFlowSpeed) * 0.10f

                if (lightingState != EdgeLightingState.HIDDEN && globalAlpha > 0.01f) {
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

        // Configure state speeds and dynamics
        when (state) {
            EdgeLightingState.HIDDEN -> {
                animateAlphaTo(0f, 400L)
            }
            EdgeLightingState.SPEAKING -> {
                targetFlowSpeed = 1.6f * flowSpeedMultiplier
                if (wasHidden) animateAlphaTo(1f, 250L)
            }
            EdgeLightingState.THINKING -> {
                targetFlowSpeed = 1.8f * flowSpeedMultiplier
                if (wasHidden) animateAlphaTo(1f, 250L)
            }
            EdgeLightingState.ACTING -> {
                targetFlowSpeed = 2.2f * flowSpeedMultiplier
                if (wasHidden) animateAlphaTo(1f, 250L)
            }
            EdgeLightingState.CONNECTING, EdgeLightingState.RECONNECTING -> {
                targetFlowSpeed = 0.5f * flowSpeedMultiplier
                if (wasHidden) animateAlphaTo(0.6f, 300L)
            }
            EdgeLightingState.MUTED -> {
                targetFlowSpeed = 0.3f * flowSpeedMultiplier
                if (wasHidden) animateAlphaTo(0.4f, 300L)
            }
            EdgeLightingState.ERROR -> {
                targetFlowSpeed = 0.8f
                animateAlphaTo(1f, 150L)
            }
            else -> { // LISTENING / WAKE_LISTENING
                targetFlowSpeed = 1.0f * flowSpeedMultiplier
                if (wasHidden) animateAlphaTo(1f, 250L)
            }
        }
        invalidate()
    }

    fun updateAmplitudes(mic: Float, speaker: Float) {
        micAmplitude = mic
        speakerAmplitude = speaker

        targetLevel = when (lightingState) {
            EdgeLightingState.LISTENING -> (mic * 1.5f).coerceIn(0f, 1.2f)
            EdgeLightingState.SPEAKING -> (speaker * 1.5f).coerceIn(0f, 1.2f)
            EdgeLightingState.ACTING -> 0.7f
            EdgeLightingState.THINKING -> 0.4f
            else -> 0.05f
        }

        if (lightingState == EdgeLightingState.LISTENING || lightingState == EdgeLightingState.SPEAKING) {
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val insets = rootWindowInsets
            val corner = insets?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)
            if (corner != null && corner.radius > 0) {
                cornerRadiusPx = corner.radius.toFloat()
            }
        }

        // Setup fallback paths
        borderPath.reset()
        val rect = RectF(coreWidthPx / 2f, coreWidthPx / 2f, w - coreWidthPx / 2f, h - coreWidthPx / 2f)
        borderPath.addRoundRect(rect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)

        fallbackBorderPaint.strokeWidth = coreWidthPx
        val glowWidth = min(w, h) * (depthPercent / 100f)
        fallbackGlowPaint.strokeWidth = glowWidth
        fallbackGlowPaint.maskFilter = BlurMaskFilter(glowWidth * 0.5f, BlurMaskFilter.Blur.NORMAL)
        cometPaint.strokeWidth = coreWidthPx * 1.8f

        val rainbowColors = intArrayOf(
            Color.parseColor("#00F0FF"), // Cyan
            Color.parseColor("#3355FF"), // Blue
            Color.parseColor("#D91AF2"), // Magenta
            Color.parseColor("#FF3333"), // Red
            Color.parseColor("#FFD700"), // Yellow
            Color.parseColor("#1AF266"), // Green
            Color.parseColor("#00F0FF")  // Loop to Cyan
        )
        sweepGradient = SweepGradient(w / 2f, h / 2f, rainbowColors, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (globalAlpha <= 0.01f || lightingState == EdgeLightingState.HIDDEN) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val shorterSide = minOf(w, h)
        val depthPx = shorterSide * (depthPercent / 100f)
        val dynamicIntensity = (globalAlpha * intensity).coerceIn(0f, 1.8f)

        val (c1, c2, c3) = getPaletteColors()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && agslShader != null) {
                drawWithAgsl(canvas, w, h, depthPx, dynamicIntensity, c1, c2, c3)
            } else {
                drawWithCanvasFallback(canvas, w, h, depthPx, dynamicIntensity)
            }
        } catch (t: Throwable) {
            Log.e("Aura", "Error in EdgeLightingView onDraw: ${t.message}", t)
        }
    }

    private fun getPaletteColors(): Triple<Color, Color, Color> {
        return when (lightingState) {
            EdgeLightingState.CONNECTING, EdgeLightingState.RECONNECTING -> Triple(
                Color.valueOf(1.0f, 0.70f, 0.28f, 1.0f),
                Color.valueOf(0.29f, 0.56f, 0.89f, 1.0f),
                Color.valueOf(1.0f, 0.70f, 0.28f, 1.0f)
            )
            EdgeLightingState.THINKING -> Triple(
                Color.valueOf(0.17f, 0.71f, 0.49f, 1.0f),
                Color.valueOf(0.50f, 0.35f, 0.94f, 1.0f),
                Color.valueOf(0.20f, 0.75f, 1.0f, 1.0f)
            )
            EdgeLightingState.SPEAKING -> Triple(
                Color.valueOf(1.0f, 0.70f, 0.28f, 1.0f),
                Color.valueOf(1.0f, 0.42f, 0.42f, 1.0f),
                Color.valueOf(1.0f, 0.85f, 0.10f, 1.0f)
            )
            EdgeLightingState.ACTING -> Triple(
                Color.valueOf(1.0f, 0.82f, 0.40f, 1.0f),
                Color.valueOf(0.94f, 0.28f, 0.44f, 1.0f),
                Color.valueOf(0.02f, 0.84f, 0.63f, 1.0f)
            )
            EdgeLightingState.ERROR -> Triple(
                Color.valueOf(1.0f, 0.23f, 0.19f, 1.0f),
                Color.valueOf(0.85f, 0.02f, 0.16f, 1.0f),
                Color.valueOf(1.0f, 0.23f, 0.19f, 1.0f)
            )
            EdgeLightingState.MUTED -> Triple(
                Color.valueOf(0.60f, 0.60f, 0.60f, 0.5f),
                Color.valueOf(0.40f, 0.40f, 0.40f, 0.5f),
                Color.valueOf(0.60f, 0.60f, 0.60f, 0.5f)
            )
            else -> Triple(
                Color.valueOf(0.0f, 0.95f, 1.0f, 1.0f),
                Color.valueOf(0.85f, 0.1f, 0.95f, 1.0f),
                Color.valueOf(1.0f, 0.2f, 0.2f, 1.0f)
            )
        }
    }

    private fun drawWithAgsl(
        canvas: Canvas,
        w: Float,
        h: Float,
        depthPx: Float,
        dynamicIntensity: Float,
        c1: Color,
        c2: Color,
        c3: Color
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val shader = agslShader as? android.graphics.RuntimeShader ?: return
                shader.setFloatUniform("resolution", w, h)
                shader.setFloatUniform("time", timeSeconds)
                shader.setFloatUniform("coreWidth", coreWidthPx)
                shader.setFloatUniform("glowDepth", depthPx)
                shader.setFloatUniform("intensity", dynamicIntensity)
                shader.setFloatUniform("level", currentLevel)
                shader.setFloatUniform("cornerRadius", cornerRadiusPx)
                shader.setFloatUniform("cometCount", cometCount.toFloat())
                shader.setFloatUniform("flowSpeed", currentFlowSpeed)
                shader.setIntUniform("style", if (style == EdgeStyle.NEON_RAINBOW) 0 else 1)

                shader.setColorUniform("c1", c1.toArgb())
                shader.setColorUniform("c2", c2.toArgb())
                shader.setColorUniform("c3", c3.toArgb())

                canvas.drawRect(0f, 0f, w, h, shaderPaint)
            } catch (t: Throwable) {
                drawWithCanvasFallback(canvas, w, h, depthPx, dynamicIntensity)
            }
        }
    }

    private fun drawWithCanvasFallback(
        canvas: Canvas,
        w: Float,
        h: Float,
        depthPx: Float,
        dynamicIntensity: Float
    ) {
        val sweep = sweepGradient ?: return
        val rotationDeg = (timeSeconds * currentFlowSpeed * 45f) % 360f
        matrix.setRotate(rotationDeg, w / 2f, h / 2f)
        sweep.setLocalMatrix(matrix)

        val alphaInt = (dynamicIntensity.coerceIn(0f, 1f) * 255).toInt()
        fallbackGlowPaint.shader = sweep
        fallbackGlowPaint.alpha = (alphaInt * 0.7f).toInt()
        canvas.drawPath(borderPath, fallbackGlowPaint)

        fallbackBorderPaint.shader = sweep
        fallbackBorderPaint.alpha = alphaInt
        canvas.drawPath(borderPath, fallbackBorderPaint)
    }

    fun release() {
        phaseAnimator?.cancel()
        phaseAnimator = null
        alphaAnimator?.cancel()
        alphaAnimator = null
    }
}
