package com.example.core.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.example.BuildConfig
import com.example.core.agent.PersonaBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "aura_preferences")

class AuraPreferences(
    private val context: Context,
    private val encryptedKeyStorage: EncryptedKeyStorage = EncryptedKeyStorage(context)
) {

    companion object {
        private const val TAG = "Aura"

        val KEY_API_KEY = stringPreferencesKey("api_key")
        val KEY_MODEL_NAME = stringPreferencesKey("model_name")
        val KEY_VOICE_NAME = stringPreferencesKey("voice_name")
        val KEY_THINKING_LEVEL = stringPreferencesKey("thinking_level")
        val KEY_CACHED_LIVE_MODELS = stringSetPreferencesKey("cached_live_models")
        val KEY_UNSUPPORTED_SETUP_FIELDS = stringSetPreferencesKey("unsupported_setup_fields")

        // Language & Speech
        val KEY_DEFAULT_LANGUAGE = stringPreferencesKey("default_language")
        val KEY_GREET_ON_CONNECT = booleanPreferencesKey("greet_on_connect")
        val KEY_SHOW_CAPTIONS = booleanPreferencesKey("show_captions")

        // Interruption & Audio Processing
        val KEY_INTERRUPTION_SENSITIVITY = stringPreferencesKey("interruption_sensitivity")
        val KEY_ALLOW_INTERRUPTING = booleanPreferencesKey("allow_interrupting")
        val KEY_NOISE_GATE_STRENGTH = stringPreferencesKey("noise_gate_strength")
        val KEY_AUTO_HANGUP_SECONDS = intPreferencesKey("auto_hangup_seconds")

        // Wake Word
        val KEY_WAKE_PHRASE_ENABLED = booleanPreferencesKey("wake_phrase_enabled")
        val KEY_WAKE_WORD_ENGINE = stringPreferencesKey("wake_word_engine")
        val KEY_WAKE_WORD_SENSITIVITY = stringPreferencesKey("wake_word_sensitivity")

        // Overlays
        val KEY_EDGE_LIGHTING_ENABLED = booleanPreferencesKey("edge_lighting_enabled")
        val KEY_EDGE_LIGHTING_THICKNESS = intPreferencesKey("edge_lighting_thickness")
        val KEY_EDGE_LIGHTING_INTENSITY = floatPreferencesKey("edge_lighting_intensity")
        val KEY_OVERLAY_ENABLED = booleanPreferencesKey("overlay_enabled")

        // Persona & Owner
        val KEY_OWNER_NAME = stringPreferencesKey("owner_name")
        val KEY_EXTRA_INSTRUCTIONS = stringPreferencesKey("extra_instructions")
        val KEY_PERSONA_VERSION = intPreferencesKey("persona_version")
        val KEY_SYSTEM_INSTRUCTION = stringPreferencesKey("system_instruction")
        val KEY_INCLUDE_SYSTEM_APPS = booleanPreferencesKey("include_system_apps")

        // Existing General Settings
        val KEY_CONFIRM_RISKY_ACTIONS = booleanPreferencesKey("confirm_risky_actions")
        val KEY_MAX_AGENT_STEPS = intPreferencesKey("max_agent_steps")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val KEY_USE_SPEAKER = booleanPreferencesKey("use_speaker")
        val KEY_HEADPHONES_MODE = booleanPreferencesKey("headphones_mode")
        val KEY_DISABLED_TOOLS = stringSetPreferencesKey("disabled_tools")
        val KEY_WHATSAPP_BUSINESS = booleanPreferencesKey("whatsapp_business")
        val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")

        const val DEFAULT_MODEL = "gemini-3.8-live"
        const val DEFAULT_VOICE = "Aoede"
        const val DEFAULT_THINKING_LEVEL = "LOW"
        const val DEFAULT_LANGUAGE = "Hinglish"
        const val DEFAULT_OWNER_NAME = "Lucky, also known as Sohil"

        val INITIAL_LIVE_MODELS = listOf(
            "gemini-3.8-live",
            "gemini-3.8-live-extended-thinking"
        )

        val THINKING_LEVEL_OPTIONS = listOf("LOW", "MEDIUM", "HIGH")

        val LANGUAGE_OPTIONS = listOf(
            "Hinglish",
            "English (India)",
            "Hindi",
            "Gujarati",
            "Marathi",
            "Bengali",
            "Tamil",
            "Telugu",
            "Kannada",
            "Malayalam",
            "English (US)"
        )

        val INTERRUPTION_SENSITIVITY_OPTIONS = listOf("Low", "Medium", "High")
        val NOISE_GATE_OPTIONS = listOf("Off", "Low", "Medium", "High")
        val WAKE_SENSITIVITY_OPTIONS = listOf("normal", "strict", "loose")
    }

    suspend fun migrateIfNeeded() {
        try {
            context.dataStore.edit { prefs ->
                // 1. Migrate obsolete or discontinued models to gemini-3.8-live
                val currentModel = prefs[KEY_MODEL_NAME]
                if (currentModel.isNullOrBlank() ||
                    currentModel == "gemini-2.0-flash-exp" ||
                    currentModel == "gemini-live-2.5-flash-preview"
                ) {
                    prefs[KEY_MODEL_NAME] = DEFAULT_MODEL
                    Log.i(TAG, "Migrated model to $DEFAULT_MODEL")
                }

                // 2. Migrate API key from plain DataStore to EncryptedKeyStorage
                val legacyKey = prefs[KEY_API_KEY]
                if (!legacyKey.isNullOrBlank()) {
                    encryptedKeyStorage.setApiKey(legacyKey)
                    prefs.remove(KEY_API_KEY)
                    Log.i(TAG, "Migrated API key to EncryptedKeyStorage and cleared plain entry")
                }

                // 3. Persona migration: Migrate legacy full persona string to extraInstructions once
                val personaVersion = prefs[KEY_PERSONA_VERSION] ?: 0
                if (personaVersion < PersonaBuilder.CURRENT_PERSONA_VERSION) {
                    val legacyPersona = prefs[KEY_SYSTEM_INSTRUCTION]
                    if (!legacyPersona.isNullOrBlank()) {
                        // If it differs from the old hardcoded default, preserve as extra instructions
                        if (!legacyPersona.startsWith("You are Aura, a realtime voice agent for Android")) {
                            prefs[KEY_EXTRA_INSTRUCTIONS] = legacyPersona
                            Log.i(TAG, "Migrated custom system instruction to extraInstructions")
                        }
                        prefs.remove(KEY_SYSTEM_INSTRUCTION)
                    }
                    prefs[KEY_PERSONA_VERSION] = PersonaBuilder.CURRENT_PERSONA_VERSION
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during preferences migration: ${e.message}", e)
        }
    }

    val apiKey: Flow<String> = flow {
        val encrypted = encryptedKeyStorage.getApiKey()
        if (encrypted.isNotBlank()) {
            emit(encrypted)
        } else {
            val buildConfigKey = try {
                val field = BuildConfig::class.java.getField("GEMINI_API_KEY")
                val key = field.get(null) as? String
                if (!key.isNullOrBlank() && key != "MY_GEMINI_API_KEY") key else ""
            } catch (e: Exception) {
                ""
            }
            emit(buildConfigKey)
        }
    }

    val modelName: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        val model = it[KEY_MODEL_NAME]
        if (model.isNullOrBlank() || model == "gemini-2.0-flash-exp" || model == "gemini-live-2.5-flash-preview") {
            DEFAULT_MODEL
        } else {
            model
        }
    }

    val voiceName: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_VOICE_NAME] ?: DEFAULT_VOICE
    }

    val thinkingLevel: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        val level = it[KEY_THINKING_LEVEL] ?: DEFAULT_THINKING_LEVEL
        if (level.equals("MINIMAL", ignoreCase = true)) "LOW" else level
    }

    val defaultLanguage: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_DEFAULT_LANGUAGE] ?: DEFAULT_LANGUAGE
    }

    val greetOnConnect: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_GREET_ON_CONNECT] ?: true
    }

    val showCaptions: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_SHOW_CAPTIONS] ?: true
    }

    val interruptionSensitivity: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_INTERRUPTION_SENSITIVITY] ?: "Low"
    }

    val allowInterrupting: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_ALLOW_INTERRUPTING] ?: true
    }

    val noiseGateStrength: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_NOISE_GATE_STRENGTH] ?: "Medium"
    }

    val autoHangupSeconds: Flow<Int> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_AUTO_HANGUP_SECONDS] ?: 30
    }

    val wakePhraseEnabled: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_WAKE_PHRASE_ENABLED] ?: false
    }

    val wakeWordEngine: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_WAKE_WORD_ENGINE] ?: "Vosk"
    }

    val wakeWordSensitivity: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_WAKE_WORD_SENSITIVITY] ?: "normal"
    }

    val edgeLightingEnabled: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_EDGE_LIGHTING_ENABLED] ?: true
    }

    val edgeLightingThickness: Flow<Int> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_EDGE_LIGHTING_THICKNESS] ?: 8
    }

    val edgeLightingIntensity: Flow<Float> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_EDGE_LIGHTING_INTENSITY] ?: 1.0f
    }

    val ownerName: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_OWNER_NAME] ?: DEFAULT_OWNER_NAME
    }

    val extraInstructions: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_EXTRA_INSTRUCTIONS] ?: ""
    }

    val includeSystemApps: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_INCLUDE_SYSTEM_APPS] ?: true
    }

    val unsupportedSetupFields: Flow<Set<String>> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_UNSUPPORTED_SETUP_FIELDS] ?: emptySet()
    }

    val cachedLiveModels: Flow<Set<String>> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_CACHED_LIVE_MODELS] ?: INITIAL_LIVE_MODELS.toSet()
    }

    val confirmRiskyActions: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_CONFIRM_RISKY_ACTIONS] ?: true
    }

    val maxAgentSteps: Flow<Int> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_MAX_AGENT_STEPS] ?: 25
    }

    val overlayEnabled: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_OVERLAY_ENABLED] ?: false
    }

    val themeMode: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_THEME_MODE] ?: "dark"
    }

    val hapticsEnabled: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_HAPTICS_ENABLED] ?: true
    }

    val useSpeaker: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_USE_SPEAKER] ?: true
    }

    val headphonesMode: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_HEADPHONES_MODE] ?: false
    }

    val disabledTools: Flow<Set<String>> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_DISABLED_TOOLS] ?: emptySet()
    }

    val isWhatsappBusiness: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_WHATSAPP_BUSINESS] ?: false
    }

    val isOnboardingCompleted: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_ONBOARDING_COMPLETED] ?: false
    }

    // Setters
    suspend fun setApiKey(key: String) {
        encryptedKeyStorage.setApiKey(key.trim())
        try {
            context.dataStore.edit { it.remove(KEY_API_KEY) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear legacy API key: ${e.message}", e)
        }
    }

    suspend fun setModelName(name: String) = context.dataStore.edit { it[KEY_MODEL_NAME] = name.trim() }
    suspend fun setVoiceName(voice: String) = context.dataStore.edit { it[KEY_VOICE_NAME] = voice.trim() }
    suspend fun setThinkingLevel(level: String) {
        val sanitized = if (level.equals("MINIMAL", ignoreCase = true)) "LOW" else level.uppercase()
        context.dataStore.edit { it[KEY_THINKING_LEVEL] = sanitized }
    }
    suspend fun setDefaultLanguage(lang: String) = context.dataStore.edit { it[KEY_DEFAULT_LANGUAGE] = lang }
    suspend fun setGreetOnConnect(enabled: Boolean) = context.dataStore.edit { it[KEY_GREET_ON_CONNECT] = enabled }
    suspend fun setShowCaptions(show: Boolean) = context.dataStore.edit { it[KEY_SHOW_CAPTIONS] = show }
    suspend fun setInterruptionSensitivity(sens: String) = context.dataStore.edit { it[KEY_INTERRUPTION_SENSITIVITY] = sens }
    suspend fun setAllowInterrupting(allow: Boolean) = context.dataStore.edit { it[KEY_ALLOW_INTERRUPTING] = allow }
    suspend fun setNoiseGateStrength(strength: String) = context.dataStore.edit { it[KEY_NOISE_GATE_STRENGTH] = strength }
    suspend fun setAutoHangupSeconds(sec: Int) = context.dataStore.edit { it[KEY_AUTO_HANGUP_SECONDS] = sec }
    suspend fun setWakePhraseEnabled(enabled: Boolean) = context.dataStore.edit { it[KEY_WAKE_PHRASE_ENABLED] = enabled }
    suspend fun setWakeWordEngine(engine: String) = context.dataStore.edit { it[KEY_WAKE_WORD_ENGINE] = engine }
    suspend fun setWakeWordSensitivity(sens: String) = context.dataStore.edit { it[KEY_WAKE_WORD_SENSITIVITY] = sens }
    suspend fun setEdgeLightingEnabled(enabled: Boolean) = context.dataStore.edit { it[KEY_EDGE_LIGHTING_ENABLED] = enabled }
    suspend fun setEdgeLightingThickness(thickness: Int) = context.dataStore.edit { it[KEY_EDGE_LIGHTING_THICKNESS] = thickness }
    suspend fun setEdgeLightingIntensity(intensity: Float) = context.dataStore.edit { it[KEY_EDGE_LIGHTING_INTENSITY] = intensity }
    suspend fun setOwnerName(name: String) = context.dataStore.edit { it[KEY_OWNER_NAME] = name }
    suspend fun setExtraInstructions(instructions: String) = context.dataStore.edit { it[KEY_EXTRA_INSTRUCTIONS] = instructions }
    suspend fun setIncludeSystemApps(include: Boolean) = context.dataStore.edit { it[KEY_INCLUDE_SYSTEM_APPS] = include }

    suspend fun recordUnsupportedSetupField(fieldKey: String) {
        context.dataStore.edit { prefs ->
            val cur = prefs[KEY_UNSUPPORTED_SETUP_FIELDS]?.toMutableSet() ?: mutableSetOf()
            cur.add(fieldKey)
            prefs[KEY_UNSUPPORTED_SETUP_FIELDS] = cur
        }
    }

    suspend fun resetExtraInstructions() {
        context.dataStore.edit { it.remove(KEY_EXTRA_INSTRUCTIONS) }
    }

    suspend fun setCachedLiveModels(models: Set<String>) = context.dataStore.edit { it[KEY_CACHED_LIVE_MODELS] = models }
    suspend fun setConfirmRiskyActions(enabled: Boolean) = context.dataStore.edit { it[KEY_CONFIRM_RISKY_ACTIONS] = enabled }
    suspend fun setMaxAgentSteps(steps: Int) = context.dataStore.edit { it[KEY_MAX_AGENT_STEPS] = steps }
    suspend fun setOverlayEnabled(enabled: Boolean) = context.dataStore.edit { it[KEY_OVERLAY_ENABLED] = enabled }
    suspend fun setThemeMode(mode: String) = context.dataStore.edit { it[KEY_THEME_MODE] = mode }
    suspend fun setHapticsEnabled(enabled: Boolean) = context.dataStore.edit { it[KEY_HAPTICS_ENABLED] = enabled }
    suspend fun setUseSpeaker(use: Boolean) = context.dataStore.edit { it[KEY_USE_SPEAKER] = use }
    suspend fun setHeadphonesMode(enabled: Boolean) = context.dataStore.edit { it[KEY_HEADPHONES_MODE] = enabled }
    suspend fun setWhatsappBusiness(enabled: Boolean) = context.dataStore.edit { it[KEY_WHATSAPP_BUSINESS] = enabled }
    suspend fun setOnboardingCompleted(completed: Boolean) = context.dataStore.edit { it[KEY_ONBOARDING_COMPLETED] = completed }

    suspend fun setToolEnabled(toolName: String, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_DISABLED_TOOLS]?.toMutableSet() ?: mutableSetOf()
            if (enabled) current.remove(toolName) else current.add(toolName)
            prefs[KEY_DISABLED_TOOLS] = current
        }
    }

    private suspend fun FlowCollector<Preferences>.emitDefaults(throwable: Throwable) {
        if (throwable is IOException) {
            emit(emptyPreferences())
        } else {
            throw throwable
        }
    }
}
