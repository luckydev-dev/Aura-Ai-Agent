package com.example.core.tools

import android.content.Context
import android.hardware.camera2.CameraManager
import com.example.core.root.RootManager
import java.util.Locale

class DeviceTools(
    private val context: Context,
    private val rootManager: RootManager
) {

    val setWifiTool = object : Tool {
        override val name = "set_wifi"
        override val description = "Turns Wi-Fi on or off."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("on" to mapOf("type" to "boolean", "description" to "true to turn on, false to turn off.")),
            "required" to listOf("on")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val on = args["on"] as? Boolean ?: return ToolExecutionResult(false, "Missing 'on' parameter")
            val cmd = if (on) "svc wifi enable || cmd wifi set-wifi-enabled enabled" else "svc wifi disable || cmd wifi set-wifi-enabled disabled"
            val res = rootManager.execute(cmd)
            return ToolExecutionResult(res.success, if (on) "Wi-Fi turned on" else "Wi-Fi turned off")
        }
    }

    val setBluetoothTool = object : Tool {
        override val name = "set_bluetooth"
        override val description = "Turns Bluetooth on or off."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("on" to mapOf("type" to "boolean", "description" to "true to turn on, false to turn off.")),
            "required" to listOf("on")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val on = args["on"] as? Boolean ?: return ToolExecutionResult(false, "Missing 'on' parameter")
            val cmd = if (on) "svc bluetooth enable || cmd bluetooth_manager enable" else "svc bluetooth disable || cmd bluetooth_manager disable"
            val res = rootManager.execute(cmd)
            return ToolExecutionResult(res.success, if (on) "Bluetooth turned on" else "Bluetooth turned off")
        }
    }

    val setMobileDataTool = object : Tool {
        override val name = "set_mobile_data"
        override val description = "Turns cellular mobile data on or off."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("on" to mapOf("type" to "boolean", "description" to "true to turn on, false to turn off.")),
            "required" to listOf("on")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val on = args["on"] as? Boolean ?: return ToolExecutionResult(false, "Missing 'on' parameter")
            val cmd = if (on) "svc data enable" else "svc data disable"
            val res = rootManager.execute(cmd)
            return ToolExecutionResult(res.success, if (on) "Mobile data enabled" else "Mobile data disabled")
        }
    }

    val setAirplaneModeTool = object : Tool {
        override val name = "set_airplane_mode"
        override val description = "Turns airplane mode on or off."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("on" to mapOf("type" to "boolean", "description" to "true to turn on, false to turn off.")),
            "required" to listOf("on")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val on = args["on"] as? Boolean ?: return ToolExecutionResult(false, "Missing 'on' parameter")
            val cmd = if (on) {
                "cmd connectivity airplane-mode enable || (settings put global airplane_mode_on 1 && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true)"
            } else {
                "cmd connectivity airplane-mode disable || (settings put global airplane_mode_on 0 && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false)"
            }
            val res = rootManager.execute(cmd)
            return ToolExecutionResult(res.success, if (on) "Airplane mode turned on" else "Airplane mode turned off")
        }
    }

    val setFlashlightTool = object : Tool {
        override val name = "set_flashlight"
        override val description = "Turns the camera flashlight/torch on or off."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("on" to mapOf("type" to "boolean", "description" to "true to turn on, false to turn off.")),
            "required" to listOf("on")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val on = args["on"] as? Boolean ?: return ToolExecutionResult(false, "Missing 'on' parameter")
            return try {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = cameraManager.cameraIdList.firstOrNull() ?: "0"
                cameraManager.setTorchMode(cameraId, on)
                ToolExecutionResult(true, if (on) "Flashlight turned on" else "Flashlight turned off")
            } catch (e: Exception) {
                val cmd = if (on) "echo 255 > /sys/class/leds/flashlight/brightness" else "echo 0 > /sys/class/leds/flashlight/brightness"
                val res = rootManager.execute(cmd)
                ToolExecutionResult(res.success, if (on) "Flashlight enabled" else "Flashlight disabled")
            }
        }
    }

    val setHotspotTool = object : Tool {
        override val name = "set_hotspot"
        override val description = "Turns portable Wi-Fi hotspot on or off."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("on" to mapOf("type" to "boolean", "description" to "true for on, false for off.")),
            "required" to listOf("on")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val on = args["on"] as? Boolean ?: return ToolExecutionResult(false, "Missing 'on' parameter")
            val cmd = if (on) "cmd connectivity start-tethering wifi" else "cmd connectivity stop-tethering wifi"
            val res = rootManager.execute(cmd)
            return ToolExecutionResult(res.success, if (on) "Hotspot enabled" else "Hotspot disabled")
        }
    }

    val setLocationTool = object : Tool {
        override val name = "set_location"
        override val description = "Turns GPS / location services on or off."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("on" to mapOf("type" to "boolean", "description" to "true for on, false for off.")),
            "required" to listOf("on")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val on = args["on"] as? Boolean ?: return ToolExecutionResult(false, "Missing 'on' parameter")
            val mode = if (on) 3 else 0
            val res = rootManager.execute("settings put secure location_mode $mode")
            return ToolExecutionResult(res.success, if (on) "Location turned on" else "Location turned off")
        }
    }

    val setDoNotDisturbTool = object : Tool {
        override val name = "set_do_not_disturb"
        override val description = "Sets Do Not Disturb mode ('on', 'off', or 'priority')."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("mode" to mapOf("type" to "string", "description" to "'on', 'off', or 'priority'.")),
            "required" to listOf("mode")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val mode = args["mode"]?.toString()?.lowercase(Locale.ROOT) ?: "off"
            val zenMode = when (mode) {
                "on", "alarms", "total" -> 2
                "priority" -> 1
                else -> 0
            }
            val res = rootManager.execute("settings put global zen_mode $zenMode")
            return ToolExecutionResult(res.success, "DND set to $mode")
        }
    }

    val setAutoRotateTool = object : Tool {
        override val name = "set_auto_rotate"
        override val description = "Toggles auto-rotate screen orientation."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("on" to mapOf("type" to "boolean", "description" to "true for on, false for off.")),
            "required" to listOf("on")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val on = args["on"] as? Boolean ?: return ToolExecutionResult(false, "Missing 'on' parameter")
            val res = rootManager.execute("settings put system accelerometer_rotation ${if (on) 1 else 0}")
            return ToolExecutionResult(res.success, if (on) "Auto-rotate turned on" else "Auto-rotate turned off")
        }
    }

    val setDarkModeTool = object : Tool {
        override val name = "set_dark_mode"
        override val description = "Toggles system dark mode theme ('yes', 'no', or 'auto')."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("mode" to mapOf("type" to "string", "description" to "'yes', 'no', or 'auto'.")),
            "required" to listOf("mode")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val mode = args["mode"]?.toString()?.lowercase(Locale.ROOT) ?: "yes"
            val res = rootManager.execute("cmd uimode night $mode")
            return ToolExecutionResult(res.success, "System dark mode set to $mode")
        }
    }

    val setBrightnessTool = object : Tool {
        override val name = "set_brightness"
        override val description = "Sets screen brightness percentage (0 to 100)."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("percent" to mapOf("type" to "integer", "description" to "0 to 100.")),
            "required" to listOf("percent")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val percent = (args["percent"] as? Number)?.toInt()?.coerceIn(0, 100) ?: return ToolExecutionResult(false, "Missing percent")
            val raw = (percent * 255 / 100).coerceIn(1, 255)
            val res = rootManager.execute("settings put system screen_brightness_mode 0 && settings put system screen_brightness $raw")
            return ToolExecutionResult(res.success, "Brightness set to $percent%")
        }
    }

    val setVolumeTool = object : Tool {
        override val name = "set_volume"
        override val description = "Sets volume percentage for stream (media, ring, alarm, notification, call)."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "stream" to mapOf("type" to "string", "description" to "Stream name: 'media', 'ring', 'alarm', 'call'."),
                "percent" to mapOf("type" to "integer", "description" to "0 to 100.")
            ),
            "required" to listOf("percent")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val stream = args["stream"]?.toString()?.lowercase(Locale.ROOT) ?: "media"
            val percent = (args["percent"] as? Number)?.toInt()?.coerceIn(0, 100) ?: return ToolExecutionResult(false, "Missing percent")
            val streamId = when (stream) {
                "ring" -> 2
                "call", "voice" -> 0
                "alarm" -> 4
                "notification" -> 5
                else -> 3 // media
            }
            val res = rootManager.execute("cmd media_session volume --stream $streamId --set ${(percent * 15 / 100).coerceIn(0, 15)}")
            return ToolExecutionResult(res.success, "Set $stream volume to $percent%")
        }
    }

    val setRingerModeTool = object : Tool {
        override val name = "set_ringer_mode"
        override val description = "Sets ringer mode ('normal', 'vibrate', 'silent')."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("mode" to mapOf("type" to "string", "description" to "'normal', 'vibrate', or 'silent'.")),
            "required" to listOf("mode")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val mode = args["mode"]?.toString()?.lowercase(Locale.ROOT) ?: "normal"
            val ringerVal = when (mode) {
                "silent" -> 0
                "vibrate" -> 1
                else -> 2
            }
            val res = rootManager.execute("settings put global mode_ringer $ringerVal")
            return ToolExecutionResult(res.success, "Ringer set to $mode")
        }
    }

    val getBatteryTool = object : Tool {
        override val name = "get_battery"
        override val description = "Gets current battery level and charging status."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val res = rootManager.execute("dumpsys battery | grep -E 'level|status|temperature'")
            return ToolExecutionResult(res.success, res.stdout.trim())
        }
    }

    val getStorageTool = object : Tool {
        override val name = "get_storage"
        override val description = "Gets device internal storage free/used space."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val res = rootManager.execute("df -h /data")
            return ToolExecutionResult(res.success, res.stdout.trim())
        }
    }

    val getRamTool = object : Tool {
        override val name = "get_ram"
        override val description = "Gets RAM memory usage."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val res = rootManager.execute("free -h || head -n 4 /proc/meminfo")
            return ToolExecutionResult(res.success, res.stdout.trim())
        }
    }

    val getNetworkInfoTool = object : Tool {
        override val name = "get_network_info"
        override val description = "Gets Wi-Fi SSID, IP address, and network connectivity state."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val res = rootManager.execute("ip addr show wlan0 || ip route")
            return ToolExecutionResult(res.success, res.stdout.trim())
        }
    }

    val getDeviceInfoTool = object : Tool {
        override val name = "get_device_info"
        override val description = "Gets device hardware model, Android version, and root status."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val model = rootManager.execute("getprop ro.product.model").stdout.trim()
            val androidVer = rootManager.execute("getprop ro.build.version.release").stdout.trim()
            val diag = rootManager.getDiagnosticInfo()
            return ToolExecutionResult(true, "Model: $model\nAndroid: $androidVer\nRoot: $diag")
        }
    }

    val takeScreenshotTool = object : Tool {
        override val name = "take_screenshot"
        override val description = "Captures a screenshot of the current screen."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "describe" to mapOf("type" to "boolean", "description" to "Whether to also summarize what is on screen.")
            )
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val res = rootManager.execute("screencap -p /sdcard/aura_screenshot.png")
            return if (res.success) {
                ToolExecutionResult(true, "Screenshot saved to /sdcard/aura_screenshot.png")
            } else {
                ToolExecutionResult(false, "Screenshot failed: ${res.stderr}")
            }
        }
    }

    val recordScreenTool = object : Tool {
        override val name = "record_screen"
        override val description = "Records the screen for a given duration in seconds."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("seconds" to mapOf("type" to "integer", "description" to "Duration in seconds (e.g. 5, 10).")),
            "required" to listOf("seconds")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val sec = (args["seconds"] as? Number)?.toInt()?.coerceIn(1, 60) ?: 10
            val res = rootManager.execute("screenrecord --time-limit $sec /sdcard/aura_screenrecord.mp4")
            return if (res.success) {
                ToolExecutionResult(true, "Screen recording saved to /sdcard/aura_screenrecord.mp4 ($sec seconds)")
            } else {
                ToolExecutionResult(false, "Recording failed: ${res.stderr}")
            }
        }
    }

    val rebootTool = object : Tool {
        override val name = "reboot"
        override val description = "Reboots the device into normal, recovery, bootloader, or soft reboot mode. Requires confirmation."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("mode" to mapOf("type" to "string", "description" to "'normal', 'recovery', 'bootloader', or 'soft'.")),
            "required" to listOf("mode")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val mode = args["mode"]?.toString()?.lowercase(Locale.ROOT) ?: "normal"
            val cmd = when (mode) {
                "recovery" -> "reboot recovery"
                "bootloader", "fastboot" -> "reboot bootloader"
                "soft" -> "setprop ctl.restart zygote"
                else -> "reboot"
            }
            rootManager.execute(cmd)
            return ToolExecutionResult(true, "Rebooting into $mode mode...")
        }
    }

    val shutdownTool = object : Tool {
        override val name = "shutdown"
        override val description = "Powers off / shuts down the device. Requires confirmation."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            rootManager.execute("reboot -p || poweroff")
            return ToolExecutionResult(true, "Shutting down device...")
        }
    }

    fun getAllTools(): List<Tool> = listOf(
        setWifiTool, setBluetoothTool, setMobileDataTool, setAirplaneModeTool,
        setFlashlightTool, setHotspotTool, setLocationTool, setDoNotDisturbTool,
        setAutoRotateTool, setDarkModeTool, setBrightnessTool, setVolumeTool,
        setRingerModeTool, getBatteryTool, getStorageTool, getRamTool,
        getNetworkInfoTool, getDeviceInfoTool, takeScreenshotTool, recordScreenTool,
        rebootTool, shutdownTool
    )
}
