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

    var depthPercent: Int = 22 // 8% to 40%
        set(value) {
            field = value.coerceIn(8, 40)
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
    private var cornerRadiusPx = 40f * density

    // Animation & Breathing
    private var timeSeconds = 0f
    private var phaseAnimator: ValueAnimator? = null
    private var globalAlpha = 0f
    private var alphaAnimator: ValueAnimator? = null

    // Target and interpolated levels
    private var targetLevel = 0f
    private var currentLevel = 0f

    // AGSL RuntimeShader for API 33+
    private var agslShader: Any? = null // RuntimeShader on API 33+
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Fallback paints and paths for API 26-32
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

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
                    uniform float depth;
                    uniform float intensity;
                    uniform float level;
                    uniform float cornerRadius;
                    uniform half4 c1;
                    uniform half4 c2;
                    uniform half4 c3;

                    float sdRoundRect(float2 p, float2 hs, float r) {
                        float2 q = abs(p) - hs + r;
                        return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
                    }

                    half4 main(float2 fc) {
                        float2 c = resolution * 0.5;
                        float2 p = fc - c;
                        float d = max(-sdRoundRect(p, c, cornerRadius), 0.0);
                        float reach = depth * (0.7 + 0.9 * level);
                        if (reach <= 0.0) reach = 1.0;
                        float f = exp(-d / reach) * smoothstep(reach * 3.0, 0.0, d);
                        float t = fract(atan(p.y, p.x) / 6.2831853 + time * 0.08);
                        float k = t * 3.0;
                        half3 col = k < 1.0 ? mix(c1.rgb, c2.rgb, k) : (k < 2.0 ? mix(c2.rgb, c3.rgb, k - 1.0) : mix(c3.rgb, c1.rgb, k - 2.0));
                        float a = clamp(f * intensity * (0.88 + 0.12 * sin(time * 2.0 + t * 12.566)), 0.0, 1.0);
                        return half4(col * a, a);
                    }
                """.trimIndent()
                agslShader = android.graphics.RuntimeShader(shaderCode)
                shaderPaint.shader = agslShader as android.graphics.Shader
            } catch (e: Exception) {
                android.util.Log.e("Aura", "Failed compiling AGSL RuntimeShader: ${e.message}", e)
                agslShader = null
            }
        }
    }

    private fun startPhaseAnimation() {
        phaseAnimator?.cancel()
        phaseAnimator = ValueAnimator.ofFloat(0f, 1000f).apply {
            duration = 1000_000L // Long running time counter
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (!isReduceMotionEnabled) {
                    timeSeconds += 0.016f // ~60fps step
                }
                // Smoothly interpolate currentLevel toward targetLevel
                currentLevel += (targetLevel - currentLevel) * 0.18f

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

        when (state) {
            EdgeLightingState.HIDDEN -> {
                animateAlphaTo(0f, 400L)
            }
            else -> {
                if (wasHidden) {
                    animateAlphaTo(1f, 250L)
                }
                invalidate()
            }
        }
    }

    fun updateAmplitudes(mic: Float, speaker: Float) {
        micAmplitude = mic
        speakerAmplitude = speaker

        targetLevel = when (lightingState) {
            EdgeLightingState.LISTENING -> mic.coerceIn(0f, 1f)
            EdgeLightingState.SPEAKING -> speaker.coerceIn(0f, 1f)
            EdgeLightingState.ACTING -> 0.7f
            EdgeLightingState.THINKING -> 0.4f
            else -> 0.1f
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
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (globalAlpha <= 0.01f || lightingState == EdgeLightingState.HIDDEN) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val shorterSide = minOf(w, h)
        val depthPx = shorterSide * (depthPercent / 100f)
        val dynamicIntensity = (globalAlpha * intensity).coerceIn(0f, 1.5f)

        // Palette selection
        val (c1, c2, c3) = getPaletteColors()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && agslShader != null) {
                drawWithAgsl(canvas, w, h, depthPx, dynamicIntensity, c1, c2, c3)
            } else {
                drawWithCanvasFallback(canvas, w, h, depthPx, dynamicIntensity, c1, c2, c3)
            }
        } catch (t: Throwable) {
            Log.e("Aura", "Error in EdgeLightingView onDraw: ${t.message}", t)
        }
    }

    private fun getPaletteColors(): Triple<Color, Color, Color> {
        return when (lightingState) {
            EdgeLightingState.CONNECTING, EdgeLightingState.RECONNECTING -> Triple(
                Color.valueOf(1.0f, 0.70f, 0.28f, 1.0f), // Amber
                Color.valueOf(0.29f, 0.56f, 0.89f, 1.0f), // Blue
                Color.valueOf(1.0f, 0.70f, 0.28f, 1.0f)
            )
            EdgeLightingState.LISTENING -> when (colorTheme) {
                "Ocean" -> Triple(
                    Color.valueOf(0.0f, 0.75f, 1.0f, 1.0f),
                    Color.valueOf(0.17f, 0.71f, 0.49f, 1.0f),
                    Color.valueOf(0.12f, 0.53f, 0.90f, 1.0f)
                )
                "Sunset" -> Triple(
                    Color.valueOf(1.0f, 0.42f, 0.42f, 1.0f),
                    Color.valueOf(1.0f, 0.70f, 0.28f, 1.0f),
                    Color.valueOf(0.85f, 0.25f, 0.45f, 1.0f)
                )
                "Mono" -> Triple(
                    Color.valueOf(0.85f, 0.88f, 0.92f, 1.0f),
                    Color.valueOf(0.60f, 0.65f, 0.72f, 1.0f),
                    Color.valueOf(0.85f, 0.88f, 0.92f, 1.0f)
                )
                else -> Triple( // "Aura" default
                    Color.valueOf(0.50f, 0.35f, 0.94f, 1.0f), // Violet
                    Color.valueOf(0.17f, 0.71f, 0.49f, 1.0f), // Cyan
                    Color.valueOf(0.50f, 0.35f, 0.94f, 1.0f)
                )
            }
            EdgeLightingState.THINKING -> Triple(
                Color.valueOf(0.17f, 0.71f, 0.49f, 1.0f), // Emerald
                Color.valueOf(0.50f, 0.35f, 0.94f, 1.0f), // Violet
                Color.valueOf(1.0f, 0.56f, 0.33f, 1.0f)  // Coral
            )
            EdgeLightingState.SPEAKING -> Triple(
                Color.valueOf(1.0f, 0.70f, 0.28f, 1.0f), // Warm amber
                Color.valueOf(1.0f, 0.42f, 0.42f, 1.0f), // Coral
                Color.valueOf(1.0f, 0.70f, 0.28f, 1.0f)
            )
            EdgeLightingState.ACTING -> Triple(
                Color.valueOf(1.0f, 0.82f, 0.40f, 1.0f), // Gold
                Color.valueOf(0.94f, 0.28f, 0.44f, 1.0f), // Pink
                Color.valueOf(0.02f, 0.84f, 0.63f, 1.0f)  // Cyan
            )
            EdgeLightingState.ERROR -> Triple(
                Color.valueOf(1.0f, 0.23f, 0.19f, 1.0f), // Crimson
                Color.valueOf(0.85f, 0.02f, 0.16f, 1.0f),
                Color.valueOf(1.0f, 0.23f, 0.19f, 1.0f)
            )
            EdgeLightingState.MUTED -> Triple(
                Color.valueOf(0.88f, 0.66f, 0.43f, 0.6f),
                Color.valueOf(0.47f, 0.42f, 0.36f, 0.6f),
                Color.valueOf(0.88f, 0.66f, 0.43f, 0.6f)
            )
            else -> Triple(
                Color.valueOf(1.0f, 0.70f, 0.28f, 1.0f),
                Color.valueOf(1.0f, 0.42f, 0.42f, 1.0f),
                Color.valueOf(0.50f, 0.35f, 0.94f, 1.0f)
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
                shader.setFloatUniform("depth", depthPx)
                shader.setFloatUniform("intensity", dynamicIntensity)
                shader.setFloatUniform("level", currentLevel)
                shader.setFloatUniform("cornerRadius", cornerRadiusPx)

                shader.setColorUniform("c1", c1.toArgb())
                shader.setColorUniform("c2", c2.toArgb())
                shader.setColorUniform("c3", c3.toArgb())

                canvas.drawRect(0f, 0f, w, h, shaderPaint)
            } catch (t: Throwable) {
                // If shader execution or GPU driver fails, gracefully fall back to Canvas drawing
                drawWithCanvasFallback(canvas, w, h, depthPx, dynamicIntensity, c1, c2, c3)
            }
        }
    }

    private fun drawWithCanvasFallback(
        canvas: Canvas,
        w: Float,
        h: Float,
        depthPx: Float,
        dynamicIntensity: Float,
        c1: Color,
        c2: Color,
        c3: Color
    ) {
        // High quality fallback on API 26-32 using 4 directional edge gradient strips
        val alphaEdge = (dynamicIntensity.coerceIn(0f, 1f) * 230).toInt()
        val c1Int = Color.argb(alphaEdge, (c1.red() * 255).toInt(), (c1.green() * 255).toInt(), (c1.blue() * 255).toInt())
        val transparent = Color.TRANSPARENT

        // Top strip
        fallbackPaint.shader = LinearGradient(0f, 0f, 0f, depthPx, c1Int, transparent, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, depthPx, fallbackPaint)

        // Bottom strip
        fallbackPaint.shader = LinearGradient(0f, h, 0f, h - depthPx, c1Int, transparent, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, h - depthPx, w, h, fallbackPaint)

        // Left strip
        fallbackPaint.shader = LinearGradient(0f, 0f, depthPx, 0f, c1Int, transparent, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, depthPx, h, fallbackPaint)

        // Right strip
        fallbackPaint.shader = LinearGradient(w, 0f, w - depthPx, 0f, c1Int, transparent, Shader.TileMode.CLAMP)
        canvas.drawRect(w - depthPx, 0f, w, h, fallbackPaint)
    }

    fun release() {
        phaseAnimator?.cancel()
        phaseAnimator = null
        alphaAnimator?.cancel()
        alphaAnimator = null
    }
}
