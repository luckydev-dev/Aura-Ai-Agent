package com.example

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.example.core.live.LiveDiagnostics
import com.example.ui.overlay.OverlayManager
import com.topjohnwu.superuser.Shell

class AuraApplication : Application(), Application.ActivityLifecycleCallbacks {

    val liveDiagnostics = LiveDiagnostics()

    lateinit var overlayManager: OverlayManager
        private set

    companion object {
        lateinit var instance: AuraApplication
            private set

        var isAppInForeground: Boolean = false
            private set
    }

    private var runningActivities = 0

    override fun onCreate() {
        super.onCreate()
        instance = this

        registerActivityLifecycleCallbacks(this)

        // Pre-configure LibSU Shell
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(15)
        )

        overlayManager = OverlayManager(this)
    }

    override fun onActivityStarted(activity: Activity) {
        runningActivities++
        isAppInForeground = runningActivities > 0
    }

    override fun onActivityStopped(activity: Activity) {
        runningActivities--
        isAppInForeground = runningActivities > 0
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
