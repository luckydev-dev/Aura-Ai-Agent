package com.example.core.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.example.core.root.RootManager
import java.util.Locale

class AppTools(
    private val context: Context,
    private val rootManager: RootManager
) {

    private fun findPackage(nameOrPackage: String): String? {
        val pm = context.packageManager
        val query = nameOrPackage.trim().lowercase(Locale.ROOT)
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        // Exact package name match
        for (app in installed) {
            if (app.packageName.lowercase(Locale.ROOT) == query) return app.packageName
        }

        // Fuzzy match on application label
        for (app in installed) {
            val label = pm.getApplicationLabel(app).toString().lowercase(Locale.ROOT)
            if (label == query || label.contains(query)) {
                return app.packageName
            }
        }
        return null
    }

    val openAppTool = object : Tool {
        override val name = "open_app"
        override val description = "Opens an installed Android app by its name or package identifier."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "name_or_package" to mapOf(
                    "type" to "string",
                    "description" to "The name of the app (e.g. 'WhatsApp', 'Chrome') or its package name."
                )
            ),
            "required" to listOf("name_or_package")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val query = args["name_or_package"]?.toString() ?: return ToolExecutionResult(false, "Missing app name")
            val pkg = findPackage(query)
            if (pkg == null) {
                return ToolExecutionResult(false, "Could not find app matching '$query'")
            }

            // Launch via PackageManager intent or am start
            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            return if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                ToolExecutionResult(true, "Opened $pkg successfully")
            } else {
                val res = rootManager.execute("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
                if (res.success) {
                    ToolExecutionResult(true, "Started $pkg")
                } else {
                    ToolExecutionResult(false, "Failed to start $pkg: ${res.stderr}")
                }
            }
        }
    }

    val closeAppTool = object : Tool {
        override val name = "close_app"
        override val description = "Closes or force-stops an app by its name or package."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "name" to mapOf("type" to "string", "description" to "The name or package of the app to close.")
            ),
            "required" to listOf("name")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val name = args["name"]?.toString() ?: return ToolExecutionResult(false, "Missing app name")
            val pkg = findPackage(name) ?: name
            val res = rootManager.execute("am force-stop $pkg")
            return if (res.success) {
                ToolExecutionResult(true, "Force stopped $pkg")
            } else {
                ToolExecutionResult(false, "Failed to stop $pkg: ${res.stderr}")
            }
        }
    }

    val clearAppDataTool = object : Tool {
        override val name = "clear_app_data"
        override val description = "Clears all data and cache of an app (requires user confirmation)."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "name" to mapOf("type" to "string", "description" to "App name or package to clear.")
            ),
            "required" to listOf("name")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val name = args["name"]?.toString() ?: return ToolExecutionResult(false, "Missing app name")
            val pkg = findPackage(name) ?: name
            val res = rootManager.execute("pm clear $pkg")
            return if (res.success) {
                ToolExecutionResult(true, "Cleared all data for $pkg")
            } else {
                ToolExecutionResult(false, "Failed to clear data: ${res.stderr}")
            }
        }
    }

    val clearAppCacheTool = object : Tool {
        override val name = "clear_app_cache"
        override val description = "Clears cache directory of an app."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "name" to mapOf("type" to "string", "description" to "App name or package.")
            ),
            "required" to listOf("name")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val name = args["name"]?.toString() ?: return ToolExecutionResult(false, "Missing app name")
            val pkg = findPackage(name) ?: name
            val res = rootManager.execute("pm trim-caches 999G && rm -rf /data/data/$pkg/cache/*")
            return if (res.success) {
                ToolExecutionResult(true, "Cleared cache for $pkg")
            } else {
                ToolExecutionResult(false, "Failed to clear cache: ${res.stderr}")
            }
        }
    }

    val listInstalledAppsTool = object : Tool {
        override val name = "list_installed_apps"
        override val description = "Lists installed apps on the device, optionally filtered."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "filter" to mapOf("type" to "string", "description" to "Optional search filter.")
            )
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val filter = args["filter"]?.toString()?.lowercase(Locale.ROOT) ?: ""
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .map { app ->
                    val label = pm.getApplicationLabel(app).toString()
                    "$label (${app.packageName})"
                }
                .filter { if (filter.isBlank()) true else it.lowercase(Locale.ROOT).contains(filter) }
                .take(40)

            return ToolExecutionResult(true, apps.joinToString("\n"))
        }
    }

    val getForegroundAppTool = object : Tool {
        override val name = "get_foreground_app"
        override val description = "Gets the currently active/focused app on screen."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val res = rootManager.execute("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
            return if (res.success && res.stdout.isNotBlank()) {
                ToolExecutionResult(true, res.stdout.trim())
            } else {
                ToolExecutionResult(false, "Could not determine foreground app")
            }
        }
    }

    val goHomeTool = object : Tool {
        override val name = "go_home"
        override val description = "Navigates to the Android home screen."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val res = rootManager.execute("input keyevent 3")
            return ToolExecutionResult(res.success, if (res.success) "Navigated to home" else res.stderr)
        }
    }

    val goBackTool = object : Tool {
        override val name = "go_back"
        override val description = "Performs back navigation button press."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val res = rootManager.execute("input keyevent 4")
            return ToolExecutionResult(res.success, if (res.success) "Went back" else res.stderr)
        }
    }

    val openRecentsTool = object : Tool {
        override val name = "open_recents"
        override val description = "Opens the Android app switcher / recents menu."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val res = rootManager.execute("input keyevent 187")
            return ToolExecutionResult(res.success, if (res.success) "Opened recents" else res.stderr)
        }
    }

    val openNotificationsTool = object : Tool {
        override val name = "open_notifications"
        override val description = "Pulls down the notification shade."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val res = rootManager.execute("cmd statusbar expand-notifications")
            return ToolExecutionResult(res.success, if (res.success) "Opened notifications" else res.stderr)
        }
    }

    val openQuickSettingsTool = object : Tool {
        override val name = "open_quick_settings"
        override val description = "Pulls down the quick settings panel."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val res = rootManager.execute("cmd statusbar expand-settings")
            return ToolExecutionResult(res.success, if (res.success) "Opened quick settings" else res.stderr)
        }
    }

    val lockScreenTool = object : Tool {
        override val name = "lock_screen"
        override val description = "Locks the device screen."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val res = rootManager.execute("input keyevent 26")
            return ToolExecutionResult(res.success, if (res.success) "Screen locked" else res.stderr)
        }
    }

    val wakeScreenTool = object : Tool {
        override val name = "wake_screen"
        override val description = "Wakes up the device screen."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val res = rootManager.execute("input keyevent 224")
            return ToolExecutionResult(res.success, if (res.success) "Screen awakened" else res.stderr)
        }
    }

    fun getAllTools(): List<Tool> = listOf(
        openAppTool, closeAppTool, clearAppDataTool, clearAppCacheTool,
        listInstalledAppsTool, getForegroundAppTool, goHomeTool, goBackTool,
        openRecentsTool, openNotificationsTool, openQuickSettingsTool,
        lockScreenTool, wakeScreenTool
    )
}
