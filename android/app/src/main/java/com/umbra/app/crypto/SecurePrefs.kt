package com.umbra.app.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Хранит приватные ключи в EncryptedSharedPreferences (шифрование на мастер-ключе
 * Android Keystore). Приватный материал не покидает устройство и не попадает в
 * обычные SharedPreferences.
 */
class SecurePrefs(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "umbra_secure",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    // Ed25519 (аутентификация)
    fun saveEd25519(pair: KeyPair) {
        prefs.edit()
            .putString("ed25519_priv", b64(pair.private.encoded))
            .putString("ed25519_pub", b64(pair.public.encoded))
            .apply()
    }

    fun ed25519(): KeyPair? {
        val priv = prefs.getString("ed25519_priv", null) ?: return null
        val pub = prefs.getString("ed25519_pub", null) ?: return null
        val kf = KeyFactory.getInstance("Ed25519")
        val privKey = kf.generatePrivate(PKCS8EncodedKeySpec(fromB64(priv)))
        val pubKey = kf.generatePublic(X509EncodedKeySpec(fromB64(pub)))
        return KeyPair(pubKey, privKey)
    }

    // X25519 identity (E2E)
    fun saveXIdentity(pair: ECKeyPair) {
        prefs.edit()
            .putString("x_identity_priv", b64(pair.privateKey.serialize()))
            .apply()
    }

    fun xIdentity(): ECKeyPair {
        val priv = fromB64(prefs.getString("x_identity_priv", null) ?: throw IllegalStateException("x25519 не сгенерирован"))
        val privateKey: ECPrivateKey = Curve.decodePrivatePoint(priv)
        return ECKeyPair(privateKey.publicKey(), privateKey)
    }

    // signed pre-key (временный, для текущей сессии регистрации)
    fun saveSignedPreKey(pair: ECKeyPair) {
        prefs.edit().putString("signed_prekey_priv", b64(pair.privateKey.serialize())).apply()
    }

    fun sessionToken(): String? = prefs.getString("token", null)
    fun saveSessionToken(token: String) = prefs.edit().putString("token", token).apply()
    fun userId(): String? = prefs.getString("user_id", null)
    fun saveUserId(id: String) = prefs.edit().putString("user_id", id).apply()
    fun username(): String? = prefs.getString("username", null)
    fun saveUsername(name: String) = prefs.edit().putString("username", name).apply()
    fun clearSession() = prefs.edit().remove("token").remove("user_id").apply()

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun fromB64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}
