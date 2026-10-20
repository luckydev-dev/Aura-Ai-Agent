package com.example.core.memory

import android.content.Context
import com.example.core.memory.database.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class MemoryRepository(context: Context) {
    private val database = AuraDatabase.getInstance(context)
    private val conversationDao = database.conversationDao()
    private val actionLogDao = database.actionLogDao()
    private val userFactDao = database.userFactDao()

    fun getRecentConversations(limit: Int = 100): Flow<List<ConversationEntity>> =
        conversationDao.getRecent(limit)

    suspend fun logConversation(speaker: String, text: String, audioDurationSec: Float = 0f) {
        if (text.isBlank()) return
        conversationDao.insert(
            ConversationEntity(
                speaker = speaker,
                text = text.trim(),
                audioDurationSec = audioDurationSec
            )
        )
    }

    suspend fun clearConversations() = conversationDao.clearAll()
    suspend fun deleteConversation(id: Long) = conversationDao.deleteById(id)

    fun getActionLogs(limit: Int = 100): Flow<List<ActionLogEntity>> =
        actionLogDao.getRecentLogs(limit)

    fun searchLogs(query: String): Flow<List<ActionLogEntity>> =
        actionLogDao.searchLogs(query)

    suspend fun logAction(
        toolName: String,
        argsJson: String,
        output: String,
        success: Boolean,
        durationMs: Long
    ) {
        actionLogDao.insert(
            ActionLogEntity(
                toolName = toolName,
                argsJson = argsJson,
                output = output,
                success = success,
                durationMs = durationMs
            )
        )
    }

    suspend fun clearActionLogs() = actionLogDao.clearAll()
    suspend fun deleteActionLog(id: Long) = actionLogDao.deleteById(id)

    fun getAllFacts(): Flow<List<UserFactEntity>> = userFactDao.getAll()

    suspend fun saveFact(key: String, value: String, category: String = "fact") {
        userFactDao.insert(
            UserFactEntity(
                key = key.trim(),
                value = value.trim(),
                category = category.trim()
            )
        )
    }

    suspend fun findFact(key: String): String? {
        return userFactDao.findByKey(key.trim())?.value
    }

    suspend fun deleteFact(id: Long) = userFactDao.deleteById(id)
    suspend fun clearAllFacts() = userFactDao.clearAll()

    /**
     * Builds a compact string of user facts & aliases to inject into the Gemini system prompt.
     */
    suspend fun buildMemoryContextSummary(): String {
        return try {
            val facts = userFactDao.getAll().first()
            if (facts.isEmpty()) return ""
            val sb = StringBuilder("User Known Facts & Aliases:\n")
            facts.forEach { fact ->
                sb.append("- [${fact.category}] ${fact.key}: ${fact.value}\n")
            }
            sb.toString()
        } catch (_: Exception) {
            ""
        }
    }
}
