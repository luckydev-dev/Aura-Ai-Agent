package com.example.core.root

import java.util.Locale

object SafetyLayer {

    // Absolute hard blocklist that cannot be bypassed under any circumstances
    private val CATASTROPHIC_PATTERNS = listOf(
        Regex("""\bdd\s+if=.*\sof=/dev/(block|sda|sdb|mmcblk0)"""),
        Regex("""\bmkfs(\.\w+)?\s+/dev/"""),
        Regex("""\bformat\s+/dev/"""),
        Regex("""\bflash_image\b"""),
        Regex("""\bwipe\s+(data|system)""")
    )

    // Patterns for operations requiring explicit confirmation when confirmation is enabled
    private val RISKY_PATTERNS = listOf(
        Regex("""\breboot\b"""),
        Regex("""\bshutdown\b"""),
        Regex("""\bpoweroff\b"""),
        Regex("""\bpm\s+uninstall\b"""),
        Regex("""\bpm\s+clear\b"""),
        Regex("""\brm\s+-rf\b"""),
        Regex("""\brmdir\b"""),
        Regex("""\bsettings\s+put\s+secure\b""")
    )

    /**
     * Checks if a command is completely forbidden to execute.
     */
    fun isCatastrophic(command: String): Boolean {
        val normalized = command.trim().lowercase(Locale.ROOT)

        // Check dangerous rm commands targeting root or system paths
        if (normalized.startsWith("rm ") || normalized.contains(" rm ")) {
            val hasRecursive = normalized.contains("-r") || normalized.contains("-f")
            if (hasRecursive) {
                val tokens = normalized.split("\\s+".toRegex())
                val target = tokens.lastOrNull() ?: ""
                if (target == "/" || target == "/data" || target == "/system" ||
                    target == "/data/" || target == "/system/"
                ) {
                    return true
                }
            }
        }

        return CATASTROPHIC_PATTERNS.any { it.containsMatchIn(normalized) }
    }

    /**
     * Checks if a command is risky and should prompt the user for confirmation.
     */
    fun isRisky(command: String): Boolean {
        val normalized = command.trim().lowercase(Locale.ROOT)
        if (isCatastrophic(normalized)) return true
        return RISKY_PATTERNS.any { it.containsMatchIn(normalized) }
    }

    /**
     * Validates command safety.
     * @return null if safe to execute directly, or a reason if blocked/requires confirmation.
     */
    fun evaluateCommand(command: String, requireConfirmation: Boolean): CommandSafetyAssessment {
        if (isCatastrophic(command)) {
            return CommandSafetyAssessment.CatastrophicBlocked(
                "Command contains a strictly prohibited catastrophic pattern that cannot be executed."
            )
        }
        if (requireConfirmation && isRisky(command)) {
            return CommandSafetyAssessment.RequiresConfirmation(
                "This action will modify system state or delete data. Confirmation required."
            )
        }
        return CommandSafetyAssessment.Safe
    }
}

sealed class CommandSafetyAssessment {
    object Safe : CommandSafetyAssessment()
    data class RequiresConfirmation(val message: String) : CommandSafetyAssessment()
    data class CatastrophicBlocked(val reason: String) : CommandSafetyAssessment()
}
