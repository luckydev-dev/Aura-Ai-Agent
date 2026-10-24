package com.example.core.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.SmsManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import com.example.core.root.RootManager
import com.example.core.settings.AuraPreferences
import com.example.service.AuraForegroundService
import com.example.service.AuraNotificationListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class CallAnnouncementManager(
    private val context: Context,
    private val preferences: AuraPreferences,
    private val rootManager: RootManager
) {
    companion object {
        private const val TAG = "Aura"
        var instance: CallAnnouncementManager? = null
            private set
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var telephonyCallback: Any? = null
    private var phoneStateListener: PhoneStateListener? = null

    var isRinging = false
        private set
    var activeCallerName: String? = null
        private set
    var activeCallerNumber: String? = null
        private set
    var isVoipCall: Boolean = false
        private set

    init {
        instance = this
        registerTelephonyListener()
    }

    private fun registerTelephonyListener() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleCallState(state, null)
                    }
                }
                telephonyManager.registerTelephonyCallback(context.mainExecutor, callback)
                telephonyCallback = callback
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleCallState(state, phoneNumber)
                    }
                }
                @Suppress("DEPRECATION")
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                phoneStateListener = listener
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering telephony listener: ${e.message}", e)
        }
    }

    private fun handleCallState(state: Int, number: String?) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                isRinging = true
                isVoipCall = false
                val callerNumber = number ?: ""
                val contactName = resolveCallerName(callerNumber)
                activeCallerName = contactName
                activeCallerNumber = callerNumber

                scope.launch {
                    val enabled = preferences.callAnnouncementEnabled.first()
                    if (enabled) {
                        announceIncomingCall(contactName ?: callerNumber.ifBlank { "Unknown Caller" }, isVoip = false)
                    }
                }
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (isRinging) {
                    isRinging = false
                    releaseMicrophoneForCall()
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                isRinging = false
                activeCallerName = null
                activeCallerNumber = null
            }
        }
    }

    fun onVoipCallDetected(appName: String, caller: String) {
        scope.launch {
            val enabled = preferences.callAnnouncementEnabled.first()
            val allowWhatsapp = preferences.callAnnounceWhatsapp.first()
            if (enabled && allowWhatsapp) {
                isRinging = true
                isVoipCall = true
                activeCallerName = caller
                activeCallerNumber = ""
                announceIncomingCall(caller, isVoip = true, appName = appName)
            }
        }
    }

    private fun announceIncomingCall(caller: String, isVoip: Boolean, appName: String? = null) {
        scope.launch {
            try {
                // Duck ringer stream to 5% as requested so ring is soft while announcement is clear
                val maxRing = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
                val softRing = (maxRing * 0.05f).toInt().coerceAtLeast(1)
                audioManager.setStreamVolume(AudioManager.STREAM_RING, softRing, 0)
            } catch (e: Exception) {
                Log.e(TAG, "Failed setting soft ringer volume: ${e.message}")
            }

            val owner = preferences.ownerName.first().ifBlank { "Boss" }
            val announcement = if (isVoip) {
                "$owner, ${appName ?: "WhatsApp"} par $caller ka call aa raha hai. Uthau ya kaat du?"
            } else {
                "$owner, $caller ka phone aa raha hai. Uthau ya kaat du?"
            }

            Log.i(TAG, "Incoming call announcement: $announcement")

            AuraForegroundService.currentService?.let { service ->
                service.liveClient.sendTextMessage("[SYSTEM EVENT: Incoming Call from $caller. Say immediately: \"$announcement\"]")
            }
        }
    }

    suspend fun handleVoiceCallDecision(command: String): Boolean {
        val lower = command.lowercase().trim()
        return when {
            lower.contains("utha") || lower.contains("answer") || lower.contains("receive") || lower.contains("pick up") || lower.contains("haan utha") -> {
                answerCall()
                true
            }
            lower.contains("kaat") || lower.contains("reject") || lower.contains("decline") || lower.contains("cut") -> {
                rejectCall()
                true
            }
            lower.contains("silent") || lower.contains("chup") || lower.contains("mute") -> {
                silenceRinger()
                true
            }
            lower.contains("message") || lower.contains("bol do") -> {
                val msg = command.substringAfter("message", "").ifBlank { "I'll call you later." }
                rejectCallWithMessage(msg.trim())
                true
            }
            else -> false
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun answerCall(): Boolean {
        Log.i(TAG, "Answering incoming call...")
        var answered = false

        if (isVoipCall) {
            val notifs = AuraNotificationListener.getActiveNotificationsCached()
            val callNotif = notifs.firstOrNull {
                it.sbn.notification.category == android.app.Notification.CATEGORY_CALL ||
                it.title.contains("call", ignoreCase = true) ||
                it.text.contains("call", ignoreCase = true)
            }
            if (callNotif != null) {
                val actions = callNotif.sbn.notification.actions ?: emptyArray()
                val answerAction = actions.firstOrNull {
                    it.title.toString().contains("answer", ignoreCase = true) ||
                    it.title.toString().contains("accept", ignoreCase = true) ||
                    it.title.toString().contains("उठाएं")
                }
                if (answerAction != null) {
                    try {
                        answerAction.actionIntent.send()
                        answered = true
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed triggering answer notification action", e)
                    }
                }
            }
        }

        if (!answered && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                try {
                    telecomManager?.acceptRingingCall()
                    answered = true
                } catch (e: Exception) {
                    Log.e(TAG, "telecomManager.acceptRingingCall failed: ${e.message}", e)
                }
            }
        }

        if (!answered) {
            val res = rootManager.execute("input keyevent 5")
            answered = res.success
        }

        releaseMicrophoneForCall()
        isRinging = false
        return answered
    }

    @SuppressLint("MissingPermission")
    suspend fun rejectCall(): Boolean {
        Log.i(TAG, "Rejecting incoming call...")
        var rejected = false

        if (isVoipCall) {
            val notifs = AuraNotificationListener.getActiveNotificationsCached()
            val callNotif = notifs.firstOrNull {
                it.sbn.notification.category == android.app.Notification.CATEGORY_CALL ||
                it.title.contains("call", ignoreCase = true)
            }
            if (callNotif != null) {
                val actions = callNotif.sbn.notification.actions ?: emptyArray()
                val declineAction = actions.firstOrNull {
                    it.title.toString().contains("decline", ignoreCase = true) ||
                    it.title.toString().contains("reject", ignoreCase = true) ||
                    it.title.toString().contains("काटें")
                }
                if (declineAction != null) {
                    try {
                        declineAction.actionIntent.send()
                        rejected = true
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed triggering reject notification action", e)
                    }
                }
            }
        }

        if (!rejected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                try {
                    telecomManager?.endCall()
                    rejected = true
                } catch (e: Exception) {
                    Log.e(TAG, "telecomManager.endCall failed: ${e.message}", e)
                }
            }
        }

        if (!rejected) {
            val res = rootManager.execute("input keyevent 6")
            rejected = res.success
        }

        isRinging = false
        return rejected
    }

    suspend fun rejectCallWithMessage(message: String): Boolean {
        val num = activeCallerNumber
        rejectCall()
        if (!num.isNullOrBlank()) {
            return try {
                val sms = SmsManager.getDefault()
                sms.sendTextMessage(num, null, message, null, null)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed sending SMS after call rejection", e)
                false
            }
        }
        return true
    }

    suspend fun silenceRinger(): Boolean {
        return try {
            audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
            true
        } catch (e: Exception) {
            val res = rootManager.execute("input keyevent 164")
            res.success
        }
    }

    private fun releaseMicrophoneForCall() {
        AuraForegroundService.currentService?.let { service ->
            service.audioStreamer.setMuted(true)
            service.audioPlayer.stop()
        }
    }

    @SuppressLint("Range")
    private fun resolveCallerName(phoneNumber: String): String? {
        if (phoneNumber.isBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
            val cursor: Cursor? = context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    it.getString(it.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME))
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up contact name: ${e.message}", e)
            null
        }
    }

    fun simulateIncomingCall(callerName: String, number: String) {
        isRinging = true
        isVoipCall = false
        activeCallerName = callerName
        activeCallerNumber = number
        announceIncomingCall(callerName, isVoip = false)
    }

    fun destroy() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyCallback?.let {
                    telephonyManager.unregisterTelephonyCallback(it as TelephonyCallback)
                }
            } else {
                phoneStateListener?.let {
                    @Suppress("DEPRECATION")
                    telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering telephony listener", e)
        }
        scope.cancel()
        instance = null
    }
}
