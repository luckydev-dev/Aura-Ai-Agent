package com.example.core.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class EncryptedKeyStorage(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "aura_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e("Aura", "EncryptedSharedPreferences initialization failed; falling back to private prefs", e)
            context.getSharedPreferences("aura_secure_fallback", Context.MODE_PRIVATE)
        }
    }

    fun getApiKey(): String {
        return prefs.getString(KEY_API_KEY, "") ?: ""
    }

    fun setApiKey(key: String) {
        prefs.edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    fun clearApiKey() {
        prefs.edit().remove(KEY_API_KEY).apply()
    }

    fun getApiKeyPoolJson(): String {
        return prefs.getString(KEY_API_KEY_POOL, "") ?: ""
    }

    fun setApiKeyPoolJson(json: String) {
        prefs.edit().putString(KEY_API_KEY_POOL, json).apply()
    }

    companion object {
        private const val KEY_API_KEY = "encrypted_gemini_api_key"
        private const val KEY_API_KEY_POOL = "encrypted_gemini_api_key_pool"
    }
}
