package com.example

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.example.core.lifecycle.AppForegroundState
import com.example.core.live.LiveDiagnostics
import com.example.ui.overlay.OverlayManager
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AuraApplication : Application(), Application.ActivityLifecycleCallbacks {

    val liveDiagnostics = LiveDiagnostics()

    lateinit var overlayManager: OverlayManager
        private set

    companion object {
        private const val TAG = "Aura"
        lateinit var instance: AuraApplication
            private set

        val isAppInForeground: Boolean
            get() = AppForegroundState.current
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        setupCrashHandler()
        registerActivityLifecycleCallbacks(this)

        // Pre-configure LibSU Shell once
        com.example.core.root.RootManager.ensureConfigured()

        overlayManager = OverlayManager(this)
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stackTrace = sw.toString()
                Log.e(TAG, "FATAL CRASH in thread ${thread.name}: $stackTrace")

                val crashFile = File(filesDir, "crash_logs.txt")
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val logEntry = "\n--- CRASH AT $timestamp [Thread: ${thread.name}] ---\n$stackTrace\n"

                // Keep log size bounded (max ~64KB)
                if (crashFile.exists() && crashFile.length() > 64 * 1024) {
                    val prev = crashFile.readText().takeLast(32 * 1024)
                    crashFile.writeText(prev + logEntry)
                } else {
                    crashFile.appendText(logEntry)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed writing crash log", e)
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    override fun onActivityStarted(activity: Activity) {
        AppForegroundState.onActivityStarted()
    }

    override fun onActivityStopped(activity: Activity) {
        AppForegroundState.onActivityStopped()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
