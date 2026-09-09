package com.umbra.app.data.session

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Сессия T1: токен + карточка аккаунта, зашифрованные EncryptedSharedPreferences.
 * Signal-ключей больше нет — аккаунт подтверждается кодом из Telegram, история
 * и профиль подтягиваются из облака при входе с любого телефона.
 */
class SessionStore(context: Context, preferenceName: String = "umbra_session") {
    private val prefs: SharedPreferences = run {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, preferenceName, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun token(): String? = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
    fun userId(): String? = prefs.getString(KEY_USER_ID, null)?.takeIf { it.isNotBlank() }
    fun username(): String? = prefs.getString(KEY_USERNAME, null)?.takeIf { it.isNotBlank() }
    fun phone(): String? = prefs.getString(KEY_PHONE, null)?.takeIf { it.isNotBlank() }
    fun displayName(): String? = prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() }
    fun lastName(): String? = prefs.getString(KEY_LAST_NAME, null)?.takeIf { it.isNotBlank() }
    fun avatarMediaId(): String? = prefs.getString(KEY_AVATAR, null)?.takeIf { it.isNotBlank() }

    fun isLoggedIn(): Boolean = token() != null

    /** Профиль заполнен (есть имя) — после входа не показываем экран профиля. */
    fun profileComplete(): Boolean = !displayName().isNullOrBlank()

    fun save(token: String, userId: String, username: String, phone: String,
             displayName: String, lastName: String, avatarMediaId: String?) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USER_ID, userId)
            .putString(KEY_USERNAME, username)
            .putString(KEY_PHONE, phone)
            .putString(KEY_NAME, displayName)
            .putString(KEY_LAST_NAME, lastName)
            .putString(KEY_AVATAR, avatarMediaId.orEmpty())
            .apply()
    }

    fun saveAvatar(mediaId: String) {
        prefs.edit().putString(KEY_AVATAR, mediaId).apply()
    }

    fun saveProfile(username: String, displayName: String, lastName: String, avatarMediaId: String?) {
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_NAME, displayName)
            .putString(KEY_LAST_NAME, lastName)
            .putString(KEY_AVATAR, avatarMediaId.orEmpty())
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /** Keep the cache owner so reauthentication does not discard the outbox. */
    fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    companion object {
        private const val KEY_TOKEN = "token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USERNAME = "username"
        private const val KEY_PHONE = "phone"
        private const val KEY_NAME = "name"
        private const val KEY_LAST_NAME = "last_name"
        private const val KEY_AVATAR = "avatar_media_id"
    }
}
