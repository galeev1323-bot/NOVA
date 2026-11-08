package com.nova.messenger.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val bio: String? = null,
    @SerialName("last_seen") val lastSeen: String? = null,
    @SerialName("hide_last_seen") val hideLastSeen: Boolean = false,
    @SerialName("is_premium") val isPremium: Boolean = false,
    @SerialName("premium_until") val premiumUntil: String? = null,
    @SerialName("chat_theme") val chatTheme: String = "default",
    @SerialName("avatar_frame") val avatarFrame: String = "none",
    @SerialName("profile_effect") val profileEffect: String = "none",
)

@Entity(tableName = "chats")
@Serializable
data class Chat(
    @PrimaryKey val id: String,
    @SerialName("is_group") val isGroup: Boolean = false,
    val title: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("other_user_id") val otherUserId: String? = null,
    @SerialName("last_body") val lastBody: String? = null,
    @SerialName("last_at") val lastAt: String? = null,
    val unread: Long = 0,
)

@Entity(tableName = "messages")
@Serializable
data class Message(
    @PrimaryKey val id: String,
    @SerialName("chat_id") val chatId: String,
    @SerialName("sender_id") val senderId: String,
    val body: String? = null,
    @SerialName("media_url") val mediaUrl: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("media_name") val mediaName: String? = null,
    @SerialName("reply_to") val replyTo: String? = null,
    val forwarded: Boolean = false,
    @SerialName("edited_at") val editedAt: String? = null,
    @SerialName("created_at") val createdAt: String = "",
)

@Entity(tableName = "reactions", primaryKeys = ["messageId", "userId"])
@Serializable
data class Reaction(
    @SerialName("message_id") val messageId: String,
    @SerialName("user_id") val userId: String,
    val emoji: String,
)

@Serializable
data class MessageInsert(
    @SerialName("chat_id") val chatId: String,
    val body: String? = null,
    @SerialName("media_url") val mediaUrl: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("media_name") val mediaName: String? = null,
    @SerialName("reply_to") val replyTo: String? = null,
    val forwarded: Boolean = false,
)

@Serializable
data class BlockRow(@SerialName("blocked_id") val blockedId: String)

@Dao
interface NovaDao {
    @Query("SELECT * FROM chats ORDER BY lastAt DESC") fun chats(): Flow<List<Chat>>
    @Query("SELECT * FROM chats WHERE id=:id") fun chat(id: String): Flow<Chat?>
    @Query("SELECT * FROM chats WHERE id=:id") suspend fun chatOnce(id: String): Chat?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertChats(l: List<Chat>)
    @Query("DELETE FROM chats WHERE id NOT IN (:ids)") suspend fun pruneChats(ids: List<String>)
    @Query("UPDATE chats SET unread=0 WHERE id=:c") suspend fun clearUnread(c: String)

    @Query("SELECT * FROM messages WHERE chatId=:c ORDER BY createdAt ASC") fun messages(c: String): Flow<List<Message>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertMessages(l: List<Message>)
    @Query("DELETE FROM messages WHERE id=:id") suspend fun deleteMessage(id: String)

    @Query("SELECT * FROM reactions WHERE messageId IN (SELECT id FROM messages WHERE chatId=:c)")
    fun reactions(c: String): Flow<List<Reaction>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertReactions(l: List<Reaction>)
    @Query("DELETE FROM reactions WHERE messageId=:m AND userId=:u") suspend fun deleteReaction(m: String, u: String)
}

@Database(entities = [Chat::class, Message::class, Reaction::class], version = 1, exportSchema = false)
abstract class NovaDb : RoomDatabase() { abstract fun dao(): NovaDao }
