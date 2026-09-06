package com.umbra.app.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Room-БД для офлайн-очереди и локального кэша.
 *
 * ВАЖНО: хранятся ТОЛЬКО зашифрованные сообщения (ciphertext). Открытый текст
 * никогда не попадает в БД — расшифровка выполняется в памяти при отображении.
 */

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val senderId: String,
    val recipientId: String,
    val chatId: String,
    val ciphertext: String, // base64
    val createdAt: String,
    val expiresAt: String?,
)

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: String,
    val type: String,
    val title: String,
)

@Entity(tableName = "identity_keys")
data class IdentityKeyEntity(
    @PrimaryKey val id: String, // user id (получателя)
    val identityKey: String,     // base64 открытого identity-ключа X25519
    val verified: Boolean,       // ручная проверка safety number
)

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE chatId = :chatId OR recipientId = :chatId ORDER BY createdAt ASC")
    fun messagesFor(chatId: String): Flow<List<MessageEntity>>

    @Query("DELETE FROM messages WHERE expiresAt IS NOT NULL AND expiresAt < :now")
    suspend fun deleteExpired(now: String)
}

@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(chat: ChatEntity)

    @Query("SELECT * FROM chats ORDER BY title ASC")
    fun all(): Flow<List<ChatEntity>>
}

@Dao
interface IdentityKeyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(key: IdentityKeyEntity)

    @Query("SELECT * FROM identity_keys WHERE id = :id")
    suspend fun get(id: String): IdentityKeyEntity?
}

@Database(
    entities = [MessageEntity::class, ChatEntity::class, IdentityKeyEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun chatDao(): ChatDao
    abstract fun identityKeyDao(): IdentityKeyDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "umbra.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
