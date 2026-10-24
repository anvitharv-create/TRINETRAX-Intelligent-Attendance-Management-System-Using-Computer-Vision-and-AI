package com.trinetrax.attendance.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.trinetrax.attendance.data.model.UserDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/**
 * Stores the backend bearer token and the signed-in user in an encrypted
 * SharedPreferences file (AES-256). The token never touches plain prefs.
 */
class SessionManager(context: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "trinetrax_secure_session",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val _token = MutableStateFlow(prefs.getString(KEY_TOKEN, null))
    val token: StateFlow<String?> = _token

    private val _user = MutableStateFlow(readStoredUser())
    val user: StateFlow<UserDto?> = _user

    val currentUser: UserDto? get() = _user.value

    fun saveSession(tokenValue: String, user: UserDto) {
        prefs.edit().putString(KEY_TOKEN, tokenValue).putString(KEY_USER, json.encodeToString(UserDto.serializer(), user)).apply()
        _token.value = tokenValue
        _user.value = user
    }

    fun updateUser(user: UserDto) {
        prefs.edit().putString(KEY_USER, json.encodeToString(UserDto.serializer(), user)).apply()
        _user.value = user
    }

    fun clear() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_USER).apply()
        _token.value = null
        _user.value = null
    }

    private fun readStoredUser(): UserDto? {
        val raw = prefs.getString(KEY_USER, null) ?: return null
        return runCatching { json.decodeFromString(UserDto.serializer(), raw) }.getOrNull()
    }

    private companion object {
        const val KEY_TOKEN = "auth_token"
        const val KEY_USER = "auth_user"
    }
}
