package com.example.core.input

import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Point
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.example.core.root.RootManager
import com.example.service.AuraAccessibilityService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.roundToInt

/**
 * Central Input Controller for all programmatic screen interactions.
 * Every tap, swipe, scroll, drag, gesture, text entry, and key event in the app
 * routes through this controller. Coordinates are always real device pixels,
 * safe zones avoid gesture conflict margins, and events synchronize with the Agent Cursor.
 */
class InputController(
    private val context: Context,
    private val rootManager: RootManager
) {
    companion object {
        private const val TAG = "Aura"

        @Volatile
        private var instance: InputController? = null

        fun getInstance(context: Context, rootManager: RootManager): InputController {
            return instance ?: synchronized(this) {
                instance ?: InputController(context.applicationContext, rootManager).also { instance = it }
            }
        }
    }

    private val _events = MutableSharedFlow<InputEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<InputEvent> = _events.asSharedFlow()

    // Cursor synchronization callback
    var cursorListener: (suspend (InputEvent) -> Unit)? = null

    // Cursor suppression (for screenshots and accessibility dumps)
    @Volatile
    var isCursorSuppressed: Boolean = false

    /**
     * Gets real display dimensions in pixels.
     */
    fun getDisplayMetrics(): Point {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Point(dm.widthPixels, dm.heightPixels)
        }
    }

    fun getSafeBounds(): Pair<Point, Point> {
        val size = getDisplayMetrics()
        val minX = (size.x * 0.04f).roundToInt()
        val maxX = (size.x * 0.96f).roundToInt()
        val minY = (size.y * 0.05f).roundToInt()
        val maxY = (size.y * 0.94f).roundToInt()
        return Pair(Point(minX, minY), Point(maxX, maxY))
    }

    fun clampToSafe(x: Float, y: Float): Pair<Float, Float> {
        val size = getDisplayMetrics()
        val cx = x.coerceIn(size.x * 0.03f, size.x * 0.97f)
        val cy = y.coerceIn(size.y * 0.04f, size.y * 0.96f)
        return Pair(cx, cy)
    }

    private suspend fun notifyCursor(event: InputEvent) {
        _events.emit(event)
        if (!isCursorSuppressed) {
            cursorListener?.invoke(event)
        }
    }

    /**
     * Taps at real screen pixel coordinates (x, y).
     */
    suspend fun tap(x: Float, y: Float, label: String = ""): Boolean = withContext(Dispatchers.IO) {
        val (cx, cy) = clampToSafe(x, y)
        val event = InputEvent(
            type = InputEventType.TAP,
            startX = cx,
            startY = cy,
            endX = cx,
            endY = cy,
            durationMs = 50L,
            label = label
        )
        notifyCursor(event)

        val ix = cx.roundToInt()
        val iy = cy.roundToInt()
        val rootRes = rootManager.execute("input tap $ix $iy")
        if (rootRes.success) return@withContext true

        // Fallback: Accessibility service
        val a11y = AuraAccessibilityService.instance
        if (a11y != null) {
            a11y.clickAt(cx, cy)
            return@withContext true
        }
        Log.e(TAG, "InputController.tap failed at ($ix, $iy): root=${rootRes.stderr}, a11y=null")
        false
    }

    /**
     * Double taps at real screen pixel coordinates.
     */
    suspend fun doubleTap(x: Float, y: Float, label: String = ""): Boolean = withContext(Dispatchers.IO) {
        val (cx, cy) = clampToSafe(x, y)
        val event = InputEvent(
            type = InputEventType.DOUBLE_TAP,
            startX = cx,
            startY = cy,
            endX = cx,
            endY = cy,
            durationMs = 120L,
            label = label
        )
        notifyCursor(event)

        val ix = cx.roundToInt()
        val iy = cy.roundToInt()
        val r1 = rootManager.execute("input tap $ix $iy")
        delay(90)
        val r2 = rootManager.execute("input tap $ix $iy")
        if (r1.success && r2.success) return@withContext true

        val a11y = AuraAccessibilityService.instance
        if (a11y != null) {
            a11y.clickAt(cx, cy)
            delay(90)
            a11y.clickAt(cx, cy)
            return@withContext true
        }
        false
    }

    /**
     * Long press at real screen pixel coordinates for durationMs.
     */
    suspend fun longPress(x: Float, y: Float, durationMs: Long = 1000L, label: String = ""): Boolean = withContext(Dispatchers.IO) {
        val (cx, cy) = clampToSafe(x, y)
        val event = InputEvent(
            type = InputEventType.LONG_PRESS,
            startX = cx,
            startY = cy,
            endX = cx,
            endY = cy,
            durationMs = durationMs,
            label = label
        )
        notifyCursor(event)

        val ix = cx.roundToInt()
        val iy = cy.roundToInt()
        val res = rootManager.execute("input swipe $ix $iy $ix $iy $durationMs")
        if (res.success) return@withContext true

        val a11y = AuraAccessibilityService.instance
        if (a11y != null) {
            val path = Path().apply { moveTo(cx, cy) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            a11y.dispatchGesture(gesture, null, null)
            return@withContext true
        }
        false
    }

    /**
     * Swipes from (x1, y1) to (x2, y2) over durationMs.
     */
    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300L, label: String = ""): Boolean = withContext(Dispatchers.IO) {
        val (cx1, cy1) = clampToSafe(x1, y1)
        val (cx2, cy2) = clampToSafe(x2, y2)
        val event = InputEvent(
            type = InputEventType.SWIPE,
            startX = cx1,
            startY = cy1,
            endX = cx2,
            endY = cy2,
            durationMs = durationMs,
            label = label
        )
        notifyCursor(event)

        val ix1 = cx1.roundToInt()
        val iy1 = cy1.roundToInt()
        val ix2 = cx2.roundToInt()
        val iy2 = cy2.roundToInt()
        val res = rootManager.execute("input swipe $ix1 $iy1 $ix2 $iy2 $durationMs")
        if (res.success) return@withContext true

        val a11y = AuraAccessibilityService.instance
        if (a11y != null) {
            val path = Path().apply {
                moveTo(cx1, cy1)
                lineTo(cx2, cy2)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            a11y.dispatchGesture(gesture, null, null)
            return@withContext true
        }
        false
    }

    /**
     * Relative scroll in a direction (up, down, left, right) by a fraction of available screen size.
     */
    suspend fun scroll(direction: String, amountFraction: Float = 0.5f, label: String = ""): Boolean {
        val size = getDisplayMetrics()
        val w = size.x.toFloat()
        val h = size.y.toFloat()
        val frac = amountFraction.coerceIn(0.1f, 0.85f)

        return when (direction.lowercase()) {
            "down" -> {
                // Drag down = scrolls content up: from higher Y to lower Y
                val startX = w * 0.5f
                val startY = h * (0.5f + frac * 0.35f)
                val endY = h * (0.5f - frac * 0.35f)
                swipe(startX, startY, startX, endY, 280L, label.ifBlank { "Scroll Down" })
            }
            "up" -> {
                // Drag up = scrolls content down: from lower Y to higher Y
                val startX = w * 0.5f
                val startY = h * (0.5f - frac * 0.35f)
                val endY = h * (0.5f + frac * 0.35f)
                swipe(startX, startY, startX, endY, 280L, label.ifBlank { "Scroll Up" })
            }
            "left" -> {
                val startX = w * (0.5f + frac * 0.35f)
                val endX = w * (0.5f - frac * 0.35f)
                val startY = h * 0.5f
                swipe(startX, startY, endX, startY, 280L, label.ifBlank { "Scroll Left" })
            }
            "right" -> {
                val startX = w * (0.5f - frac * 0.35f)
                val endX = w * (0.5f + frac * 0.35f)
                val startY = h * 0.5f
                swipe(startX, startY, endX, startY, 280L, label.ifBlank { "Scroll Right" })
            }
            else -> {
                val startX = w * 0.5f
                val startY = h * 0.72f
                val endY = h * 0.30f
                swipe(startX, startY, startX, endY, 280L, label.ifBlank { "Scroll" })
            }
        }
    }

    /**
     * Swipes in a cardinal direction (up, down, left, right).
     */
    suspend fun swipeDirection(direction: String, label: String = ""): Boolean {
        return scroll(direction, 0.65f, label)
    }

    /**
     * Draws continuous multi-segment path (e.g. for pattern lock or drawing).
     */
    suspend fun drawPath(points: List<Pair<Float, Float>>, durationMs: Long = 600L, label: String = ""): Boolean = withContext(Dispatchers.IO) {
        if (points.size < 2) return@withContext false

        val event = InputEvent(
            type = InputEventType.DRAW_PATH,
            pathPoints = points,
            durationMs = durationMs,
            label = label
        )
        notifyCursor(event)

        // 1. Root-first for lockscreen gestures (immune to keyguard touch isolation)
        if (rootManager.isRootAvailable()) {
            val sb = StringBuilder()
            val first = points.first()
            sb.append("input motionevent DOWN ${first.first.roundToInt()} ${first.second.roundToInt()}; ")
            for (i in 1 until points.size) {
                val p1 = points[i - 1]
                val p2 = points[i]
                val steps = 8
                for (s in 1..steps) {
                    val ix = (p1.first + (p2.first - p1.first) * (s.toFloat() / steps)).roundToInt()
                    val iy = (p1.second + (p2.second - p1.second) * (s.toFloat() / steps)).roundToInt()
                    sb.append("sleep 0.012; input motionevent MOVE $ix $iy; ")
                }
            }
            val last = points.last()
            sb.append("sleep 0.04; input motionevent UP ${last.first.roundToInt()} ${last.second.roundToInt()}")
            val res = rootManager.execute(sb.toString())
            if (res.success) return@withContext true
        }

        // 2. Accessibility Gesture fallback
        val a11y = AuraAccessibilityService.instance
        if (a11y != null) {
            val path = Path()
            points.forEachIndexed { i, pt ->
                if (i == 0) path.moveTo(pt.first, pt.second)
                else path.lineTo(pt.first, pt.second)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()

            val lock = CompletableDeferred<Boolean>()
            a11y.dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    lock.complete(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    lock.complete(false)
                }
            }, null)

            val a11ySuccess = withTimeoutOrNull(durationMs + 1000) { lock.await() } ?: false
            if (a11ySuccess) return@withContext true
        }
        false
    }

    /**
     * Types text into the currently focused input field.
     */
    suspend fun typeText(text: String, label: String = ""): Boolean = withContext(Dispatchers.IO) {
        val event = InputEvent(
            type = InputEventType.TYPE_TEXT,
            text = text,
            durationMs = 150L,
            label = label.ifBlank { "Typing..." }
        )
        notifyCursor(event)

        // Escape text for shell input
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("'", "\\'")
            .replace(" ", "%s")
            .replace("&", "\\&")
            .replace("|", "\\|")
            .replace("<", "\\<")
            .replace(">", "\\>")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace(";", "\\;")
            .replace("$", "\\$")

        val res = rootManager.execute("input text \"$escaped\"")
        if (res.success) return@withContext true

        // Fallback: accessibility node text
        val a11y = AuraAccessibilityService.instance
        if (a11y != null && a11y.setTextOnActiveNode(text)) {
            return@withContext true
        }
        false
    }

    /**
     * Presses a key code (e.g. 3 for HOME, 4 for BACK, 66 for ENTER, 224 for WAKEUP, 223 for SLEEP).
     */
    suspend fun pressKey(keyCode: Int, label: String = ""): Boolean = withContext(Dispatchers.IO) {
        val event = InputEvent(
            type = InputEventType.KEY_EVENT,
            keyCode = keyCode,
            durationMs = 50L,
            label = label
        )
        notifyCursor(event)

        // Special handling for system global actions if accessibility is available
        val a11y = AuraAccessibilityService.instance
        if (a11y != null) {
            when (keyCode) {
                4 -> if (a11y.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)) return@withContext true
                3 -> if (a11y.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)) return@withContext true
                187 -> if (a11y.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)) return@withContext true
                223 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && a11y.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) return@withContext true
            }
        }

        val res = rootManager.execute("input keyevent $keyCode")
        res.success
    }
}
