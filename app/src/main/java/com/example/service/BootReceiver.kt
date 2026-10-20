package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.core.settings.AuraPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = AuraPreferences(context)
            CoroutineScope(Dispatchers.IO).launch {
                val wakeEnabled = prefs.wakePhraseEnabled.first()
                if (wakeEnabled) {
                    AuraForegroundService.startCall(context)
                }
            }
        }
    }
}
