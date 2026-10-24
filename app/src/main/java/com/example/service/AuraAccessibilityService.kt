package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.core.agent.ScreenNode
import kotlinx.coroutines.*

class AuraAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AuraAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /**
     * Finds nodes by text across active window.
     */
    fun findNodesByText(text: String): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        return root.findAccessibilityNodeInfosByText(text) ?: emptyList()
    }

    /**
     * Traverses in-memory active window hierarchy and extracts compact ScreenNodes (sub-30ms).
     */
    fun extractScreenNodes(): List<ScreenNode> {
        val root = rootInActiveWindow ?: return emptyList()
        val nodes = mutableListOf<ScreenNode>()
        var counter = 1

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val rect = Rect()
            node.getBoundsInScreen(rect)

            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val viewId = node.viewIdResourceName?.substringAfterLast("/") ?: ""
            val cls = node.className?.toString()?.substringAfterLast(".") ?: ""
            val pkg = node.packageName?.toString() ?: ""

            val isClickable = node.isClickable
            val isEditable = node.isEditable
            val isScrollable = node.isScrollable
            val isFocusable = node.isFocusable

            // Include interactive or informative nodes, skip 0-size invisible bounds
            if (rect.width() > 0 && rect.height() > 0) {
                if (text.isNotBlank() || desc.isNotBlank() || viewId.isNotBlank() || isClickable || isEditable || isScrollable) {
                    nodes.add(
                        ScreenNode(
                            text = text.trim(),
                            contentDescription = desc.trim(),
                            resourceId = viewId,
                            className = cls,
                            bounds = "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]",
                            centerX = rect.centerX(),
                            centerY = rect.centerY(),
                            isClickable = isClickable,
                            isFocusable = isFocusable,
                            index = counter++,
                            isEditable = isEditable,
                            isScrollable = isScrollable,
                            packageName = pkg
                        )
                    )
                }
            }

            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        traverse(root)
        return nodes
    }

    /**
     * Sets text on an editable node directly via accessibility arguments.
     */
    fun setTextOnActiveNode(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        return false
    }

    /**
     * Performs a click gesture via Accessibility API as fallback to root.
     */
    fun clickAt(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Locks the device screen via system Accessibility API.
     */
    fun lockDevice(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }
        return false
    }

    /**
     * Swipes vertically upwards from bottom to center of screen to reveal lock credential screen.
     */
    fun swipeUpToUnlock(callback: (() -> Unit)? = null) {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()

        val path = Path().apply {
            moveTo(w / 2f, h * 0.85f)
            lineTo(w / 2f, h * 0.25f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 260))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback?.invoke()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback?.invoke()
            }
        }, null)
    }

    /**
     * Dispatches continuous multi-segment gesture across standard 3x3 pattern lock coordinates.
     */
    fun drawPatternGesture(points: List<Int>, callback: ((Boolean) -> Unit)? = null) {
        if (points.size < 2) {
            callback?.invoke(false)
            return
        }
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()

        val gridSize = (w * 0.78f).coerceAtMost(h * 0.38f)
        val startX = (w - gridSize) / 2f
        val startY = h * 0.52f
        val step = gridSize / 2f

        val path = Path()
        points.forEachIndexed { index, pt ->
            val col = pt % 3
            val row = pt / 3
            val x = startX + col * step
            val y = startY + row * step
            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        val duration = (points.size * 100L).coerceIn(350L, 1200L)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback?.invoke(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback?.invoke(false)
            }
        }, null)
    }

    /**
     * Executes automated wake, swipe up, and credential input to unlock the screen.
     */
    fun executeAutoUnlock(
        lockType: String,
        patternPoints: String,
        pin: String,
        password: String,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        serviceScope.launch {
            // Wake up screen if interactive is false
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm?.isInteractive == false) {
                val wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "Aura:UnlockWake"
                )
                wakeLock.acquire(3000)
                delay(600)
            }

            // Swipe up to reveal lock pattern or PIN
            swipeUpToUnlock {
                serviceScope.launch {
                    delay(600) // Allow lockscreen animation to settle
                    when (lockType.uppercase()) {
                        "PATTERN" -> {
                            val pts = patternPoints.split(",")
                                .mapNotNull { it.trim().toIntOrNull() }
                            if (pts.size >= 2) {
                                drawPatternGesture(pts) { success ->
                                    onComplete?.invoke(success)
                                }
                            } else {
                                onComplete?.invoke(false)
                            }
                        }
                        "PIN" -> {
                            if (pin.isNotBlank()) {
                                pin.forEach { ch ->
                                    val digitNodes = findNodesByText(ch.toString())
                                    val digitButton = digitNodes.firstOrNull { it.isClickable }
                                    if (digitButton != null) {
                                        digitButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    }
                                    delay(120)
                                }
                                onComplete?.invoke(true)
                            } else {
                                onComplete?.invoke(false)
                            }
                        }
                        "PASSWORD" -> {
                            if (password.isNotBlank()) {
                                setTextOnActiveNode(password)
                                onComplete?.invoke(true)
                            } else {
                                onComplete?.invoke(false)
                            }
                        }
                        else -> {
                            onComplete?.invoke(true)
                        }
                    }
                }
            }
        }
    }
}
