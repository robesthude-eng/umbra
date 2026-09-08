package com.umbra.app.crypto

import android.content.Context
import android.util.Base64
import com.umbra.app.data.api.PreKeyBundle
import com.umbra.app.data.api.RegisterRequest
import com.umbra.app.data.db.AppDatabase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Все операции Signal и запись результата выполняются внутри одной Room-транзакции. */
class CryptoManager(
    context: Context,
    private val db: AppDatabase,
    private val prefs: SecurePrefs = SecurePrefs(context),
    private val vault: LocalVault = LocalVault(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    fun ensureIdentity() = prefs.ensureIdentity()
    fun hasIdentity() = prefs.hasIdentity()
    fun currentToken() = prefs.sessionToken()
    fun saveSession(token: String, expires: String) = prefs.saveSession(token, expires)
    fun clearSession() = prefs.clearSession()
    fun wipeAll() = prefs.wipeAll()
    fun saveUser(username: String, userId: String) = prefs.saveUser(username, userId)
    fun userId() = prefs.userId()
    fun username() = prefs.username()
    fun phone() = prefs.phone()
    fun savePhone(phone: String) = prefs.savePhone(phone)
    fun signChallenge(challenge: String) = b64(prefs.sign(challenge.toByteArray(Charsets.UTF_8)))

    fun store(): PersistentSignalStore {
        check(db.inTransaction()) { "Signal operation requires a database transaction" }
        val pair = prefs.xIdentity()
        val owner = b64(MessageDigest.getInstance("SHA-256").digest(pair.publicKey.serialize()))
        return PersistentSignalStore(db.cryptoDao(), vault, owner,
            IdentityKeyPair(IdentityKey(pair.publicKey), pair.privateKey), prefs.registrationId())
    }

    fun needsKeyPublication(serverVersion: Int, serverCount: Int): Boolean {
        val store = store()
        return serverVersion < 2 || store.read("meta", "published") == null ||
            serverCount < 20 || store.read("meta", "pending") != null
    }

    fun buildRegisterRequest(username: String, phone: String = "", name: String = ""): RegisterRequest {
        val store = store()
        store.read("meta", "pending")?.let {
            return json.decodeFromString<RegisterRequest>(String(it, Charsets.UTF_8))
                .copy(username = username, phone = phone, name = name)
        }
        val identity = prefs.xIdentity()
        val signed = store.loadSignedPreKeys().maxByOrNull { it.timestamp } ?: run {
            val pair = Curve.generateKeyPair()
            val signature = Curve.calculateSignature(identity.privateKey, pair.publicKey.serialize())
            SignedPreKeyRecord(nextId { store.containsSignedPreKey(it) }, System.currentTimeMillis(), pair, signature)
                .also { store.storeSignedPreKey(it.id, it) }
        }
        // Старые private pre-keys сохраняем для сообщений, уже находящихся в пути.
        val prekeys = List(100) {
            PreKeyRecord(nextId { store.containsPreKey(it) }, Curve.generateKeyPair())
                .also { store.storePreKey(it.id, it) }
        }
        val request = RegisterRequest(
            username = username,
            phone = phone,
            name = name,
            identity_ed25519 = b64(prefs.edPublic()),
            identity_x25519 = b64(identity.publicKey.serialize()),
            signed_prekey = b64(signed.keyPair.publicKey.serialize()),
            signed_prekey_signature = b64(signed.signature),
            one_time_prekeys = prekeys.map { b64(it.keyPair.publicKey.serialize()) },
            key_version = 2,
            registration_id = prefs.registrationId(),
            signed_prekey_id = signed.id,
            one_time_prekey_ids = prekeys.map { it.id },
            key_bundle_id = UUID.randomUUID().toString(),
        )
        store.write("meta", "pending", json.encodeToString(request).toByteArray(Charsets.UTF_8))
        return request
    }

    fun markKeysPublished() {
        val store = store()
        store.write("meta", "published", byteArrayOf(2))
        // Удаление pending через DAO сохраняется атомарно вместе с меткой.
        store.clear("meta", "pending")
    }

    fun establishSession(userId: String, bundle: PreKeyBundle) {
        require(bundle.key_version == 2 && bundle.device_id == 1) { "Собеседнику нужно обновить клиент и ключи" }
        val store = store()
        val address = SignalProtocolAddress(userId, 1)
        val identity = IdentityKey(Curve.decodePoint(unb64(bundle.identity_x25519), 0))
        check(store.isTrustedIdentity(address, identity, org.signal.libsignal.protocol.state.IdentityKeyStore.Direction.SENDING)) {
            "Ключ собеседника изменился. Проверьте его личность."
        }
        if (store.containsSession(address)) return
        val hasPrekey = bundle.one_time_prekey.isNotEmpty()
        if (hasPrekey) require(bundle.one_time_prekey_id > 0)
        val signalBundle = org.signal.libsignal.protocol.state.PreKeyBundle(
            bundle.registration_id, bundle.device_id,
            if (hasPrekey) bundle.one_time_prekey_id else -1,
            if (hasPrekey) Curve.decodePoint(unb64(bundle.one_time_prekey), 0) else null,
            bundle.signed_prekey_id, Curve.decodePoint(unb64(bundle.signed_prekey), 0),
            unb64(bundle.signed_prekey_signature), identity,
        )
        SessionBuilder(store, address).process(signalBundle)
    }

    fun encrypt(recipient: String, plaintext: String): String {
        val message = SessionCipher(store(), SignalProtocolAddress(recipient, 1))
            .encrypt(plaintext.toByteArray(Charsets.UTF_8))
        // Это версия контейнера, не новая криптосхема; bytes внутри — результат libsignal.
        return b64(byteArrayOf(85, 77, 66, 2, message.type.toByte()) + message.serialize())
    }

    fun decrypt(sender: String, value: String): String {
        val bytes = unb64(value)
        val cipher = SessionCipher(store(), SignalProtocolAddress(sender, 1))
        val clear = if (bytes.size > 5 && bytes[0] == 85.toByte() && bytes[1] == 77.toByte() && bytes[2] == 66.toByte()) {
            require(bytes[3] == 2.toByte()) { "Неподдерживаемая версия сообщения" }
            val payload = bytes.copyOfRange(5, bytes.size)
            when (bytes[4].toInt()) {
                CiphertextMessage.PREKEY_TYPE -> cipher.decrypt(PreKeySignalMessage(payload))
                CiphertextMessage.WHISPER_TYPE -> cipher.decrypt(SignalMessage(payload))
                else -> error("Неизвестный тип Signal-сообщения")
            }
        } else {
            // Совместимость с прежним контейнером: только разбор формата может
            // переключить тип; ошибки доверия/расшифровки не маскируются.
            val prekey = try { PreKeySignalMessage(bytes) } catch (_: InvalidMessageException) { null }
                catch (_: InvalidVersionException) { null }
            if (prekey != null) cipher.decrypt(prekey) else cipher.decrypt(SignalMessage(bytes))
        }
        return String(clear, Charsets.UTF_8)
    }

    fun sealHistory(owner: String, id: String, text: String) =
        vault.seal("history:" + owner + ":" + id, text.toByteArray(Charsets.UTF_8))
    fun openHistory(owner: String, id: String, value: String) =
        String(vault.open("history:" + owner + ":" + id, value), Charsets.UTF_8)

    // ---------- шифрование медиа (AES-256-GCM на файл) ----------
    //
    // Каждый файл шифруется одноразовым ключом; ключ и nonce вкладываются
    // в E2E-конверт сообщения (Signal-сессия), на сервер уходит только ciphertext.

    /** Новый AES-256 ключ файла (32 байта, SecureRandom). */
    fun newFileKey(): ByteArray = ByteArray(FILE_KEY_BYTES).also { random.nextBytes(it) }

    /** Новый GCM-nonce (12 байт); НИКОГДА не переиспользовать с тем же ключом. */
    fun newFileNonce(): ByteArray = ByteArray(GCM_NONCE_BYTES).also { random.nextBytes(it) }

    /** Шифрует байты файла: ciphertext = plaintext || GCM-тег (16 байт). */
    fun encryptFileBytes(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(plaintext)
    }

    /** Расшифровывает байты файла; при подмене ciphertext бросает AEADBadTagException. */
    fun decryptFileBytes(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(ciphertext)
    }

    private fun nextId(used: (Int) -> Boolean): Int {
        var id: Int
        do { id = random.nextInt(0xffffff) + 1 } while (used(id))
        return id
    }
    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(value: String) = Base64.decode(value, Base64.NO_WRAP)

    companion object {
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val FILE_KEY_BYTES = 32
        private const val GCM_NONCE_BYTES = 12
    }
}
