package com.umbra.app.crypto

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.ecc.ECKeyPair
import java.security.SecureRandom
import java.time.Instant

/** Один профиль на установку. Выход не уничтожает identity и историю. */
class SecurePrefs(context: Context, name: String = "umbra_secure") {
    private val prefs = EncryptedSharedPreferences.create(
        context, name,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun hasIdentity(): Boolean = (prefs.contains("ed25519_seed") || prefs.contains("ed25519_priv")) &&
        prefs.contains("x_identity_priv")

    @Synchronized
    fun ensureIdentity() {
        if (hasIdentity()) return
        check(!prefs.contains("ed25519_priv") && !prefs.contains("ed25519_seed") && !prefs.contains("x_identity_priv")) {
            "Часть ключей отсутствует. Существующие ключи не будут перезаписаны."
        }
        val ed = Ed25519PrivateKeyParameters(SecureRandom())
        val x = Curve.generateKeyPair()
        check(prefs.edit()
            .putString("ed25519_seed", b64(ed.encoded))
            .putString("x_identity_priv", b64(x.privateKey.serialize()))
            .putString("registration_id", (SecureRandom().nextInt(16380) + 1).toString())
            .commit()) { "Не удалось сохранить ключи" }
    }

    private fun edPrivate(): Ed25519PrivateKeyParameters {
        prefs.getString("ed25519_seed", null)?.let { return Ed25519PrivateKeyParameters(unb64(it), 0) }
        // Старый PKCS#8 читается без зависимости от поддержки Ed25519 в java.security Android.
        val legacy = prefs.getString("ed25519_priv", null)
            ?: error("На этом устройстве нет ключей аккаунта. Вход по одному имени невозможен.")
        return PrivateKeyFactory.createKey(unb64(legacy)) as Ed25519PrivateKeyParameters
    }

    fun edPublic(): ByteArray = edPrivate().generatePublicKey().encoded
    fun sign(data: ByteArray): ByteArray = Ed25519Signer().run {
        init(true, edPrivate())
        update(data, 0, data.size)
        generateSignature()
    }

    fun xIdentity(): ECKeyPair {
        val value = prefs.getString("x_identity_priv", null) ?: error("Нет identity-ключа")
        val privateKey = Curve.decodePrivatePoint(unb64(value))
        return ECKeyPair(privateKey.publicKey(), privateKey)
    }

    fun registrationId(): Int = prefs.getString("registration_id", null)?.toInt() ?: 1
    fun username(): String? = prefs.getString("username", null)
    fun userId(): String? = prefs.getString("user_id", null)
    fun phone(): String? = prefs.getString("phone", null)
    fun savePhone(phone: String) {
        check(prefs.edit().putString("phone", phone).commit())
    }
    fun saveUser(username: String, userId: String) {
        val existing = this.userId()
        check(existing == null || existing == userId) { "Смена аккаунта без переноса ключей запрещена" }
        check(prefs.edit().putString("username", username).putString("user_id", userId).commit())
    }

    fun sessionToken(): String? {
        val expires = prefs.getString("token_expires", null)
        if (expires != null && !Instant.parse(expires).isAfter(Instant.now())) return null
        return prefs.getString("token", null)
    }

    fun saveSession(token: String, expires: String) {
        check(prefs.edit().putString("token", token).putString("token_expires", expires).commit())
    }

    fun clearSession() {
        check(prefs.edit().remove("token").remove("token_expires").commit())
    }

    /** Полное стирание профиля: ключи, сессия, аккаунт. Только для «сжечь аккаунт». */
    fun wipeAll() {
        check(prefs.edit().clear().commit())
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(value: String) = Base64.decode(value, Base64.NO_WRAP)
}
