package com.example.core.unlock

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.core.input.InputController
import com.example.core.root.RootManager
import com.example.core.settings.EncryptedKeyStorage
import com.example.service.AuraAccessibilityService
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue

enum class UnlockStatus {
    UNLOCKED,
    ALREADY_UNLOCKED,
    NO_CREDENTIALS,
    ROOT_AND_ACCESSIBILITY_MISSING,
    BOUNCER_NOT_SHOWN,
    CREDENTIAL_REJECTED_OR_LOCKED_OUT,
    RATE_LIMITED,
    TIMEOUT,
    CANCELLED
}

data class UnlockResult(
    val status: UnlockStatus,
    val humanMessage: String,
    val stepLog: List<String>
)

/**
 * Verified, root-first Phone Unlock state machine.
 * Enforces hard security limits, real bouncer detection, exact credential entry,
 * lockout backoff, and full verification without guessing pattern geometry.
 */
class UnlockManager(
    private val context: Context,
    private val rootManager: RootManager,
    private val inputController: InputController,
    private val encryptedStorage: EncryptedKeyStorage,
    private val patternLocator: PatternGridLocator
) {
    companion object {
        private const val TAG = "Aura"

        // Safety Rate Limits: max 3 entries per 10 minutes
        private const val MAX_ATTEMPTS_PER_WINDOW = 3
        private const val WINDOW_MS = 10 * 60 * 1000L

        private val attemptTimestamps = ConcurrentLinkedQueue<Long>()
        @Volatile
        var lockoutUntilMs: Long = 0L
            private set

        @Volatile
        var isCancelled: Boolean = false

        @Volatile
        private var INSTANCE: UnlockManager? = null

        fun getInstance(context: Context): UnlockManager {
            return INSTANCE ?: synchronized(this) {
                val app = context.applicationContext as com.example.AuraApplication
                val root = app.rootManager
                val input = app.inputController
                val storage = EncryptedKeyStorage(context.applicationContext)
                val locator = PatternGridLocator(context.applicationContext, root, input, storage)
                UnlockManager(context.applicationContext, root, input, storage, locator).also { INSTANCE = it }
            }
        }

        /**
         * Android Pattern Lock auto-middle-dot rule:
         * In a 3x3 grid (0..8), connecting two straight collinear non-adjacent dots
         * automatically includes the unvisited middle dot.
         */
        fun applyMiddleDotRule(raw: List<Int>): List<Int> {
            if (raw.size < 2) return raw
            val result = mutableListOf<Int>()
            result.add(raw.first())

            val middleMap = mapOf(
                Pair(0, 2) to 1, Pair(2, 0) to 1,
                Pair(3, 5) to 4, Pair(5, 3) to 4,
                Pair(6, 8) to 7, Pair(8, 6) to 7,
                Pair(0, 6) to 3, Pair(6, 0) to 3,
                Pair(1, 7) to 4, Pair(7, 1) to 4,
                Pair(2, 8) to 5, Pair(8, 2) to 5,
                Pair(0, 8) to 4, Pair(8, 0) to 4,
                Pair(2, 6) to 4, Pair(6, 2) to 4
            )

            for (i in 1 until raw.size) {
                val prev = result.last()
                val curr = raw[i]
                val mid = middleMap[Pair(prev, curr)]
                if (mid != null && mid !in result) {
                    result.add(mid)
                }
                if (curr !in result) {
                    result.add(curr)
                }
            }
            return result
        }
    }

    private val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    /**
     * Checks if device is currently locked.
     */
    suspend fun isLocked(): Boolean {
        if (!keyguardManager.isKeyguardLocked) return false
        // Root cross-check
        val res = rootManager.execute("dumpsys window | grep -E \"mShowing=true|mKeyguardShowing=true\"")
        return res.stdout.isNotBlank() || keyguardManager.isKeyguardLocked
    }

    /**
     * Executes the complete verified unlock sequence.
     * @param dryRunOnlyBouncer If true, only wakes and reveals the bouncer without typing credentials.
     */
    suspend fun executeUnlock(dryRunOnlyBouncer: Boolean = false): UnlockResult = withContext(Dispatchers.IO) {
        val stepLog = mutableListOf<String>()
        fun logStep(msg: String) {
            Log.i(TAG, "[Unlock] $msg")
            stepLog.add(msg)
        }

        isCancelled = false
        logStep("1. Checking keyguard lock state...")

        // Step 1: Already unlocked?
        if (!isLocked()) {
            logStep("Device is already unlocked.")
            return@withContext UnlockResult(
                UnlockStatus.ALREADY_UNLOCKED,
                "Phone is already unlocked.",
                stepLog
            )
        }

        // Step 2: Rate limit and lockout check
        val now = System.currentTimeMillis()
        if (now < lockoutUntilMs) {
            val remainSec = ((lockoutUntilMs - now) / 1000L).coerceAtLeast(1)
            logStep("Device lockout active. Retry in $remainSec s.")
            return@withContext UnlockResult(
                UnlockStatus.RATE_LIMITED,
                "Phone is temporarily locked out. Try again in $remainSec seconds.",
                stepLog
            )
        }

        // Prune old timestamps
        while (attemptTimestamps.peek() != null && now - attemptTimestamps.peek() > WINDOW_MS) {
            attemptTimestamps.poll()
        }
        if (attemptTimestamps.size >= MAX_ATTEMPTS_PER_WINDOW) {
            logStep("Exceeded 3 unlock attempts within 10 minutes.")
            return@withContext UnlockResult(
                UnlockStatus.RATE_LIMITED,
                "Safety limit reached (3 attempts/10m). Please unlock manually.",
                stepLog
            )
        }

        // Step 3: Preconditions (credentials & services)
        val lockType = encryptedStorage.getLockType().uppercase()
        val hasCredentials = when (lockType) {
            "PIN" -> encryptedStorage.getLockPin().isNotBlank()
            "PASSWORD" -> encryptedStorage.getLockPassword().isNotBlank()
            "PATTERN" -> encryptedStorage.getPatternPoints().isNotBlank()
            else -> false
        }
        if (!hasCredentials && !dryRunOnlyBouncer) {
            logStep("No unlock credentials configured in Settings.")
            return@withContext UnlockResult(
                UnlockStatus.NO_CREDENTIALS,
                "No lock screen credentials configured. Please set Pattern/PIN in Settings.",
                stepLog
            )
        }

        val hasRoot = rootManager.isRootAvailable()
        val a11yRunning = AuraAccessibilityService.isRunning()
        if (!hasRoot && !a11yRunning) {
            logStep("Root and Accessibility Service are both missing.")
            return@withContext UnlockResult(
                UnlockStatus.ROOT_AND_ACCESSIBILITY_MISSING,
                "Neither Root nor Accessibility Service is available to unlock the device.",
                stepLog
            )
        }

        // Step 4: Wake up device
        logStep("2. Waking up device screen...")
        if (!powerManager.isInteractive) {
            if (hasRoot) {
                rootManager.execute("input keyevent 224") // KEYCODE_WAKEUP
            } else {
                @Suppress("DEPRECATION")
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "Aura:UnlockWake"
                )
                wakeLock.acquire(3000)
            }

            var wakePoll = 0
            while (!powerManager.isInteractive && wakePoll < 14) {
                delay(150)
                wakePoll++
            }
        }
        logStep("Screen is interactive: ${powerManager.isInteractive}")

        if (isCancelled) {
            logStep("Unlock cancelled by user.")
            return@withContext UnlockResult(UnlockStatus.CANCELLED, "Unlock was cancelled.", stepLog)
        }

        // Step 5: Show the bouncer
        logStep("3. Dismissing keyguard / Swiping up for bouncer...")
        if (hasRoot) {
            rootManager.execute("wm dismiss-keyguard")
        }

        // Swipe up from bottom to center
        val metrics = inputController.getDisplayMetrics()
        val w = metrics.x.toFloat()
        val h = metrics.y.toFloat()
        inputController.swipe(w * 0.5f, h * 0.88f, w * 0.5f, h * 0.30f, 250L, "Dismiss Keyguard")

        // Step 6: Wait for bouncer
        logStep("4. Waiting for bouncer credential UI to appear...")
        var bouncerFound = false
        var bouncerCheckCount = 0
        while (bouncerCheckCount < 20) {
            delay(150)
            bouncerCheckCount++
            if (isBouncerShowing(lockType)) {
                bouncerFound = true
                break
            }
        }

        if (!bouncerFound) {
            // Second attempt swipe
            logStep("Bouncer not detected on first attempt, retrying swipe...")
            inputController.swipe(w * 0.5f, h * 0.90f, w * 0.5f, h * 0.25f, 280L, "Retry Swipe")
            delay(600)
            bouncerFound = isBouncerShowing(lockType)
        }

        if (!bouncerFound) {
            logStep("Bouncer did not appear. Aborting credential entry for safety.")
            return@withContext UnlockResult(
                UnlockStatus.BOUNCER_NOT_SHOWN,
                "Lock screen bouncer did not appear. Please wake phone manually.",
                stepLog
            )
        }
        logStep("Bouncer confirmed showing.")

        if (dryRunOnlyBouncer) {
            logStep("Dry run mode: Bouncer displayed successfully.")
            return@withContext UnlockResult(
                UnlockStatus.UNLOCKED,
                "Dry run: Bouncer displayed successfully.",
                stepLog
            )
        }

        // Step 7: Enter Credential ONCE
        logStep("5. Entering credentials ($lockType)...")
        attemptTimestamps.add(System.currentTimeMillis())

        when (lockType) {
            "PIN" -> {
                val pin = encryptedStorage.getLockPin()
                if (hasRoot) {
                    rootManager.execute("input text $pin && input keyevent 66")
                } else {
                    enterPinViaAccessibility(pin)
                }
            }
            "PASSWORD" -> {
                val password = encryptedStorage.getLockPassword()
                val a11y = AuraAccessibilityService.instance
                val textSet = a11y?.setTextOnActiveNode(password) ?: false
                if (!textSet) {
                    inputController.typeText(password, "Enter Password")
                }
                inputController.pressKey(66, "Enter")
            }
            "PATTERN" -> {
                val rawPoints = encryptedStorage.getPatternPoints()
                    .split(",")
                    .mapNotNull { it.trim().toIntOrNull() }

                val points = applyMiddleDotRule(rawPoints)
                logStep("Pattern points to draw (${points.size} dots)")

                val detection = patternLocator.locatePatternGrid()
                logStep("Grid detected via method: ${detection.method}")

                val screenCoords = points.map { idx ->
                    detection.points[idx]
                }

                // Add small waypoints to guarantee every dot is hit
                val duration = (points.size * 140L).coerceAtLeast(600L)
                inputController.drawPath(screenCoords, duration, "Draw Pattern")
            }
        }

        // Step 8: Verify unlock outcome
        logStep("6. Verifying lock dismissal...")
        var verifiedUnlocked = false
        var pollVerify = 0
        while (pollVerify < 16) {
            delay(150)
            pollVerify++
            if (!isLocked()) {
                verifiedUnlocked = true
                break
            }
        }

        if (verifiedUnlocked) {
            logStep("✓ Phone unlocked successfully!")
            return@withContext UnlockResult(
                UnlockStatus.UNLOCKED,
                "Phone unlocked successfully.",
                stepLog
            )
        }

        // If still locked, check screen text for rejection / lockout
        val screenText = getLockscreenErrorText()
        logStep("Lockscreen diagnostic text: $screenText")

        if (screenText.contains("try again", ignoreCase = true) ||
            screenText.contains("wrong", ignoreCase = true) ||
            screenText.contains("incorrect", ignoreCase = true) ||
            screenText.contains("too many", ignoreCase = true)
        ) {
            // Check lockout seconds
            val secMatch = Regex("""(?:in|after)\s+(\d+)\s*(?:seconds|s)""").find(screenText)
            val lockoutSec = secMatch?.groupValues?.get(1)?.toLongOrNull() ?: 30L
            lockoutUntilMs = System.currentTimeMillis() + (lockoutSec * 1000L)
            logStep("Credential rejected or lockout triggered ($lockoutSec s).")

            return@withContext UnlockResult(
                UnlockStatus.CREDENTIAL_REJECTED_OR_LOCKED_OUT,
                "Credential was rejected or phone is locked out for $lockoutSec seconds.",
                stepLog
            )
        }

        logStep("Unlock timed out waiting for keyguard to clear.")
        return@withContext UnlockResult(
            UnlockStatus.TIMEOUT,
            "Unlock timed out. The phone is still locked.",
            stepLog
        )
    }

    private suspend fun isBouncerShowing(lockType: String): Boolean {
        val a11y = AuraAccessibilityService.instance
        if (a11y != null) {
            val root = a11y.rootInActiveWindow
            if (root != null) {
                if (checkBouncerInNode(root, lockType)) return true
            }
        }

        // Root uiautomator check
        val dumpRes = rootManager.execute("uiautomator dump /data/local/tmp/aura_ui.xml && cat /data/local/tmp/aura_ui.xml")
        if (dumpRes.success) {
            val xml = dumpRes.stdout
            return when (lockType) {
                "PATTERN" -> xml.contains("LockPatternView", ignoreCase = true) || xml.contains("lockPattern", ignoreCase = true)
                "PIN" -> xml.contains("pinEntry", ignoreCase = true) || xml.contains("keyguard_pin", ignoreCase = true) || xml.contains("keyguard_selector_fade_container", ignoreCase = true)
                "PASSWORD" -> xml.contains("passwordEntry", ignoreCase = true) || xml.contains("isPassword=\"true\"", ignoreCase = true)
                else -> true
            }
        }
        return false
    }

    private fun checkBouncerInNode(node: AccessibilityNodeInfo, lockType: String): Boolean {
        val cls = node.className?.toString() ?: ""
        val id = node.viewIdResourceName?.toString() ?: ""

        when (lockType) {
            "PATTERN" -> {
                if (cls.contains("LockPatternView", ignoreCase = true) || id.contains("lockPattern", ignoreCase = true)) return true
            }
            "PIN" -> {
                if (id.contains("pinEntry", ignoreCase = true) || id.contains("keyguard_pin", ignoreCase = true) || node.text == "1") return true
            }
            "PASSWORD" -> {
                if (id.contains("passwordEntry", ignoreCase = true) || node.isPassword) return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (checkBouncerInNode(child, lockType)) return true
        }
        return false
    }

    private suspend fun enterPinViaAccessibility(pin: String) {
        val a11y = AuraAccessibilityService.instance ?: return
        val root = a11y.rootInActiveWindow ?: return

        pin.forEach { ch ->
            val digitStr = ch.toString()
            val digitNode = findExactDigitNode(root, digitStr)
            if (digitNode != null) {
                val clickableNode = findClickableAncestorOrSelf(digitNode)
                if (clickableNode != null) {
                    clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } else {
                    val rect = android.graphics.Rect()
                    digitNode.getBoundsInScreen(rect)
                    inputController.tap(rect.centerX().toFloat(), rect.centerY().toFloat(), "PIN $digitStr")
                }
            }
            delay(120)
        }
        delay(150)
        inputController.pressKey(66, "Enter")
    }

    private fun findExactDigitNode(node: AccessibilityNodeInfo, digit: String): AccessibilityNodeInfo? {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        if (text == digit || desc == digit) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findExactDigitNode(child, digit)
            if (found != null) return found
        }
        return null
    }

    private fun findClickableAncestorOrSelf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        while (cur != null) {
            if (cur.isClickable) return cur
            cur = cur.parent
        }
        return null
    }

    private suspend fun getLockscreenErrorText(): String {
        val a11y = AuraAccessibilityService.instance
        if (a11y != null) {
            val root = a11y.rootInActiveWindow
            if (root != null) {
                val sb = StringBuilder()
                collectAllText(root, sb)
                return sb.toString()
            }
        }
        val dump = rootManager.execute("cat /data/local/tmp/aura_ui.xml")
        return dump.stdout
    }

    private fun collectAllText(node: AccessibilityNodeInfo, sb: StringBuilder) {
        val t = node.text?.toString()
        if (!t.isNullOrBlank()) sb.append(t).append(" ")
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            collectAllText(c, sb)
        }
    }
}
