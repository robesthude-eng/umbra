package com.umbra.app.crypto

import android.util.Base64
import com.umbra.app.data.db.CryptoDao
import com.umbra.app.data.db.CryptoRecord
import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.*
import java.util.UUID

/**
 * Контракт libsignal 0.60.1. Не кэшируем изменяемые session records:
 * откат Room-транзакции откатывает и ratchet, и удаление одноразового ключа.
 */
class PersistentSignalStore(
    private val dao: CryptoDao,
    private val vault: LocalVault,
    private val owner: String,
    private val identity: IdentityKeyPair,
    private val registrationId: Int,
) : SignalProtocolStore {
    private fun address(a: SignalProtocolAddress) = name(a.name) + ":" + a.deviceId
    private fun name(s: String) = Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
    private fun aad(kind: String, key: String) = "signal:$owner:$kind:$key"

    fun read(kind: String, key: String): ByteArray? =
        dao.get(owner, kind, key)?.let { vault.open(aad(kind, key), it.value) }

    fun write(kind: String, key: String, bytes: ByteArray) =
        dao.put(CryptoRecord(owner, kind, key, vault.seal(aad(kind, key), bytes)))

    fun records(kind: String): List<Pair<String, ByteArray>> =
        dao.all(owner, kind).map { it.recordKey to vault.open(aad(kind, it.recordKey), it.value) }

    private fun remove(kind: String, key: String) = dao.delete(owner, kind, key)

    fun clear(kind: String, key: String) = remove(kind, key)

    override fun getIdentityKeyPair(): IdentityKeyPair = identity
    override fun getLocalRegistrationId(): Int = registrationId
    override fun getIdentity(a: SignalProtocolAddress): IdentityKey? =
        read("identity", address(a))?.let { IdentityKey(it, 0) }

    override fun isTrustedIdentity(a: SignalProtocolAddress, key: IdentityKey, direction: IdentityKeyStore.Direction): Boolean {
        val saved = getIdentity(a)
        return saved == null || saved == key
    }

    override fun saveIdentity(a: SignalProtocolAddress, key: IdentityKey): Boolean {
        val old = getIdentity(a)
        check(old == null || old == key) { "Ключ собеседника изменился. Нужна повторная проверка личности." }
        write("identity", address(a), key.serialize())
        return old != key
    }

    override fun loadSession(a: SignalProtocolAddress): SessionRecord? =
        read("session", address(a))?.let { SessionRecord(it) }
    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.map { loadSession(it) ?: throw NoSessionException(it, "Нет сессии") }
    override fun containsSession(a: SignalProtocolAddress) = dao.get(owner, "session", address(a)) != null
    override fun storeSession(a: SignalProtocolAddress, record: SessionRecord) = write("session", address(a), record.serialize())
    override fun deleteSession(a: SignalProtocolAddress) = remove("session", address(a))
    override fun deleteAllSessions(name: String) {
        dao.all(owner, "session").filter { it.recordKey.startsWith(this.name(name) + ":") }
            .forEach { remove("session", it.recordKey) }
    }
    override fun getSubDeviceSessions(name: String): List<Int> =
        dao.all(owner, "session").filter { it.recordKey.startsWith(this.name(name) + ":") }
            .map { it.recordKey.substringAfterLast(':').toInt() }.filter { it != 1 }

    override fun loadPreKey(id: Int) = PreKeyRecord(read("prekey", "$id") ?: throw InvalidKeyIdException("Нет pre-key"))
    override fun storePreKey(id: Int, record: PreKeyRecord) = write("prekey", "$id", record.serialize())
    override fun containsPreKey(id: Int) = dao.get(owner, "prekey", "$id") != null
    override fun removePreKey(id: Int) = remove("prekey", "$id")
    override fun loadSignedPreKey(id: Int) = SignedPreKeyRecord(read("signed", "$id") ?: throw InvalidKeyIdException("Нет signed pre-key"))
    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> = records("signed").map { SignedPreKeyRecord(it.second) }
    override fun storeSignedPreKey(id: Int, record: SignedPreKeyRecord) = write("signed", "$id", record.serialize())
    override fun containsSignedPreKey(id: Int) = dao.get(owner, "signed", "$id") != null
    override fun removeSignedPreKey(id: Int) = remove("signed", "$id")

    override fun loadKyberPreKey(id: Int) = KyberPreKeyRecord(read("kyber", "$id") ?: throw InvalidKeyIdException("Нет Kyber pre-key"))
    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> = records("kyber").map { KyberPreKeyRecord(it.second) }
    override fun storeKyberPreKey(id: Int, record: KyberPreKeyRecord) = write("kyber", "$id", record.serialize())
    override fun containsKyberPreKey(id: Int) = dao.get(owner, "kyber", "$id") != null
    override fun markKyberPreKeyUsed(id: Int) = write("kyber-used", "$id", byteArrayOf(1))

    override fun storeSenderKey(a: SignalProtocolAddress, id: UUID, record: SenderKeyRecord) =
        write("sender", address(a) + ":" + id, record.serialize())
    override fun loadSenderKey(a: SignalProtocolAddress, id: UUID): SenderKeyRecord? =
        read("sender", address(a) + ":" + id)?.let { SenderKeyRecord(it) }
}
