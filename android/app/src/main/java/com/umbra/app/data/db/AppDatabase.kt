package com.umbra.app.data.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "messages", indices = [
    Index(value = ["ownerId", "chatId", "createdAtMillis"]),
    Index(value = ["ownerId", "deliveryState"]),
])
data class MessageEntity(
    @PrimaryKey val id: String,
    val senderId: String,
    val recipientId: String,
    val chatId: String,
    val ciphertext: String,
    val createdAt: String,
    val expiresAt: String?,
    @ColumnInfo(defaultValue = "''") val ownerId: String = "",
    @ColumnInfo(defaultValue = "NULL") val localBody: String? = null,
    @ColumnInfo(defaultValue = "0") val createdAtMillis: Long = 0,
    @ColumnInfo(defaultValue = "NULL") val expiresAtMillis: Long? = null,
    @ColumnInfo(defaultValue = "'sent'") val deliveryState: String = "sent",
    @ColumnInfo(defaultValue = "NULL") val clientId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val error: String? = null,
    @ColumnInfo(defaultValue = "0") val expiresInSeconds: Long = 0,
)

@Entity(tableName = "chats")
data class ChatEntity(@PrimaryKey val id: String, val type: String, val title: String)

/**
 * Контакт из телефонной книги + результат поиска в Umbra.
 * umbraUserId != null — человек зарегистрирован, ему можно писать.
 */
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val phoneHash: String,
    val phone: String,
    val name: String,
    val umbraUserId: String? = null,
    val umbraUsername: String? = null,
    val umbraDisplayName: String? = null,
    val syncedAt: Long = 0,
)

@Entity(tableName = "identity_keys")
data class IdentityKeyEntity(@PrimaryKey val id: String, val identityKey: String, val verified: Boolean)

/** Все value зашифрованы LocalVault; ключи записи включены в AEAD associated data. */
@Entity(tableName = "crypto_records", primaryKeys = ["owner", "kind", "recordKey"])
data class CryptoRecord(val owner: String, val kind: String, val recordKey: String, val value: String)

@Dao
interface CryptoDao {
    @Query("SELECT * FROM crypto_records WHERE owner = :owner AND kind = :kind AND recordKey = :key")
    fun get(owner: String, kind: String, key: String): CryptoRecord?

    @Query("SELECT * FROM crypto_records WHERE owner = :owner AND kind = :kind")
    fun all(owner: String, kind: String): List<CryptoRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(record: CryptoRecord)

    @Query("DELETE FROM crypto_records WHERE owner = :owner AND kind = :kind AND recordKey = :key")
    fun delete(owner: String, kind: String, key: String)
}

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE ownerId = :owner ORDER BY createdAtMillis, id")
    fun all(owner: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE ownerId = :owner AND chatId = :chatId ORDER BY createdAtMillis, id")
    fun messagesFor(owner: String, chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    fun get(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE ownerId = :owner AND deliveryState = 'pending' ORDER BY createdAtMillis, id LIMIT 50")
    fun pending(owner: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE ownerId = :owner AND localBody IS NULL AND deliveryState = 'sent' ORDER BY createdAtMillis, id LIMIT 100")
    fun unreadCiphertexts(owner: String): List<MessageEntity>

    @Query("SELECT MAX(createdAtMillis) FROM messages WHERE ownerId = :owner")
    fun maxCreatedAtMillis(owner: String): Long?

    @Query("DELETE FROM messages WHERE id = :id")
    fun delete(id: String)

    @Query("DELETE FROM messages WHERE expiresAtMillis IS NOT NULL AND expiresAtMillis <= :now")
    fun deleteExpired(now: Long)

    @Query("SELECT * FROM messages WHERE ownerId = ''")
    fun legacyMessages(): List<MessageEntity>
}

@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(chat: ChatEntity)

    @Query("SELECT * FROM chats WHERE id = :id")
    fun get(id: String): ChatEntity?

    @Query("SELECT * FROM chats WHERE id = :id")
    fun observe(id: String): Flow<ChatEntity?>

    @Query("SELECT * FROM chats ORDER BY title ASC")
    fun all(): Flow<List<ChatEntity>>
}

@Dao
interface ContactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(contacts: List<ContactEntity>)

    /** Сначала зарегистрированные в Umbra (по имени), затем остальные. */
    @Query("SELECT * FROM contacts ORDER BY (umbraUserId IS NOT NULL) DESC, name ASC")
    fun all(): Flow<List<ContactEntity>>

    @Query("DELETE FROM contacts")
    fun clear()
}

@Dao
interface IdentityKeyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(key: IdentityKeyEntity)
    @Query("SELECT * FROM identity_keys WHERE id = :id")
    suspend fun get(id: String): IdentityKeyEntity?
}

@Database(
    entities = [MessageEntity::class, ChatEntity::class, ContactEntity::class, IdentityKeyEntity::class, CryptoRecord::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun chatDao(): ChatDao
    abstract fun contactDao(): ContactDao
    abstract fun identityKeyDao(): IdentityKeyDao
    abstract fun cryptoDao(): CryptoDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN ownerId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN localBody TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN createdAtMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN expiresAtMillis INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN deliveryState TEXT NOT NULL DEFAULT 'sent'")
                db.execSQL("ALTER TABLE messages ADD COLUMN clientId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN error TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN expiresInSeconds INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX index_messages_ownerId_chatId_createdAtMillis ON messages(ownerId,chatId,createdAtMillis)")
                db.execSQL("CREATE INDEX index_messages_ownerId_deliveryState ON messages(ownerId,deliveryState)")
                db.execSQL("CREATE TABLE IF NOT EXISTS crypto_records (owner TEXT NOT NULL, kind TEXT NOT NULL, recordKey TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(owner,kind,recordKey))")
                // Старый ciphertext сохраняем. Направление и даты нормализуем после проверки аккаунта.
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Телефонная книга + статус регистрации в Umbra; заполняется синхронизацией.
                db.execSQL("CREATE TABLE IF NOT EXISTS contacts (phoneHash TEXT NOT NULL, phone TEXT NOT NULL, name TEXT NOT NULL, umbraUserId TEXT DEFAULT NULL, umbraUsername TEXT DEFAULT NULL, umbraDisplayName TEXT DEFAULT NULL, syncedAt INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(phoneHash))")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "umbra.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
