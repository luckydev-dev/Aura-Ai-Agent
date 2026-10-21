package com.example.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.core.agent.AgentLoop
import com.example.core.audio.AudioRecordStreamer
import com.example.core.audio.AudioTrackPlayer
import com.example.core.live.GeminiLiveClient
import com.example.core.live.LiveConnectionState
import com.example.core.memory.MemoryRepository
import com.example.core.root.RootManager
import com.example.core.settings.AuraPreferences
import com.example.core.tools.ToolRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

class AuraForegroundService : Service() {

    companion object {
        private const val TAG = "Aura"

        const val ACTION_START_CALL = "com.example.aura.START_CALL"
        const val ACTION_END_CALL = "com.example.aura.END_CALL"
        const val ACTION_TOGGLE_MUTE = "com.example.aura.TOGGLE_MUTE"
        const val ACTION_TOGGLE_SPEAKER = "com.example.aura.TOGGLE_SPEAKER"

        const val CHANNEL_ID = "aura_live_call_channel"
        const val NOTIFICATION_ID = 1001

        private val _isCallActive = MutableStateFlow(false)
        val isCallActive: StateFlow<Boolean> = _isCallActive.asStateFlow()

        var currentService: AuraForegroundService? = null
            private set

        fun startCall(context: Context) {
            val intent = Intent(context, AuraForegroundService::class.java).apply {
                action = ACTION_START_CALL
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun endCall(context: Context) {
            val intent = Intent(context, AuraForegroundService::class.java).apply {
                action = ACTION_END_CALL
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    lateinit var preferences: AuraPreferences
    lateinit var memoryRepository: MemoryRepository
    lateinit var rootManager: RootManager
    lateinit var toolRegistry: ToolRegistry
    lateinit var audioStreamer: AudioRecordStreamer
    lateinit var audioPlayer: AudioTrackPlayer
    lateinit var liveClient: GeminiLiveClient
    lateinit var agentLoop: AgentLoop

    override fun onCreate() {
        super.onCreate()
        currentService = this

        preferences = AuraPreferences(this)
        memoryRepository = MemoryRepository(this)
        rootManager = RootManager(this)
        agentLoop = AgentLoop(rootManager)
        toolRegistry = ToolRegistry(this, rootManager, preferences, memoryRepository, agentLoop)
        audioPlayer = AudioTrackPlayer(this)

        audioStreamer = AudioRecordStreamer { base64Chunk ->
            liveClient.sendAudioChunk(base64Chunk)
        }

        liveClient = GeminiLiveClient(toolRegistry, audioPlayer)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Aura:CallWakeLock").apply {
            setReferenceCounted(false)
        }

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CALL -> {
                startForegroundNotification()
                initiateCall()
            }
            ACTION_END_CALL -> {
                terminateCall()
            }
            ACTION_TOGGLE_MUTE -> {
                val current = audioStreamer.isMuted.value
                audioStreamer.setMuted(!current)
                updateNotification()
            }
            ACTION_TOGGLE_SPEAKER -> {
                serviceScope.launch {
                    val current = preferences.useSpeaker.first()
                    preferences.setUseSpeaker(!current)
                    audioPlayer.setSpeakerphoneOn(!current)
                    updateNotification()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun requestVoiceAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { focusChange ->
                        Log.d(TAG, "Voice audio focus changed: $focusChange")
                    }
                    .build()

                audioFocusRequest = focusRequest
                val result = audioManager.requestAudioFocus(focusRequest)
                Log.i(TAG, "Requested USAGE_VOICE_COMMUNICATION audio focus: result=$result")
            } else {
                @Suppress("DEPRECATION")
                val result = audioManager.requestAudioFocus(
                    { focusChange -> Log.d(TAG, "Voice audio focus changed: $focusChange") },
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
                Log.i(TAG, "Requested legacy voice audio focus: result=$result")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request voice audio focus", e)
        }
    }

    private fun abandonVoiceAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
            Log.i(TAG, "Abandoned voice audio focus")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to abandon voice audio focus", e)
        }
    }

    private fun initiateCall() {
        if (_isCallActive.value) return
        _isCallActive.value = true
        wakeLock?.acquire(3 * 3600 * 1000L) // 3h max

        requestVoiceAudioFocus()

        serviceScope.launch {
            val apiKey = preferences.apiKey.first()
            val model = preferences.modelName.first()
            val voice = preferences.voiceName.first()
            val thinkingLevel = preferences.thinkingLevel.first()
            val basePersona = preferences.systemInstruction.first()
            val memoryContext = memoryRepository.buildMemoryContextSummary()
            val fullPrompt = if (memoryContext.isNotBlank()) "$basePersona\n\n$memoryContext" else basePersona

            val useSpeaker = preferences.useSpeaker.first()
            audioPlayer.setSpeakerphoneOn(useSpeaker)

            audioPlayer.start(serviceScope)
            audioStreamer.start(serviceScope)

            // Listen for errors to release mic/speaker/audio focus and reset active call state
            launch {
                liveClient.connectionState.collect { state ->
                    if (state is LiveConnectionState.Error) {
                        _isCallActive.value = false
                        audioStreamer.stop()
                        audioPlayer.stop()
                        abandonVoiceAudioFocus()
                    }
                }
            }

            liveClient.connect(serviceScope, apiKey, model, voice, fullPrompt, thinkingLevel)
        }
    }

    private fun terminateCall() {
        _isCallActive.value = false
        liveClient.disconnect()
        audioStreamer.stop()
        audioPlayer.stop()
        abandonVoiceAudioFocus()

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wakeLock", e)
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Aura Live Call",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows status of active voice session"
                setSound(null, null)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val endCallIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AuraForegroundService::class.java).apply { action = ACTION_END_CALL },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val muteIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, AuraForegroundService::class.java).apply { action = ACTION_TOGGLE_MUTE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val isMuted = if (::audioStreamer.isInitialized) audioStreamer.isMuted.value else false

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle("Aura Live Voice")
            .setContentText(if (isMuted) "Microphone Muted" else "Connected — Listening & Ready")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(0, if (isMuted) "Unmute" else "Mute", muteIntent)
            .addAction(0, "End Call", endCallIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        super.onDestroy()
        currentService = null
        serviceScope.cancel()
        terminateCall()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
