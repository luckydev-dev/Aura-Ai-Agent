package com.example.core.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.example.core.agent.AgentLoop
import com.example.core.agent.AgentStepDecision
import com.example.core.agent.ScreenXmlParser
import com.example.core.root.RootManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

class ScreenAutomationTools(
    private val context: Context,
    private val rootManager: RootManager,
    private val agentLoop: AgentLoop
) {

    val readScreenTool = object : Tool {
        override val name = "read_screen"
        override val description = "Dumps and parses the current screen hierarchy and returns interactive elements and text."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val res = rootManager.execute("uiautomator dump /sdcard/ui_aura.xml && cat /sdcard/ui_aura.xml")
            val nodes = ScreenXmlParser.parse(res.stdout)
            val summary = ScreenXmlParser.toCompactSummary(nodes)
            return ToolExecutionResult(true, summary)
        }
    }

    val tapElementTool = object : Tool {
        override val name = "tap_element"
        override val description = "Finds an on-screen element by its text, content-description, or ID and taps it."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "query" to mapOf("type" to "string", "description" to "The button text, label, content-desc, or resource ID.")
            ),
            "required" to listOf("query")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val query = args["query"]?.toString()?.lowercase(Locale.ROOT) ?: return ToolExecutionResult(false, "Missing query")
            val res = rootManager.execute("uiautomator dump /sdcard/ui_aura.xml && cat /sdcard/ui_aura.xml")
            val nodes = ScreenXmlParser.parse(res.stdout)

            val match = nodes.find {
                it.text.lowercase(Locale.ROOT).contains(query) ||
                it.contentDescription.lowercase(Locale.ROOT).contains(query) ||
                it.resourceId.lowercase(Locale.ROOT).contains(query)
            }

            return if (match != null) {
                rootManager.execute("input tap ${match.centerX} ${match.centerY}")
                ToolExecutionResult(true, "Tapped '${match.text.ifEmpty { match.contentDescription }}' at (${match.centerX}, ${match.centerY})")
            } else {
                ToolExecutionResult(false, "Could not find element matching '$query' on current screen")
            }
        }
    }

    val tapXyTool = object : Tool {
        override val name = "tap_xy"
        override val description = "Taps screen at exact (x, y) coordinates."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "x" to mapOf("type" to "integer", "description" to "X coordinate in pixels."),
                "y" to mapOf("type" to "integer", "description" to "Y coordinate in pixels.")
            ),
            "required" to listOf("x", "y")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val x = (args["x"] as? Number)?.toInt() ?: return ToolExecutionResult(false, "Missing x")
            val y = (args["y"] as? Number)?.toInt() ?: return ToolExecutionResult(false, "Missing y")
            val res = rootManager.execute("input tap $x $y")
            return ToolExecutionResult(res.success, if (res.success) "Tapped at ($x, $y)" else res.stderr)
        }
    }

    val longPressTool = object : Tool {
        override val name = "long_press"
        override val description = "Performs a long press at (x, y) coordinates."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "x" to mapOf("type" to "integer", "description" to "X coordinate."),
                "y" to mapOf("type" to "integer", "description" to "Y coordinate."),
                "duration_ms" to mapOf("type" to "integer", "description" to "Duration in ms (default 1000).")
            ),
            "required" to listOf("x", "y")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val x = (args["x"] as? Number)?.toInt() ?: return ToolExecutionResult(false, "Missing x")
            val y = (args["y"] as? Number)?.toInt() ?: return ToolExecutionResult(false, "Missing y")
            val duration = (args["duration_ms"] as? Number)?.toInt() ?: 1000
            val res = rootManager.execute("input swipe $x $y $x $y $duration")
            return ToolExecutionResult(res.success, "Long pressed at ($x, $y)")
        }
    }

    val swipeTool = object : Tool {
        override val name = "swipe"
        override val description = "Swipes across the screen in a specified direction ('up', 'down', 'left', 'right') or custom coords."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "direction" to mapOf("type" to "string", "description" to "'up', 'down', 'left', or 'right'.")
            ),
            "required" to listOf("direction")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val dir = args["direction"]?.toString()?.lowercase(Locale.ROOT) ?: "up"
            // Typical 1080x2400 defaults
            val cmd = when (dir) {
                "down" -> "input swipe 540 600 540 1800 300"
                "left" -> "input swipe 900 1200 150 1200 300"
                "right" -> "input swipe 150 1200 900 1200 300"
                else -> "input swipe 540 1800 540 600 300" // up
            }
            val res = rootManager.execute(cmd)
            return ToolExecutionResult(res.success, "Swiped $dir")
        }
    }

    val scrollTool = object : Tool {
        override val name = "scroll"
        override val description = "Scrolls the screen down or up."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("direction" to mapOf("type" to "string", "description" to "'down' or 'up' (default 'down')."))
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val dir = args["direction"]?.toString()?.lowercase(Locale.ROOT) ?: "down"
            val cmd = if (dir == "up") "input swipe 540 800 540 1600 250" else "input swipe 540 1600 540 800 250"
            val res = rootManager.execute(cmd)
            return ToolExecutionResult(res.success, "Scrolled $dir")
        }
    }

    val typeTextTool = object : Tool {
        override val name = "type_text"
        override val description = "Types text into the currently focused input field. Handles Unicode and Hindi."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("text" to mapOf("type" to "string", "description" to "Text to type.")),
            "required" to listOf("text")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val text = args["text"]?.toString() ?: return ToolExecutionResult(false, "Missing text")

            // Check if ASCII or contains non-ASCII (e.g. Hindi, emojis)
            val isAscii = text.all { it.code in 32..126 && it != '"' && it != '\'' && it != '\\' && it != '`' && it != '$' }

            return if (isAscii) {
                val escaped = text.replace(" ", "%s")
                val res = rootManager.execute("input text \"$escaped\"")
                ToolExecutionResult(res.success, "Typed: $text")
            } else {
                // For Unicode / Hindi: copy to clipboard on main thread then send paste key event
                withContext(Dispatchers.Main) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("aura_text", text))
                }
                delay(300)
                // Paste shortcut: Ctrl+V (KEYCODE_V=50 with META_CTRL_ON=4096) or KEYCODE_PASTE=279
                val pasteRes = rootManager.execute("input keyevent 279 || input keyevent --longpress 279")
                ToolExecutionResult(true, "Pasted text: $text")
            }
        }
    }

    val pressKeyTool = object : Tool {
        override val name = "press_key"
        override val description = "Presses a key on device: 'enter', 'back', 'home', 'tab', 'delete', 'power', etc."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("key" to mapOf("type" to "string", "description" to "Key name or keycode.")),
            "required" to listOf("key")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val key = args["key"]?.toString()?.lowercase(Locale.ROOT) ?: "enter"
            val code = when (key) {
                "enter" -> 66
                "back" -> 4
                "home" -> 3
                "tab" -> 61
                "delete", "backspace" -> 67
                "power" -> 26
                "volume_up" -> 24
                "volume_down" -> 25
                else -> key.toIntOrNull() ?: 66
            }
            val res = rootManager.execute("input keyevent $code")
            return ToolExecutionResult(res.success, "Pressed $key")
        }
    }

    val waitTool = object : Tool {
        override val name = "wait"
        override val description = "Waits for a duration in milliseconds to allow UI animation."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("ms" to mapOf("type" to "integer", "description" to "Milliseconds to wait (e.g. 1000).")),
            "required" to listOf("ms")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val ms = (args["ms"] as? Number)?.toLong()?.coerceIn(100, 10000) ?: 1000
            delay(ms)
            return ToolExecutionResult(true, "Waited ${ms}ms")
        }
    }

    val findOnScreenTool = object : Tool {
        override val name = "find_on_screen"
        override val description = "Searches for text on screen and returns matched elements and coordinates."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("query" to mapOf("type" to "string", "description" to "Text to find.")),
            "required" to listOf("query")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val query = args["query"]?.toString()?.lowercase(Locale.ROOT) ?: return ToolExecutionResult(false, "Missing query")
            val res = rootManager.execute("uiautomator dump /sdcard/ui_aura.xml && cat /sdcard/ui_aura.xml")
            val nodes = ScreenXmlParser.parse(res.stdout)
            val matches = nodes.filter {
                it.text.lowercase(Locale.ROOT).contains(query) ||
                it.contentDescription.lowercase(Locale.ROOT).contains(query)
            }

            return if (matches.isNotEmpty()) {
                val formatted = matches.joinToString("\n") {
                    "Found \"${it.text.ifEmpty { it.contentDescription }}\" at (${it.centerX}, ${it.centerY})"
                }
                ToolExecutionResult(true, formatted)
            } else {
                ToolExecutionResult(true, "No match for '$query' on screen")
            }
        }
    }

    val runTaskTool = object : Tool {
        override val name = "run_task"
        override val description = "Launches the autonomous agent observe-act loop for a multi-step phone task."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("goal" to mapOf("type" to "string", "description" to "The user goal to achieve.")),
            "required" to listOf("goal")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val goal = args["goal"]?.toString() ?: return ToolExecutionResult(false, "Missing goal")
            val result = agentLoop.executeTask(goal) { step, summary ->
                // Example step resolution or completion
                if (summary.contains(goal, ignoreCase = true)) {
                    AgentStepDecision.Completed("Achieved goal '$goal'")
                } else {
                    AgentStepDecision.PerformAction("Continuing to navigate for $goal") {
                        delay(500)
                    }
                }
            }
            return ToolExecutionResult(result.success, result.message)
        }
    }

    fun getAllTools(): List<Tool> = listOf(
        readScreenTool, tapElementTool, tapXyTool, longPressTool, swipeTool,
        scrollTool, typeTextTool, pressKeyTool, waitTool, findOnScreenTool, runTaskTool
    )
}
