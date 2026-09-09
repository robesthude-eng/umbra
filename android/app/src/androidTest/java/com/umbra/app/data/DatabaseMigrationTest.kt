package com.umbra.app.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.umbra.app.data.db.AppDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @Test
    fun migrationKeepsLegacyCiphertextAndRoomValidatesSchema() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration_test_" + UUID.randomUUID() + ".db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                db.execSQL("CREATE TABLE messages (id TEXT NOT NULL PRIMARY KEY, senderId TEXT NOT NULL, recipientId TEXT NOT NULL, chatId TEXT NOT NULL, ciphertext TEXT NOT NULL, createdAt TEXT NOT NULL, expiresAt TEXT)")
                db.execSQL("CREATE TABLE chats (id TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, title TEXT NOT NULL)")
                db.execSQL("CREATE TABLE identity_keys (id TEXT NOT NULL PRIMARY KEY, identityKey TEXT NOT NULL, verified INTEGER NOT NULL)")
                db.execSQL("INSERT INTO messages VALUES ('old','alice','bob','bob','opaque','2026-01-01T00:00:00Z',NULL)")
                db.execSQL("INSERT INTO chats VALUES ('alice','dm','Alice')")
                db.execSQL("INSERT INTO identity_keys VALUES ('alice','identity',1)")
                db.version = 1
            }
            val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4).build()
            try {
                runBlocking {
                    val row = db.messageDao().get("old")!!
                    assertEquals("opaque", row.ciphertext)
                    assertEquals("", row.ownerId)
                    assertNull(row.localBody)
                    // Старые текстовые строки после MIGRATION_3_4 остаются без голосовых полей.
                    assertNull(row.localMediaPath)
                    assertNull(row.localMediaMime)
                    assertEquals(0L, row.localMediaDurationMs)
                    assertEquals("Alice", db.chatDao().get("alice")!!.title)
                    // После назначения owner таймер удаляет и ciphertext, и локальную копию.
                    db.messageDao().upsert(row.copy(ownerId = "bob", localBody = "encrypted", expiresAtMillis = 100))
                    db.messageDao().deleteExpired(100)
                    assertNull(db.messageDao().get("old"))
                }
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
