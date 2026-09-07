package com.umbra.app.crypto

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.umbra.app.data.api.PreKeyBundle
import com.umbra.app.data.api.RegisterRequest
import com.umbra.app.data.db.AppDatabase
import com.umbra.app.data.db.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CryptoPersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    internal class Profile(private val context: Context) : AutoCloseable {
        private val name = "umbra_test_" + UUID.randomUUID()
        private fun database() = Room.databaseBuilder(context, AppDatabase::class.java, name + ".db")
            .addMigrations(AppDatabase.MIGRATION_1_2).build()
        var db = database()
        var crypto = CryptoManager(context, db, SecurePrefs(context, name), LocalVault(name))
        init { crypto.ensureIdentity() }
        fun restart() {
            db.close()
            db = database()
            crypto = CryptoManager(context, db, SecurePrefs(context, name), LocalVault(name))
        }
        override fun close() {
            db.close()
            context.deleteDatabase(name + ".db")
            context.deleteSharedPreferences(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(name) }
        }
    }

    private fun RegisterRequest.bundle(id: String) = PreKeyBundle(
        id, username, identity_ed25519, identity_x25519, signed_prekey, signed_prekey_signature,
        one_time_prekeys.first(), key_version, registration_id, signed_prekey_id,
        one_time_prekey_ids.first(), 1,
    )

    @Test
    fun ratchetAndHistorySurviveRestartAndRollback() = runBlocking {
        Profile(context).use { alice -> Profile(context).use { bob ->
            val bobKeys = bob.db.withTransaction { bob.crypto.buildRegisterRequest("bob") }
            alice.db.withTransaction {
                alice.crypto.buildRegisterRequest("alice")
                alice.crypto.establishSession("bob", bobKeys.bundle("bob"))
            }
            val ciphertext = alice.db.withTransaction { alice.crypto.encrypt("bob", "first message") }
            val before = bob.db.withTransaction { bob.crypto.store().records("prekey").size }
            // Имитируем падение между decrypt (ratchet + removePreKey) и записью истории.
            try {
                bob.db.withTransaction {
                    assertEquals("first message", bob.crypto.decrypt("alice", ciphertext))
                    throw Rollback()
                }
                fail("rollback not triggered")
            } catch (_: Rollback) { }
            assertEquals(before, bob.db.withTransaction { bob.crypto.store().records("prekey").size })
            bob.db.withTransaction {
                val clear = bob.crypto.decrypt("alice", ciphertext)
                bob.db.messageDao().upsert(MessageEntity("m1", "alice", "bob", "alice", ciphertext,
                    "2026-01-01T00:00:00Z", null, ownerId = "bob",
                    localBody = bob.crypto.sealHistory("bob", "m1", clear)))
            }
            assertEquals(before - 1, bob.db.withTransaction { bob.crypto.store().records("prekey").size })
            alice.restart()
            bob.restart()
            val saved = bob.db.messageDao().get("m1")!!
            repeat(3) { assertEquals("first message", bob.crypto.openHistory("bob", "m1", saved.localBody!!)) }
            assertFalse(saved.localBody!!.contains("first message"))
            val reply = bob.db.withTransaction { bob.crypto.encrypt("alice", "reply after restart") }
            assertEquals("reply after restart", alice.db.withTransaction { alice.crypto.decrypt("bob", reply) })
            val next = alice.db.withTransaction { alice.crypto.encrypt("bob", "next ratchet message") }
            bob.restart()
            assertEquals("next ratchet message", bob.db.withTransaction { bob.crypto.decrypt("alice", next) })
        } }
    }

    @Test
    fun keyPublicationCanBeRetriedWithoutReplacingIdentity() = runBlocking {
        Profile(context).use { profile ->
            val first = profile.db.withTransaction { profile.crypto.buildRegisterRequest("alice") }
            profile.restart()
            profile.crypto.ensureIdentity()
            val retry = profile.db.withTransaction { profile.crypto.buildRegisterRequest("alice") }
            assertEquals(first, retry)
            profile.crypto.saveUser("alice", "alice-id")
            profile.crypto.saveSession("test-token", "2099-01-01T00:00:00Z")
            profile.crypto.clearSession()
            assertNull(profile.crypto.currentToken())
            assertEquals("alice-id", profile.crypto.userId())
            assertEquals(first.identity_ed25519, retry.identity_ed25519)
            profile.db.withTransaction { profile.crypto.markKeysPublished() }
            val replenished = profile.db.withTransaction { profile.crypto.buildRegisterRequest("alice") }
            assertEquals(first.identity_x25519, replenished.identity_x25519)
            assertNotEquals(first.key_bundle_id, replenished.key_bundle_id)
            assertTrue(first.one_time_prekey_ids.toSet().intersect(replenished.one_time_prekey_ids.toSet()).isEmpty())
        }
    }

    @Test
    fun changedIdentityIsRejected() = runBlocking {
        Profile(context).use { alice -> Profile(context).use { bob -> Profile(context).use { replacement ->
            val original = bob.db.withTransaction { bob.crypto.buildRegisterRequest("bob") }
            val changed = replacement.db.withTransaction { replacement.crypto.buildRegisterRequest("bob") }
            alice.db.withTransaction { alice.crypto.establishSession("bob", original.bundle("bob")) }
            try {
                alice.db.withTransaction { alice.crypto.establishSession("bob", changed.bundle("bob")) }
                fail("changed identity accepted")
            } catch (_: IllegalStateException) { }
        } } }
    }

    private class Rollback : RuntimeException()
}
