package com.umbra.app.crypto

import android.content.Context
import android.util.Base64
import com.umbra.app.data.api.PreKeyBundle
import com.umbra.app.data.api.RegisterRequest
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature

/**
 * Криптографический слой клиента Umbra.
 *
 * Два независимых набора ключей:
 *  1. Ed25519 — для АУТЕНТИФИКАЦИИ (подпись challenge). Сервер проверяет подпись,
 *     но не участвует в шифровании.
 *  2. X25519 (identity + signed pre-key + one-time pre-keys) — для E2E через
 *     Signal Protocol (X3DH + Double Ratchet). Приватные ключи НИКОГДА не покидают
 *     устройство: identity хранится в Android Keystore, сессии — в памяти.
 *
 * Примечание: Ed25519 через java.security доступен с API 33. Для minSdk 26
 * используйте Google Tink или BouncyCastle (см. README).
 */
class CryptoManager(context: Context) {

    private val securePrefs = SecurePrefs(context)

    /** Signal Protocol store (X3DH + Double Ratchet) для текущего пользователя. */
    private val signalStore: InMemorySignalProtocolStore by lazy {
        val identity = identityKeyPair()
        InMemorySignalProtocolStore(identity, REGISTRATION_ID)
    }

    /**
     * Генерирует и сохраняет identity-ключи (Ed25519 + X25519) и pre-keys.
     * Вызывается один раз при регистрации.
     */
    fun generateAndPersistIdentity() {
        // Ed25519 — для подписи challenge (аутентификация).
        val edKeyGen = KeyPairGenerator.getInstance("Ed25519")
        val edPair: KeyPair = edKeyGen.generateKeyPair()
        securePrefs.saveEd25519(edPair)

        // X25519 identity — для E2E.
        val xIdentity: ECKeyPair = Curve.generateKeyPair()
        securePrefs.saveXIdentity(xIdentity)
    }

    /** Identity-пара X25519 (для Signal Protocol). */
    private fun identityKeyPair(): IdentityKeyPair {
        val pair = securePrefs.xIdentity()
        return IdentityKeyPair(pair.publicKey, pair.privateKey)
    }

    /**
     * Собирает запрос регистрации: username + публичные ключи + подписанный pre-key.
     * Подпись signed pre-key делается Ed25519-ключом (как требует контракт сервера).
     */
    fun buildRegisterRequest(username: String): RegisterRequest {
        val ed = securePrefs.ed25519() ?: throw IllegalStateException("identity не сгенерирован")
        val xIdentity = securePrefs.xIdentity()

        val signedPreKey = Curve.generateKeyPair()
        securePrefs.saveSignedPreKey(signedPreKey)
        val spkBytes = signedPreKey.publicKey.serialize()
        val spkSignature = signEd25519(ed, spkBytes)

        val oneTimePreKeys = List(ONE_TIME_PREKEY_COUNT) {
            Curve.generateKeyPair().publicKey.serialize()
        }

        return RegisterRequest(
            username = username,
            identity_ed25519 = b64(ed.public.encoded),
            identity_x25519 = b64(xIdentity.publicKey.serialize()),
            signed_prekey = b64(spkBytes),
            signed_prekey_signature = b64(spkSignature),
            one_time_prekeys = oneTimePreKeys.map { b64(it) },
        )
    }

    /** Подписывает challenge сервера Ed25519-ключом. */
    fun signChallenge(challenge: String): String {
        val ed = securePrefs.ed25519() ?: throw IllegalStateException("identity не сгенерирован")
        return b64(signEd25519(ed, challenge.toByteArray(Charsets.UTF_8)))
    }

    // ---------- сессия / профиль ----------

    fun currentToken(): String? = securePrefs.sessionToken()
    fun saveUser(username: String, userId: String) {
        securePrefs.saveUsername(username)
        securePrefs.saveUserId(userId)
    }
    fun saveSession(token: String) = securePrefs.saveSessionToken(token)
    fun clearSession() = securePrefs.clearSession()
    fun userId(): String? = securePrefs.userId()
    fun username(): String? = securePrefs.username()

    /**
     * Устанавливает E2E-сессию с получателем через X3DH, используя его pre-key пакет.
     */
    fun establishSession(recipientUserId: String, bundle: PreKeyBundle) {
        val address = SignalProtocolAddress(recipientUserId, DEVICE_ID)
        val preKeyBundle = org.signal.libsignal.protocol.state.PreKeyBundle(
            REGISTRATION_ID,
            DEVICE_ID,
            preKeyId = 1,
            preKeyPublic = Curve.decodePoint(fromB64(bundle.identity_x25519), 0),
            signedPreKeyId = 1,
            signedPreKeyPublic = Curve.decodePoint(fromB64(bundle.signed_prekey), 0),
            signedPreKeySignature = fromB64(bundle.signed_prekey_signature),
            identityKey = org.signal.libsignal.protocol.IdentityKey(
                Curve.decodePoint(fromB64(bundle.identity_x25519), 0)
            ),
        )
        SessionBuilder(signalStore, address).process(preKeyBundle)
    }

    /**
     * Шифрует текст для получателя. Возвращает base64-сериализованный Signal message
     * (PreKeySignalMessage или SignalMessage), который сервер пересылает как opaque ciphertext.
     */
    fun encrypt(recipientUserId: String, plaintext: String): String {
        val address = SignalProtocolAddress(recipientUserId, DEVICE_ID)
        val cipher = SessionCipher(signalStore, address)
        val message: CiphertextMessage = cipher.encrypt(plaintext.toByteArray(Charsets.UTF_8))
        return b64(message.serialize())
    }

    /** Расшифровывает входящее сообщение от отправителя. */
    fun decrypt(senderUserId: String, ciphertextBase64: String): String {
        val address = SignalProtocolAddress(senderUserId, DEVICE_ID)
        val cipher = SessionCipher(signalStore, address)
        val bytes = fromB64(ciphertextBase64)
        val message: CiphertextMessage = try {
            PreKeySignalMessage(bytes)
        } catch (_: Exception) {
            SignalMessage(bytes)
        }
        val plaintext = cipher.decrypt(message)
        return String(plaintext, Charsets.UTF_8)
    }

    // ---------- вспомогательное ----------

    private fun signEd25519(pair: KeyPair, data: ByteArray): ByteArray {
        val sig = Signature.getInstance("Ed25519")
        sig.initSign(pair.private)
        sig.update(data)
        return sig.sign()
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun fromB64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    companion object {
        const val REGISTRATION_ID = 1
        const val DEVICE_ID = 1
        const val ONE_TIME_PREKEY_COUNT = 100
    }
}

/** Заглушка подписи SignedPreKeyRecord — сигнатура уточняется под версию libsignal. */
fun signedPreKeyRecord(id: Int, pair: ECKeyPair, signature: ByteArray): SignedPreKeyRecord =
    SignedPreKeyRecord(id, System.currentTimeMillis(), pair, signature)
