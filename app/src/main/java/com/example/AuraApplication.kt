package com.example

import android.app.Application
import com.example.ui.overlay.OverlayManager
import com.topjohnwu.superuser.Shell

class AuraApplication : Application() {

    val liveDiagnostics = com.example.core.live.LiveDiagnostics()

    lateinit var overlayManager: OverlayManager
        private set

    companion object {
        lateinit var instance: AuraApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Pre-configure LibSU Shell
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(15)
        )

        overlayManager = OverlayManager(this)
    }
}
