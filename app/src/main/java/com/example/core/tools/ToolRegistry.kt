package com.example.core.tools

import android.content.Context
import com.example.core.agent.AgentLoop
import com.example.core.memory.MemoryRepository
import com.example.core.root.RootManager
import com.example.core.settings.AuraPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class ActionFeedItem(
    val id: String,
    val toolName: String,
    val description: String,
    val status: ActionStatus // RUNNING, SUCCESS, FAILED
)

enum class ActionStatus {
    RUNNING, SUCCESS, FAILED
}

class ToolRegistry(
    context: Context,
    rootManager: RootManager,
    private val preferences: AuraPreferences,
    private val memoryRepository: MemoryRepository,
    agentLoop: AgentLoop
) {
    private val toolsMap = mutableMapOf<String, Tool>()

    private val _actionFeed = MutableSharedFlow<ActionFeedItem>(extraBufferCapacity = 64)
    val actionFeed: SharedFlow<ActionFeedItem> = _actionFeed.asSharedFlow()

    init {
        val appTools = AppTools(context, rootManager)
        val commTools = CommTools(context, rootManager, preferences)
        val deviceTools = DeviceTools(context, rootManager)
        val screenTools = ScreenAutomationTools(context, rootManager, agentLoop)
        val mediaTools = MediaUtilityTools(context, rootManager)
        val powerTools = PowerTools(rootManager, preferences)

        val all = appTools.getAllTools() +
                  commTools.getAllTools() +
                  deviceTools.getAllTools() +
                  screenTools.getAllTools() +
                  mediaTools.getAllTools() +
                  powerTools.getAllTools()

        all.forEach { toolsMap[it.name] = it }
    }

    fun getAllTools(): List<Tool> = toolsMap.values.toList()

    fun getTool(name: String): Tool? = toolsMap[name]

    /**
     * Builds Gemini Live tool declarations list.
     */
    suspend fun getToolDeclarationsJson(): List<Map<String, Any>> {
        val disabled = preferences.disabledTools.first()
        return toolsMap.values
            .filter { it.name !in disabled }
            .map { tool ->
                mapOf(
                    "name" to tool.name,
                    "description" to tool.description,
                    "parameters" to tool.parametersSchema
                )
            }
    }

    suspend fun executeTool(name: String, args: Map<String, Any?>): ToolExecutionResult {
        val tool = toolsMap[name] ?: return ToolExecutionResult(false, "Unknown tool: $name")
        val itemId = "${name}_${System.currentTimeMillis()}"

        // Emit starting chip in action feed
        _actionFeed.tryEmit(
            ActionFeedItem(
                id = itemId,
                toolName = name,
                description = formatActionDescription(name, args),
                status = ActionStatus.RUNNING
            )
        )

        val start = System.currentTimeMillis()
        val result = try {
            tool.execute(args)
        } catch (e: Exception) {
            ToolExecutionResult(false, "Tool error: ${e.message}")
        }
        val duration = System.currentTimeMillis() - start

        // Emit finished chip in action feed
        _actionFeed.tryEmit(
            ActionFeedItem(
                id = itemId,
                toolName = name,
                description = formatActionDescription(name, args),
                status = if (result.success) ActionStatus.SUCCESS else ActionStatus.FAILED
            )
        )

        // Log to Room Database
        val argsJson = try {
            JSONObject(args).toString()
        } catch (_: Exception) {
            args.toString()
        }
        memoryRepository.logAction(
            toolName = name,
            argsJson = argsJson,
            output = result.output,
            success = result.success,
            durationMs = duration
        )

        return result
    }

    private fun formatActionDescription(name: String, args: Map<String, Any?>): String {
        return when (name) {
            "open_app" -> "Opening ${args["name_or_package"] ?: "app"}"
            "close_app" -> "Closing ${args["name"] ?: "app"}"
            "send_whatsapp" -> "Sending WhatsApp to ${args["contact_or_number"] ?: "contact"}"
            "send_sms" -> "Sending SMS to ${args["contact"] ?: "contact"}"
            "make_call" -> "Calling ${args["contact_or_number"] ?: "number"}"
            "set_wifi" -> if (args["on"] == true) "Turning on Wi-Fi" else "Turning off Wi-Fi"
            "set_bluetooth" -> if (args["on"] == true) "Turning on Bluetooth" else "Turning off Bluetooth"
            "set_brightness" -> "Setting brightness to ${args["percent"]}%"
            "set_volume" -> "Setting volume to ${args["percent"]}%"
            "take_screenshot" -> "Capturing screenshot"
            "read_screen" -> "Reading screen content"
            "tap_element" -> "Tapping ${args["query"] ?: "element"}"
            "type_text" -> "Typing text"
            "play_music" -> "Playing music: ${args["query"] ?: ""}"
            "set_alarm" -> "Setting alarm for ${args["hour"]}:${args["minute"]}"
            "run_shell" -> "Running command: ${args["command"] ?: ""}"
            else -> name.replace("_", " ").replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }
    }
}
