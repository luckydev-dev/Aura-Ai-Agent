package com.example

import com.example.core.root.CommandSafetyAssessment
import com.example.core.root.SafetyLayer
import org.junit.Assert.*
import org.junit.Test

class SafetyLayerTest {

    @Test
    fun testCatastrophicCommandsAreBlocked() {
        val catastrophicCommands = listOf(
            "rm -rf /",
            "rm -f -r /",
            "rm -rf /data",
            "rm -rf /system",
            "dd if=/dev/zero of=/dev/block/bootdevice",
            "mkfs.ext4 /dev/block/sda"
        )

        for (cmd in catastrophicCommands) {
            assertTrue("Expected '$cmd' to be detected as catastrophic", SafetyLayer.isCatastrophic(cmd))
            val assessment = SafetyLayer.evaluateCommand(cmd, requireConfirmation = false)
            assertTrue(
                "Expected catastrophic assessment for '$cmd'",
                assessment is CommandSafetyAssessment.CatastrophicBlocked
            )
        }
    }

    @Test
    fun testRiskyCommandsRequireConfirmation() {
        val riskyCommands = listOf(
            "reboot",
            "reboot recovery",
            "shutdown",
            "poweroff",
            "pm uninstall com.whatsapp",
            "pm clear com.google.android.youtube",
            "settings put secure enabled_accessibility_services com.example"
        )

        for (cmd in riskyCommands) {
            assertTrue("Expected '$cmd' to be marked as risky", SafetyLayer.isRisky(cmd))
            val assessment = SafetyLayer.evaluateCommand(cmd, requireConfirmation = true)
            assertTrue(
                "Expected confirmation requirement for '$cmd'",
                assessment is CommandSafetyAssessment.RequiresConfirmation
            )
        }
    }

    @Test
    fun testSafeCommandsPassDirectly() {
        val safeCommands = listOf(
            "ls -la /sdcard",
            "dumpsys battery",
            "input tap 500 1000",
            "input text hello",
            "am start -a android.intent.action.VIEW",
            "cat /proc/meminfo"
        )

        for (cmd in safeCommands) {
            assertFalse("Expected '$cmd' not to be catastrophic", SafetyLayer.isCatastrophic(cmd))
            val assessment = SafetyLayer.evaluateCommand(cmd, requireConfirmation = true)
            assertEquals("Expected '$cmd' to be safe", CommandSafetyAssessment.Safe, assessment)
        }
    }
}
