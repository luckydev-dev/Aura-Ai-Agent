package com.example.core.tools

import android.content.Context
import android.util.Log
import com.example.core.agent.AgentLoop
import com.example.core.memory.MemoryRepository
import com.example.core.root.RootManager
import com.example.core.settings.AuraPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.util.Locale

data class ActionFeedItem(
    val id: String,
    val toolName: String,
    val description: String,
    val status: ActionStatus
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

        all.forEach { tool ->
            require(tool.name.matches(Regex("""[a-zA-Z_][a-zA-Z0-9_]*"""))) {
                "Tool name '${tool.name}' is invalid"
            }
            toolsMap[tool.name] = tool
        }
    }

    fun getAllTools(): List<Tool> = toolsMap.values.toList()

    fun getTool(name: String): Tool? = toolsMap[name]

    /**
     * Sanitizes and normalizes parameter schema to strict Gemini Function Declaration rules:
     * - If properties is empty or missing, OMIT parameters entirely
     * - Normalize types to uppercase (OBJECT, STRING, INTEGER, NUMBER, BOOLEAN, ARRAY)
     * - Remove nulls and unsupported keys (additionalProperties, default, $schema)
     * - Filter required list so every entry exists in properties
     */
    fun sanitizeSchema(raw: Map<String, Any?>?): Map<String, Any>? {
        if (raw == null) return null
        val rawProps = raw["properties"] as? Map<*, *>
        if (rawProps.isNullOrEmpty()) {
            return null // OMIT parameters field completely
        }

        fun normalizeValue(value: Any?): Any? {
            return when (value) {
                null -> null
                is Map<*, *> -> {
                    val result = mutableMapOf<String, Any>()
                    for ((k, v) in value) {
                        if (k == null) continue
                        val keyStr = k.toString()
                        if (keyStr in listOf("additionalProperties", "default", "\$schema")) continue

                        when (keyStr) {
                            "type" -> {
                                if (v is String) {
                                    result["type"] = v.uppercase(Locale.ROOT)
                                }
                            }
                            "properties" -> {
                                if (v is Map<*, *>) {
                                    val cleanProps = mutableMapOf<String, Any>()
                                    for ((pk, pv) in v) {
                                        if (pk != null) {
                                            val norm = normalizeValue(pv)
                                            if (norm != null) {
                                                cleanProps[pk.toString()] = norm
                                            }
                                        }
                                    }
                                    if (cleanProps.isNotEmpty()) {
                                        result["properties"] = cleanProps
                                    }
                                }
                            }
                            "required" -> {
                                if (v is Collection<*>) {
                                    val reqList = v.mapNotNull { it?.toString() }
                                    val propKeys = (value["properties"] as? Map<*, *>)?.keys?.mapNotNull { it?.toString() } ?: emptyList()
                                    val validReq = reqList.filter { it in propKeys }
                                    if (validReq.isNotEmpty()) {
                                        result["required"] = validReq
                                    }
                                }
                            }
                            else -> {
                                val norm = normalizeValue(v)
                                if (norm != null) {
                                    result[keyStr] = norm
                                }
                            }
                        }
                    }
                    if (result.isEmpty()) null else result
                }
                is Collection<*> -> {
                    val list = value.mapNotNull { normalizeValue(it) }
                    list
                }
                is String -> value
                is Number -> value
                is Boolean -> value
                else -> value.toString()
            }
        }

        val normalized = normalizeValue(raw) as? Map<String, Any> ?: return null
        val finalProps = normalized["properties"] as? Map<*, *>
        if (finalProps.isNullOrEmpty()) return null
        return normalized
    }

    /**
     * Builds Gemini Live tool declarations list adhering to strict schema rules.
     */
    suspend fun getToolDeclarationsJson(): List<Map<String, Any>> {
        val disabled = preferences.disabledTools.first()
        return toolsMap.values
            .filter { it.name !in disabled }
            .map { tool ->
                val declaration = mutableMapOf<String, Any>(
                    "name" to tool.name,
                    "description" to tool.description
                )
                val sanitizedParams = sanitizeSchema(tool.parametersSchema)
                if (sanitizedParams != null) {
                    declaration["parameters"] = sanitizedParams
                }
                declaration
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
            Log.e("Aura", "Tool $name execution failed with exception: ${e.message}", e)
            ToolExecutionResult(false, "Tool execution failed: ${e.message}")
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
        } catch (e: Exception) {
            Log.w("Aura", "Failed to serialize args for $name: ${e.message}")
            args.toString()
        }

        try {
            memoryRepository.logAction(
                toolName = name,
                argsJson = argsJson,
                output = result.output,
                success = result.success,
                durationMs = duration
            )
        } catch (e: Exception) {
            Log.e("Aura", "Failed to log action to memory repository: ${e.message}", e)
        }

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
