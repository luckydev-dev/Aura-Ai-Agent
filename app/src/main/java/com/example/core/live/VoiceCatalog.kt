package com.example.core.live

data class VoiceInfo(
    val name: String,
    val gender: String, // Female, Male, Neutral
    val styleNote: String
)

object VoiceCatalog {

    val VOICES: List<VoiceInfo> = listOf(
        VoiceInfo("Aoede", "Female", "Warm, bright, conversational (Default)"),
        VoiceInfo("Puck", "Male", "Playful, energetic, quick-witted"),
        VoiceInfo("Charon", "Male", "Deep, calm, measured, resonant"),
        VoiceInfo("Kore", "Female", "Gentle, soothing, soft-spoken"),
        VoiceInfo("Fenrir", "Male", "Crisp, bold, assertive"),
        VoiceInfo("Leda", "Female", "Clear, polished, friendly"),
        VoiceInfo("Orus", "Male", "Confident, grounded, natural"),
        VoiceInfo("Zephyr", "Female", "Light, breezy, cheerful"),
        VoiceInfo("Callirrhoe", "Female", "Expressive, melodic, warm"),
        VoiceInfo("Autonoe", "Female", "Steady, articulate, thoughtful"),
        VoiceInfo("Enceladus", "Male", "Rich, baritone, authoritative"),
        VoiceInfo("Iapetus", "Male", "Warm, mature, steady"),
        VoiceInfo("Umbriel", "Neutral", "Balanced, quiet, relaxed"),
        VoiceInfo("Algieba", "Female", "Bright, enthusiastic, crisp"),
        VoiceInfo("Despina", "Female", "Friendly, animated, vivid"),
        VoiceInfo("Erinome", "Female", "Warm, inviting, rhythmic"),
        VoiceInfo("Algenib", "Male", "Dynamic, clear, modern"),
        VoiceInfo("Rasalgethi", "Male", "Smooth, grounded, conversational"),
        VoiceInfo("Laomedeia", "Female", "Soft, lyrical, gentle"),
        VoiceInfo("Achernar", "Male", "Direct, brisk, engaging"),
        VoiceInfo("Alnilam", "Male", "Crisp, rhythmic, focused"),
        VoiceInfo("Schedar", "Female", "Expressive, clear, warm"),
        VoiceInfo("Gacrux", "Male", "Deep, steady, reassuring"),
        VoiceInfo("Pulcherrima", "Female", "Vibrant, cheerful, bright"),
        VoiceInfo("Achird", "Male", "Approachable, natural, relaxed"),
        VoiceInfo("Zubenelgenubi", "Male", "Resonant, deliberate, calm"),
        VoiceInfo("Vindemiatrix", "Female", "Professional, warm, clear"),
        VoiceInfo("Sadachbia", "Female", "Playful, light, casual"),
        VoiceInfo("Sadaltager", "Male", "Warm baritone, gentle"),
        VoiceInfo("Sulafat", "Female", "Polished, animated, friendly")
    )

    private val voiceMap = VOICES.associateBy { it.name.lowercase() }

    fun findVoice(nameOrQuery: String): VoiceInfo? {
        val q = nameOrQuery.trim().lowercase()
        return voiceMap[q] ?: VOICES.firstOrNull {
            it.name.lowercase().contains(q) ||
            it.styleNote.lowercase().contains(q) ||
            it.gender.lowercase() == q
        }
    }

    fun getRandomVoice(): VoiceInfo {
        return VOICES.random()
    }

    fun getVoiceNames(): List<String> = VOICES.map { it.name }
}
