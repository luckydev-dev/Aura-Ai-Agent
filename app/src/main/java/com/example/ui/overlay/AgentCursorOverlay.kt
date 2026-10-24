package com.example.ui.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import com.example.core.input.InputController
import com.example.core.input.InputEvent
import com.example.core.input.InputEventType
import kotlinx.coroutines.*
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class CursorStyle {
    ARROW,
    DOT,
    HAND
}

enum class CursorSpeed(val durationMultiplier: Float) {
    INSTANT(0f),
    FAST(0.6f),
    NORMAL(1.0f),
    SLOW(1.5f)
}

/**
 * High-performance agent mouse pointer overlay.
 * Uses an on-demand transient window architecture that attaches strictly during
 * cursor movements / taps and detaches when idle, guaranteeing ZERO touch blocking
 * or occlusion for the user.
 */
class AgentCursorOverlay(
    private val context: Context,
    private val inputController: InputController
) {
    companion object {
        private const val TAG = "Aura"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var cursorView: AgentCursorView? = null
    @Volatile
    private var isAttached = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    var isEnabled: Boolean = true
    var cursorStyle: CursorStyle = CursorStyle.ARROW
    var cursorSpeed: CursorSpeed = CursorSpeed.NORMAL
    var cursorSizeDp: Float = 26f
    var showTrail: Boolean = true
    var showRipple: Boolean = true
    var showActionLabel: Boolean = true
    var showDuringUnlock: Boolean = false

    init {
        // Synchronize with InputController events
        inputController.cursorListener = { event ->
            handleInputEvent(event)
        }
    }

    /**
     * Attaches the overlay to WindowManager strictly when needed.
     * Uses FLAG_NOT_TOUCHABLE and FLAG_NOT_FOCUSABLE so the window can never consume touch events.
     */
    @Synchronized
    private fun ensureAttached(): Boolean {
        if (isAttached && cursorView != null) return true
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Cannot attach AgentCursorOverlay: SYSTEM_ALERT_WINDOW not granted")
            return false
        }

        return try {
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
            ).apply {
                alpha = 1.0f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }

            val view = AgentCursorView(context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                onIdleDismissed = {
                    scope.launch(Dispatchers.Main) {
                        detachOverlay()
                    }
                }
            }

            windowManager.addView(view, params)
            cursorView = view
            isAttached = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach AgentCursorOverlay: ${e.message}")
            false
        }
    }

    /**
     * Completely removes the overlay from WindowManager so no occluding window sits on screen.
     */
    @Synchronized
    fun detachOverlay() {
        if (isAttached && cursorView != null) {
            try {
                windowManager.removeView(cursorView)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing AgentCursorOverlay: ${e.message}")
            } finally {
                cursorView = null
                isAttached = false
            }
        }
    }

    fun show(): Boolean {
        // Compatibility helper: attaches if enabled
        return if (isEnabled) ensureAttached() else false
    }

    fun hide() {
        detachOverlay()
    }

    private suspend fun handleInputEvent(event: InputEvent) {
        if (!isEnabled) return

        if (cursorSpeed == CursorSpeed.INSTANT) {
            withContext(Dispatchers.Main) {
                if (ensureAttached()) {
                    cursorView?.flashAt(event.startX, event.startY, event.label)
                }
            }
            return
        }

        withContext(Dispatchers.Main) {
            if (ensureAttached()) {
                cursorView?.let { v ->
                    v.cursorStyle = cursorStyle
                    v.cursorSizeDp = cursorSizeDp
                    v.showTrail = showTrail
                    v.showRipple = showRipple
                    v.showActionLabel = showActionLabel
                    v.executeAnimatedEvent(event, cursorSpeed.durationMultiplier)
                }
            }
        }
    }

    /**
     * Instantly plays a live cursor preview animation across the screen.
     * Used when the user selects a new style, speed, or size in Settings.
     */
    fun previewAnimation(
        style: CursorStyle? = null,
        speed: CursorSpeed? = null,
        sizeDp: Float? = null
    ) {
        if (!Settings.canDrawOverlays(context)) return

        val targetStyle = style ?: cursorStyle
        val targetSpeed = speed ?: cursorSpeed
        val targetSize = sizeDp ?: cursorSizeDp

        scope.launch(Dispatchers.Main) {
            if (!ensureAttached()) return@launch

            cursorView?.let { v ->
                v.cursorStyle = targetStyle
                v.cursorSizeDp = targetSize
                v.showTrail = showTrail
                v.showRipple = showRipple
                v.showActionLabel = showActionLabel

                val metrics = inputController.getDisplayMetrics()
                val cx = metrics.x * 0.5f
                val cy = metrics.y * 0.42f

                val previewEvent = InputEvent(
                    type = InputEventType.TAP,
                    startX = cx - 110f,
                    startY = cy - 70f,
                    endX = cx + 80f,
                    endY = cy + 40f,
                    durationMs = 90L,
                    label = when (targetStyle) {
                        CursorStyle.ARROW -> "Arrow Pointer"
                        CursorStyle.DOT -> "Touch Dot"
                        CursorStyle.HAND -> "Hand Pointer"
                    }
                )
                v.executeAnimatedEvent(previewEvent, targetSpeed.durationMultiplier)
            }
        }
    }

    fun testDemo() {
        previewAnimation()
    }

    fun destroy() {
        scope.cancel()
        detachOverlay()
    }
}

/**
 * Custom View that renders the mouse pointer, motion trails, tap ripples,
 * long-press progress rings, and action chips with hardware acceleration.
 */
class AgentCursorView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density
    var cursorStyle: CursorStyle = CursorStyle.ARROW
    var cursorSizeDp: Float = 26f
    var showTrail: Boolean = true
    var showRipple: Boolean = true
    var showActionLabel: Boolean = true

    var onIdleDismissed: (() -> Unit)? = null

    // Current pointer position & scale
    private var posX = 0f
    private var posY = 0f
    private var pointerScale = 1.0f
    private var pointerAlpha = 0f

    // Motion & Gestures
    private var rippleRadius = 0f
    private var rippleAlpha = 0f
    private var longPressProgress = 0f
    private var currentLabel: String = ""
    private var labelAlpha = 0f

    // Trail points for swipe / drag
    private val trailPoints = mutableListOf<PointF>()
    private val maxTrail = 20

    // Paints
    private val arrowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val arrowStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#12121A")
        style = Paint.Style.STROKE
        strokeWidth = 2.0f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val dotGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(6f * density, BlurMaskFilter.Blur.NORMAL)
    }

    private val dotInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val dotBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        maskFilter = BlurMaskFilter(4f * density, BlurMaskFilter.Blur.NORMAL)
    }

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }

    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val chipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 18, 20, 30)
        style = Paint.Style.FILL
    }

    private val chipBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 229, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * density
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private val arrowPath = Path()
    private val handPath = Path()
    private var currentMoveAnimator: ValueAnimator? = null
    private var idleFadeJob: Job? = null
    private val viewScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        val metrics = context.resources.displayMetrics
        posX = metrics.widthPixels / 2f
        posY = metrics.heightPixels / 2f
        buildArrowPath()
        buildHandPath()
    }

    private fun buildArrowPath() {
        val s = cursorSizeDp * density
        arrowPath.reset()
        arrowPath.moveTo(0f, 0f)
        arrowPath.lineTo(0f, s)
        arrowPath.lineTo(s * 0.28f, s * 0.75f)
        arrowPath.lineTo(s * 0.58f, s * 1.25f)
        arrowPath.lineTo(s * 0.82f, s * 1.10f)
        arrowPath.lineTo(s * 0.52f, s * 0.62f)
        arrowPath.lineTo(s * 0.88f, s * 0.62f)
        arrowPath.close()
    }

    private fun buildHandPath() {
        val s = cursorSizeDp * density * 0.9f
        handPath.reset()
        // Stylized pointing hand with index finger pointing at (0, 0)
        handPath.moveTo(0f, 0f)
        handPath.lineTo(s * 0.22f, 0f)
        handPath.lineTo(s * 0.22f, s * 0.55f)
        handPath.lineTo(s * 0.42f, s * 0.55f)
        handPath.lineTo(s * 0.42f, s * 0.95f)
        handPath.lineTo(s * 0.62f, s * 0.95f)
        handPath.lineTo(s * 0.62f, s * 1.30f)
        handPath.lineTo(-s * 0.15f, s * 1.30f)
        handPath.lineTo(-s * 0.35f, s * 0.80f)
        handPath.lineTo(-s * 0.15f, s * 0.50f)
        handPath.lineTo(0f, s * 0.50f)
        handPath.close()
    }

    fun flashAt(x: Float, y: Float, label: String) {
        posX = x
        posY = y
        currentLabel = label
        pointerAlpha = 1f
        labelAlpha = if (label.isNotBlank()) 1f else 0f
        rippleRadius = 24f * density
        rippleAlpha = 0.85f
        invalidate()
        scheduleIdleFade(500L)
    }

    suspend fun executeAnimatedEvent(event: InputEvent, speedMult: Float) = suspendCancellableCoroutine<Unit> { cont ->
        idleFadeJob?.cancel()
        currentMoveAnimator?.cancel()
        buildArrowPath()
        buildHandPath()

        val startX = if (pointerAlpha > 0.05f) posX else event.startX
        val startY = if (pointerAlpha > 0.05f) posY else event.startY
        val targetX = if (event.type == InputEventType.SWIPE) event.startX else event.startX
        val targetY = if (event.type == InputEventType.SWIPE) event.startY else event.startY

        val dist = hypot(targetX - startX, targetY - startY)
        val baseDuration = (160L + (dist * 0.25f).toLong()).coerceIn(160L, 420L)
        val glideDuration = (baseDuration * speedMult).toLong()

        currentLabel = event.label
        labelAlpha = if (event.label.isNotBlank()) 1f else 0f
        pointerAlpha = 1f
        trailPoints.clear()

        // Move cursor to start position along smooth interpolation
        val moveAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = glideDuration
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val fraction = it.animatedValue as Float
                posX = startX + (targetX - startX) * fraction
                posY = startY + (targetY - startY) * fraction
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    when (event.type) {
                        InputEventType.TAP, InputEventType.DOUBLE_TAP -> {
                            playTapAnimation(cont)
                        }
                        InputEventType.LONG_PRESS -> {
                            playLongPressAnimation(event.durationMs, cont)
                        }
                        InputEventType.SWIPE -> {
                            playSwipeAnimation(event.startX, event.startY, event.endX, event.endY, event.durationMs, cont)
                        }
                        InputEventType.DRAW_PATH -> {
                            playPathAnimation(event.pathPoints, event.durationMs, cont)
                        }
                        else -> {
                            if (cont.isActive) cont.resume(Unit) {}
                            scheduleIdleFade(600L)
                        }
                    }
                }
            })
        }
        currentMoveAnimator = moveAnim
        moveAnim.start()
    }

    private fun playTapAnimation(continuation: CancellableContinuation<Unit>) {
        pointerScale = 0.82f
        rippleRadius = 6f * density
        rippleAlpha = 1.0f
        invalidate()

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180L
            addUpdateListener {
                val f = it.animatedValue as Float
                pointerScale = 0.82f + 0.18f * f
                rippleRadius = (6f + 26f * f) * density
                rippleAlpha = 1.0f - f
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (continuation.isActive) continuation.resume(Unit) {}
                    scheduleIdleFade(700L)
                }
            })
            start()
        }
    }

    private fun playLongPressAnimation(durationMs: Long, continuation: CancellableContinuation<Unit>) {
        pointerScale = 0.85f
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            addUpdateListener {
                longPressProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    pointerScale = 1.0f
                    longPressProgress = 0f
                    if (continuation.isActive) continuation.resume(Unit) {}
                    scheduleIdleFade(700L)
                }
            })
            start()
        }
    }

    private fun playSwipeAnimation(
        x1: Float, y1: Float, x2: Float, y2: Float,
        durationMs: Long,
        continuation: CancellableContinuation<Unit>
    ) {
        pointerScale = 0.90f
        trailPoints.clear()
        trailPoints.add(PointF(x1, y1))

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs.coerceIn(180L, 800L)
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                posX = x1 + (x2 - x1) * f
                posY = y1 + (y2 - y1) * f
                if (showTrail) {
                    trailPoints.add(PointF(posX, posY))
                    if (trailPoints.size > maxTrail) trailPoints.removeAt(0)
                }
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    pointerScale = 1.0f
                    trailPoints.clear()
                    if (continuation.isActive) continuation.resume(Unit) {}
                    scheduleIdleFade(700L)
                }
            })
            start()
        }
    }

    private fun playPathAnimation(
        points: List<Pair<Float, Float>>,
        durationMs: Long,
        continuation: CancellableContinuation<Unit>
    ) {
        if (points.size < 2) {
            if (continuation.isActive) continuation.resume(Unit) {}
            scheduleIdleFade(400L)
            return
        }

        pointerScale = 0.92f
        trailPoints.clear()
        val totalSegments = points.size - 1
        val segmentDuration = (durationMs / totalSegments).coerceAtLeast(30L)

        viewScope.launch {
            for (i in 0 until totalSegments) {
                val p1 = points[i]
                val p2 = points[i + 1]
                val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = segmentDuration
                    addUpdateListener {
                        val f = it.animatedValue as Float
                        posX = p1.first + (p2.first - p1.first) * f
                        posY = p1.second + (p2.second - p1.second) * f
                        if (showTrail) {
                            trailPoints.add(PointF(posX, posY))
                            if (trailPoints.size > maxTrail) trailPoints.removeAt(0)
                        }
                        invalidate()
                    }
                }
                anim.start()
                delay(segmentDuration)
            }
            pointerScale = 1.0f
            trailPoints.clear()
            if (continuation.isActive) continuation.resume(Unit) {}
            scheduleIdleFade(600L)
        }
    }

    private fun scheduleIdleFade(delayMs: Long) {
        idleFadeJob?.cancel()
        idleFadeJob = viewScope.launch {
            delay(delayMs)
            ValueAnimator.ofFloat(pointerAlpha, 0f).apply {
                duration = 220L
                addUpdateListener {
                    val a = it.animatedValue as Float
                    pointerAlpha = a
                    labelAlpha = a
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        onIdleDismissed?.invoke()
                    }
                })
                start()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pointerAlpha <= 0.01f) return

        // 1. Draw trail
        if (showTrail && trailPoints.size > 1) {
            val path = Path()
            path.moveTo(trailPoints.first().x, trailPoints.first().y)
            for (i in 1 until trailPoints.size) {
                path.lineTo(trailPoints[i].x, trailPoints[i].y)
            }
            trailPaint.alpha = (pointerAlpha * 180).roundToInt()
            canvas.drawPath(path, trailPaint)
        }

        // 2. Draw tap ripple
        if (showRipple && rippleAlpha > 0.01f) {
            ripplePaint.alpha = (rippleAlpha * 255).roundToInt()
            canvas.drawCircle(posX, posY, rippleRadius, ripplePaint)
        }

        // 3. Draw long press progress ring
        if (longPressProgress > 0.01f) {
            val ringRadius = 20f * density
            val oval = RectF(posX - ringRadius, posY - ringRadius, posX + ringRadius, posY + ringRadius)
            glowPaint.alpha = 255
            canvas.drawArc(oval, -90f, longPressProgress * 360f, false, glowPaint)
        }

        // 4. Draw pointer icon
        canvas.save()
        canvas.translate(posX, posY)
        canvas.scale(pointerScale, pointerScale)

        arrowFillPaint.alpha = (pointerAlpha * 255).roundToInt()
        arrowStrokePaint.alpha = (pointerAlpha * 255).roundToInt()

        when (cursorStyle) {
            CursorStyle.ARROW -> {
                canvas.drawPath(arrowPath, arrowFillPaint)
                canvas.drawPath(arrowPath, arrowStrokePaint)
            }
            CursorStyle.DOT -> {
                val r = (cursorSizeDp * 0.45f) * density
                dotGlowPaint.alpha = (pointerAlpha * 180).roundToInt()
                dotInnerPaint.alpha = (pointerAlpha * 255).roundToInt()
                dotBorderPaint.alpha = (pointerAlpha * 255).roundToInt()

                canvas.drawCircle(0f, 0f, r + 3f * density, dotGlowPaint)
                canvas.drawCircle(0f, 0f, r, dotInnerPaint)
                canvas.drawCircle(0f, 0f, r, dotBorderPaint)
            }
            CursorStyle.HAND -> {
                canvas.drawPath(handPath, arrowFillPaint)
                canvas.drawPath(handPath, arrowStrokePaint)
            }
        }
        canvas.restore()

        // 5. Draw Action Label Chip
        if (showActionLabel && currentLabel.isNotBlank() && labelAlpha > 0.01f) {
            val text = currentLabel
            val textWidth = labelTextPaint.measureText(text)
            val chipPadX = 10f * density
            val chipPadY = 5f * density
            val chipH = 22f * density
            val chipW = textWidth + chipPadX * 2

            val chipLeft = posX + 16f * density
            val chipTop = posY + 6f * density
            val chipRect = RectF(chipLeft, chipTop, chipLeft + chipW, chipTop + chipH)

            chipBgPaint.alpha = (labelAlpha * 220).roundToInt()
            chipBorderPaint.alpha = (labelAlpha * 140).roundToInt()
            labelTextPaint.alpha = (labelAlpha * 255).roundToInt()

            canvas.drawRoundRect(chipRect, 6f * density, 6f * density, chipBgPaint)
            canvas.drawRoundRect(chipRect, 6f * density, 6f * density, chipBorderPaint)
            canvas.drawText(text, chipLeft + chipPadX, chipTop + chipH - chipPadY - 1f * density, labelTextPaint)
        }
    }
}
